package com.github.unknownnpc.scalaxcom.auth

import java.time.Clock

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import com.github.unknownnpc.scalaxcom.logging.Logging

/** Where the client gets the OAuth 2.0 user access token for each request. */
private[scalaxcom] sealed trait AccessTokenSource:

  /** The token to send now. */
  private[scalaxcom] def accessToken(): Future[String]

  /** X rejected `rejected` (401): a replacement to retry with once, or `None`
    * if there is no way to get one.
    */
  private[scalaxcom] def afterRejection(
      rejected: String,
    ): Option[Future[String]]

private[scalaxcom] object AccessTokenSource:

  /** A fixed token: when it expires, requests fail with 401. */
  final case class Static(token: String) extends AccessTokenSource:
    private[scalaxcom] def accessToken(): Future[String] =
      Future.successful(token)
    private[scalaxcom] def afterRejection(
        rejected: String,
      ): Option[Future[String]] = None
    override def toString: String = "Static(***)"

/** An OAuth 2.0 user token that keeps itself valid: refreshed shortly before it
  * expires, and once more if X rejects it anyway (revoked, clock skew). Needs a
  * refresh token, i.e. the `offline.access` scope.
  *
  * X rotates refresh tokens, so concurrent requests share one refresh rather
  * than each burning the token the other just got, and `onRefresh` gets every
  * new token: store it, or the next run starts with a dead refresh token.
  *
  * {{{
  * val session = OAuth2Session(flow, storedToken, onRefresh = save)
  * val x = Scalaxcom(Credentials.oauth2User(session))
  * }}}
  */
final class OAuth2Session private (
    flow: OAuth2Flow,
    initial: OAuth2Token,
    onRefresh: OAuth2Token => Unit,
    refreshMargin: FiniteDuration,
    clock: Clock,
  )(using ExecutionContext)
    extends AccessTokenSource
    with Logging:

  private var token: OAuth2Token = initial
  private var inFlight: Option[Future[OAuth2Token]] = None

  /** The latest token, refreshed or not. */
  def currentToken: OAuth2Token = synchronized(token)

  /** Refreshes now, whatever the expiry says. */
  def refresh(): Future[OAuth2Token] = synchronized {
    inFlight.getOrElse {
      val refreshToken = token.refreshToken.getOrElse(
        throw IllegalStateException(
          "No refresh token: authorize with the offline.access scope",
        ),
      )
      val started = flow.refresh(refreshToken).andThen {
        case Success(fresh) =>
          synchronized {
            token = fresh
            inFlight = None
          }
          try onRefresh(fresh)
          catch
            case NonFatal(e) =>
              logger.error(s"onRefresh failed: ${e.getMessage}", e)
        case Failure(_) => synchronized { inFlight = None }
      }
      inFlight = Some(started)
      started
    }
  }

  private[scalaxcom] def accessToken(): Future[String] =
    val current = currentToken
    if current.refreshToken.isDefined &&
      current.expiresWithin(refreshMargin, clock.instant())
    then refresh().map(_.accessToken)
    else Future.successful(current.accessToken)

  private[scalaxcom] def afterRejection(
      rejected: String,
    ): Option[Future[String]] =
    val current = currentToken
    if current.accessToken != rejected then
      Some(Future.successful(current.accessToken)) // already replaced
    else current.refreshToken.map(_ => refresh().map(_.accessToken))

  override def toString: String = s"OAuth2Session($currentToken)"

object OAuth2Session:

  /** @param refreshMargin
    *   refresh this long before the token expires.
    */
  def apply(
      flow: OAuth2Flow,
      token: OAuth2Token,
      onRefresh: OAuth2Token => Unit = _ => (),
      refreshMargin: FiniteDuration = 1.minute,
    )(using ExecutionContext,
    ): OAuth2Session =
    new OAuth2Session(flow, token, onRefresh, refreshMargin, Clock.systemUTC())

  private[scalaxcom] def withClock(
      flow: OAuth2Flow,
      token: OAuth2Token,
      onRefresh: OAuth2Token => Unit,
      refreshMargin: FiniteDuration,
      clock: Clock,
    )(using ExecutionContext,
    ): OAuth2Session =
    new OAuth2Session(flow, token, onRefresh, refreshMargin, clock)
