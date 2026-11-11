package com.github.unknownnpc.scalaxcom.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.util.Base64

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import com.github.unknownnpc.scalaxcom.exception.ScalaxcomClientException

import sttp.client3.UriContext
import sttp.model.Header
import sttp.model.HeaderNames

/** OAuth 2.0 for X: the Authorization Code flow with PKCE (user context), token
  * refresh and revocation. The port of X's TypeScript XDK `OAuth2`.
  *
  * {{{
  * val flow = OAuth2Flow("client-id", "https://example.com/callback")
  *
  * // 1. send the user to X
  * val pkce = Pkce.generate()
  * val state = java.util.UUID.randomUUID.toString
  * val url = flow.authorizationUrl(
  *   Set("tweet.read", "tweet.write", "users.read", "offline.access"),
  *   state,
  *   pkce,
  * )
  *
  * // 2. X redirects back to the callback with ?state=...&code=...;
  * //    check the state, then
  * val token: Future[OAuth2Token] = flow.exchangeCode(code, pkce)
  *
  * // 3. use it, refreshed automatically when it expires
  * token.map(t => Scalaxcom(Credentials.oauth2User(OAuth2Session(flow, t))))
  * }}}
  *
  * `clientSecret` is for confidential clients (the "Web App" type in the
  * developer portal): it authenticates the token calls with HTTP Basic. Without
  * it the client is public, and sends `client_id` instead.
  */
final class OAuth2Flow private (
    val clientId: String,
    val redirectUri: String,
    clientSecret: Option[String],
    http: AuthHttp,
    clock: Clock,
  )(using ExecutionContext)
    extends AutoCloseable:

  /** Where to send the user to approve the app. `state` comes back unchanged on
    * the redirect: check it, it's the CSRF protection.
    *
    * @param scopes
    *   e.g. `tweet.read`, `users.read`, `tweet.write`; add `offline.access` to
    *   get a refresh token.
    */
  def authorizationUrl(scopes: Set[String], state: String, pkce: Pkce): String =
    uri"${OAuth2Flow.AuthorizeUrl}"
      .addParams(
        "response_type" -> "code",
        "client_id" -> clientId,
        "redirect_uri" -> redirectUri,
        "scope" -> scopes.toSeq.sorted.mkString(" "),
        "state" -> state,
        "code_challenge" -> pkce.challenge,
        "code_challenge_method" -> pkce.method,
      )
      .toString

  /** Trades the `code` from the redirect for a token. A code works once, and
    * only for about 30 seconds.
    */
  def exchangeCode(code: String, pkce: Pkce): Future[OAuth2Token] =
    tokenRequest(Seq(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "redirect_uri" -> redirectUri,
      "code_verifier" -> pkce.verifier,
    ))

  /** A new token from a refresh token. X rotates refresh tokens: the result
    * carries a new one and the one passed in stops working.
    */
  def refresh(refreshToken: String): Future[OAuth2Token] =
    tokenRequest(Seq(
      "grant_type" -> "refresh_token",
      "refresh_token" -> refreshToken,
    )).map(token =>
      // Keep using the old refresh token if X didn't send a new one.
      token.copy(refreshToken = token.refreshToken.orElse(Some(refreshToken))),
    )

  /** Revokes an access or refresh token. */
  def revoke(token: String, isRefreshToken: Boolean = false): Future[Unit] =
    http.postForm(
      uri"${OAuth2Flow.RevokeUrl}",
      clientAuthParams :+ "token" -> token :+
        "token_type_hint" ->
        (if isRefreshToken then "refresh_token" else "access_token"),
      clientAuthHeader.toSeq,
    ).map(_ => ())

  private def tokenRequest(params: Seq[(String, String)]): Future[OAuth2Token] =
    http
      .postForm(
        uri"${OAuth2Flow.TokenUrl}",
        params ++ clientAuthParams,
        clientAuthHeader.toSeq,
      )
      .flatMap(body =>
        OAuth2Token.fromJson(body, clock) match
          case Right(token) => Future.successful(token)
          case Left(error) => Future.failed(ScalaxcomClientException(
              s"Unexpected token response: $error",
            )),
      )

  // Confidential clients authenticate with Basic, public ones name themselves.
  private def clientAuthHeader: Option[Header] =
    clientSecret.map(secret =>
      Header(
        HeaderNames.Authorization,
        "Basic " + OAuth2Flow.base64(s"$clientId:$secret"),
      ),
    )

  private def clientAuthParams: Seq[(String, String)] =
    if clientSecret.isDefined then Nil else Seq("client_id" -> clientId)

  override def close(): Unit = http.close()

object OAuth2Flow:

  private val AuthorizeUrl = "https://x.com/i/oauth2/authorize"
  private val TokenUrl = "https://api.x.com/2/oauth2/token"
  private val RevokeUrl = "https://api.x.com/2/oauth2/revoke"
  private val AppOnlyTokenUrl = "https://api.x.com/oauth2/token"

  def apply(
      clientId: String,
      redirectUri: String,
      clientSecret: Option[String] = None,
      timeout: FiniteDuration = 30.seconds,
    )(using ExecutionContext,
    ): OAuth2Flow =
    new OAuth2Flow(
      clientId,
      redirectUri,
      clientSecret,
      AuthHttp(timeout),
      Clock.systemUTC(),
    )

  /** An app-only bearer token (OAuth 2.0 Client Credentials), from the app's
    * consumer key and secret ("API Key and Secret" in the developer portal).
    * Use it with `Credentials.bearerToken`.
    */
  def appOnlyBearerToken(
      consumerKey: String,
      consumerSecret: String,
      timeout: FiniteDuration = 30.seconds,
    )(using ExecutionContext,
    ): Future[String] =
    val http = AuthHttp(timeout)
    val result = appOnlyBearerToken(http, consumerKey, consumerSecret)
    result.onComplete(_ => http.close())
    result

  private[auth] def appOnlyBearerToken(
      http: AuthHttp,
      consumerKey: String,
      consumerSecret: String,
    )(using ExecutionContext,
    ): Future[String] =
    val credentials =
      s"${URLEncoder.encode(consumerKey, UTF_8)}:${URLEncoder.encode(consumerSecret, UTF_8)}"
    http
      .postForm(
        uri"$AppOnlyTokenUrl",
        Seq("grant_type" -> "client_credentials"),
        Seq(Header(HeaderNames.Authorization, "Basic " + base64(credentials))),
      )
      .flatMap(body =>
        OAuth2Token.fromJson(body, Clock.systemUTC()) match
          case Right(token) => Future.successful(token.accessToken)
          case Left(error) => Future.failed(ScalaxcomClientException(
              s"Unexpected token response: $error",
            )),
      )

  /** Test-only: a flow over a stub backend and a fixed clock. */
  private[scalaxcom] def withHttp(
      http: AuthHttp,
      clientId: String,
      redirectUri: String,
      clientSecret: Option[String],
      clock: Clock,
    )(using ExecutionContext,
    ): OAuth2Flow =
    new OAuth2Flow(clientId, redirectUri, clientSecret, http, clock)

  private def base64(s: String): String =
    Base64.getEncoder.encodeToString(s.getBytes(UTF_8))
