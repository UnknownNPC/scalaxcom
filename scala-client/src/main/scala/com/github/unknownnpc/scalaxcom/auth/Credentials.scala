package com.github.unknownnpc.scalaxcom.auth

/** OAuth 1.0a user-context credentials: the app's consumer key/secret (called
  * "API Key and Secret" in the X developer portal) plus a user's access token
  * and secret.
  */
final case class OAuth1Credentials(
    consumerKey: String,
    consumerSecret: String,
    accessToken: String,
    accessTokenSecret: String):
  override def toString: String =
    s"OAuth1Credentials(consumerKey=$consumerKey, consumerSecret=***, accessToken=***, accessTokenSecret=***)"

/** Everything the client may authenticate with. X endpoints accept different
  * subsets of three schemes (see [[SecurityScheme]]); hold as many as you have,
  * and each request is authenticated with the best one it accepts.
  *
  * {{{
  * Credentials.bearerToken("app-only token")
  * Credentials.oauth2UserToken("user access token")          // fixed token
  * Credentials.oauth2User(OAuth2Session(flow, token))        // refreshed automatically
  * Credentials.oauth1(consumerKey, consumerSecret, accessToken, accessTokenSecret)
  *
  * // reads go out app-only, writes in user context
  * Credentials.bearerToken("...").withOAuth2UserToken("...")
  * }}}
  */
final class Credentials private (
    val bearerToken: Option[String],
    private[scalaxcom] val oauth2User: Option[AccessTokenSource],
    val oauth1: Option[OAuth1Credentials]):

  def withBearerToken(token: String): Credentials =
    new Credentials(Some(token), oauth2User, oauth1)

  /** A fixed OAuth 2.0 user access token. It expires (after 2 hours, for X);
    * use [[withOAuth2User]] to have it refreshed.
    */
  def withOAuth2UserToken(token: String): Credentials =
    new Credentials(bearerToken, Some(AccessTokenSource.Static(token)), oauth1)

  /** An OAuth 2.0 user token kept valid by `session`. */
  def withOAuth2User(session: OAuth2Session): Credentials =
    new Credentials(bearerToken, Some(session), oauth1)

  def withOAuth1(credentials: OAuth1Credentials): Credentials =
    new Credentials(bearerToken, oauth2User, Some(credentials))

  /** The schemes these credentials can satisfy. */
  def available: Set[SecurityScheme] =
    Set(
      bearerToken.map(_ => SecurityScheme.BearerToken),
      oauth2User.map(_ => SecurityScheme.OAuth2UserToken),
      oauth1.map(_ => SecurityScheme.UserToken),
    ).flatten

  // Secrets never end up in logs.
  override def toString: String =
    s"Credentials(${available.toSeq.map(_.toString).sorted.mkString(", ")})"

object Credentials:

  val none: Credentials = new Credentials(None, None, None)

  /** App-only OAuth 2.0 bearer token: public, read-only data. */
  def bearerToken(token: String): Credentials = none.withBearerToken(token)

  /** A fixed OAuth 2.0 user-context access token. */
  def oauth2UserToken(token: String): Credentials =
    none.withOAuth2UserToken(token)

  /** An OAuth 2.0 user-context token, refreshed automatically (see
    * [[OAuth2Flow]] to obtain one).
    */
  def oauth2User(session: OAuth2Session): Credentials =
    none.withOAuth2User(session)

  /** OAuth 1.0a user context. */
  def oauth1(
      consumerKey: String,
      consumerSecret: String,
      accessToken: String,
      accessTokenSecret: String,
    ): Credentials =
    none.withOAuth1(
      OAuth1Credentials(
        consumerKey,
        consumerSecret,
        accessToken,
        accessTokenSecret,
      ),
    )
