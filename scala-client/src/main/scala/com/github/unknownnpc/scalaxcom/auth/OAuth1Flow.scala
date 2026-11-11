package com.github.unknownnpc.scalaxcom.auth

import java.time.Clock

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

import com.github.unknownnpc.scalaxcom.exception.ScalaxcomClientException

import sttp.client3.UriContext
import sttp.model.Header
import sttp.model.HeaderNames

/** A temporary token from step 1 of the OAuth 1.0a login. */
final case class OAuth1RequestToken(token: String, tokenSecret: String):
  override def toString: String = s"OAuth1RequestToken($token, ***)"

/** The result of the OAuth 1.0a login: the user's access token, and who they
  * are.
  */
final case class OAuth1AccessToken(
    accessToken: String,
    accessTokenSecret: String,
    userId: Option[String],
    screenName: Option[String]):

  /** Ready for `Credentials.oauth1(...)` / `Credentials.withOAuth1(...)`. */
  def credentials(
      consumerKey: String,
      consumerSecret: String,
    ): OAuth1Credentials =
    OAuth1Credentials(
      consumerKey,
      consumerSecret,
      accessToken,
      accessTokenSecret,
    )

  override def toString: String =
    s"OAuth1AccessToken(***, ***, userId=$userId, screenName=$screenName)"

/** The OAuth 1.0a 3-legged login ("Log in with X"): the port of X's TypeScript
  * XDK `OAuth1`.
  *
  * {{{
  * val flow = OAuth1Flow(consumerKey, consumerSecret)
  *
  * // 1. a request token, then send the user to X
  * val requestToken = flow.requestToken("https://example.com/callback") // or "oob" for a PIN
  * val url = requestToken.map(flow.authorizationUrl(_))
  *
  * // 2. X redirects back with ?oauth_token=...&oauth_verifier=...
  * //    (or shows the user a PIN, the verifier, for "oob")
  * val access = flow.accessToken(requestToken, verifier)
  *
  * // 3. sign requests as that user
  * access.map(a => Scalaxcom(Credentials.oauth1(consumerKey, consumerSecret, a.accessToken, a.accessTokenSecret)))
  * }}}
  */
final class OAuth1Flow private (
    consumerKey: String,
    consumerSecret: String,
    http: AuthHttp,
    clock: Clock,
    random: Random,
  )(using ExecutionContext)
    extends AutoCloseable:

  /** Step 1: a temporary request token. `callback` is where X sends the user
    * back (it must be registered for the app), or `oob` for the PIN flow.
    */
  def requestToken(callback: String): Future[OAuth1RequestToken] =
    signedPost(
      OAuth1Flow.RequestTokenUrl,
      token = None,
      protocolParams = Seq("oauth_callback" -> callback),
    ).flatMap(params =>
      (params.get("oauth_token"), params.get("oauth_token_secret")) match
        case (Some(token), Some(secret))
            if params.get("oauth_callback_confirmed").forall(_ == "true") =>
          Future.successful(OAuth1RequestToken(token, secret))
        case _ => unexpected(params),
    )

  /** Step 2: where to send the user to approve the app. `authenticate` (Sign in
    * with X) skips the approval screen for users who already approved it.
    */
  def authorizationUrl(
      requestToken: OAuth1RequestToken,
      authenticate: Boolean = false,
    ): String =
    val base =
      if authenticate then OAuth1Flow.AuthenticateUrl
      else OAuth1Flow.AuthorizeUrl
    uri"$base?oauth_token=${requestToken.token}".toString

  /** Step 3: trades the request token and the `oauth_verifier` from the
    * redirect (or the PIN) for the user's access token.
    */
  def accessToken(
      requestToken: OAuth1RequestToken,
      verifier: String,
    ): Future[OAuth1AccessToken] =
    signedPost(
      OAuth1Flow.AccessTokenUrl,
      token = Some(requestToken.token -> requestToken.tokenSecret),
      protocolParams = Seq("oauth_verifier" -> verifier),
    ).flatMap(params =>
      (params.get("oauth_token"), params.get("oauth_token_secret")) match
        case (Some(token), Some(secret)) =>
          Future.successful(OAuth1AccessToken(
            token,
            secret,
            params.get("user_id"),
            params.get("screen_name"),
          ))
        case _ => unexpected(params),
    )

  /** Revokes a user's access token. */
  def invalidate(credentials: OAuth1Credentials): Future[Unit] =
    signedPost(
      OAuth1Flow.InvalidateUrl,
      token = Some(credentials.accessToken -> credentials.accessTokenSecret),
      protocolParams = Nil,
    ).map(_ => ())

  private def signedPost(
      url: String,
      token: Option[(String, String)],
      protocolParams: Seq[(String, String)],
    ): Future[Map[String, String]] =
    val header = OAuth1.authorizationHeader(
      consumerKey = consumerKey,
      consumerSecret = consumerSecret,
      token = token,
      method = "POST",
      url = url,
      formParams = Nil,
      protocolParams = protocolParams,
      nonce = random.alphanumeric.take(32).mkString,
      timestamp = clock.instant().getEpochSecond,
    )
    http
      .postForm(uri"$url", Nil, Seq(Header(HeaderNames.Authorization, header)))
      .map(body => OAuth1.parseForm(body.trim).toMap)

  private def unexpected[T](params: Map[String, String]): Future[T] =
    Future.failed(ScalaxcomClientException(
      s"Unexpected OAuth 1.0a response, fields: ${params.keys.toSeq.sorted.mkString(", ")}",
    ))

  override def close(): Unit = http.close()

object OAuth1Flow:

  private val RequestTokenUrl = "https://api.x.com/oauth/request_token"
  private val AuthorizeUrl = "https://api.x.com/oauth/authorize"
  private val AuthenticateUrl = "https://api.x.com/oauth/authenticate"
  private val AccessTokenUrl = "https://api.x.com/oauth/access_token"
  private val InvalidateUrl = "https://api.x.com/1.1/oauth/invalidate_token"

  /** @param consumerKey
    *   the app's "API Key" in the developer portal.
    */
  def apply(
      consumerKey: String,
      consumerSecret: String,
      timeout: FiniteDuration = 30.seconds,
    )(using ExecutionContext,
    ): OAuth1Flow =
    new OAuth1Flow(
      consumerKey,
      consumerSecret,
      AuthHttp(timeout),
      Clock.systemUTC(),
      Random(),
    )

  private[scalaxcom] def withHttp(
      http: AuthHttp,
      consumerKey: String,
      consumerSecret: String,
      clock: Clock,
    )(using ExecutionContext,
    ): OAuth1Flow =
    new OAuth1Flow(consumerKey, consumerSecret, http, clock, Random(0))
