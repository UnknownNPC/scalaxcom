package com.github.unknownnpc.scalaxcom.auth

import java.time.Clock
import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import io.circe.parser.parse

/** An OAuth 2.0 token as X issues it.
  *
  * @param expiresAt
  *   when `accessToken` stops working (X user tokens last 2 hours); `None` if
  *   unknown.
  * @param refreshToken
  *   present when the `offline.access` scope was granted. X rotates it: each
  *   refresh returns a new one and invalidates the old, so always store the
  *   latest (see [[OAuth2Session]]'s `onRefresh`).
  */
final case class OAuth2Token(
    accessToken: String,
    tokenType: String = "bearer",
    expiresAt: Option[Instant] = None,
    refreshToken: Option[String] = None,
    scopes: Set[String] = Set.empty):

  /** Whether the token has expired, or will within `margin`. */
  def expiresWithin(margin: FiniteDuration, now: Instant): Boolean =
    expiresAt.exists(_.minusNanos(margin.toNanos).compareTo(now) <= 0)

  override def toString: String =
    s"OAuth2Token(accessToken=***, tokenType=$tokenType, expiresAt=$expiresAt, " +
      s"refreshToken=${refreshToken.map(_ => "***")}, scopes=${scopes.toSeq.sorted.mkString(" ")})"

object OAuth2Token:

  /** Reads X's token endpoint answer:
    * `{"token_type", "access_token", "expires_in", "refresh_token", "scope"}`.
    */
  private[auth] def fromJson(
      body: String,
      clock: Clock,
    ): Either[String, OAuth2Token] =
    for
      json <- parse(body).left.map(_.getMessage)
      c = json.hcursor
      access <- c.get[String]("access_token").left.map(_ =>
        s"no access_token in $body",
      )
    yield OAuth2Token(
      accessToken = access,
      tokenType = c.get[String]("token_type").getOrElse("bearer"),
      expiresAt = c.get[Long]("expires_in").toOption.map(seconds =>
        clock.instant().plusSeconds(seconds),
      ),
      refreshToken = c.get[String]("refresh_token").toOption,
      scopes = c.get[String]("scope").toOption
        .map(_.split(' ').filter(_.nonEmpty).toSet)
        .getOrElse(Set.empty),
    )
