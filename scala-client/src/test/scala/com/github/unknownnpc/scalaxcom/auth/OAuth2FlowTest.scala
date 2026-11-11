package com.github.unknownnpc.scalaxcom.auth

import java.util.Base64

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.auth.StubHttp.*
import com.github.unknownnpc.scalaxcom.exception.BadRequestException

import sttp.model.Uri

class OAuth2FlowTest extends AnyFlatSpec with Matchers {

  private given ExecutionContext = ExecutionContext.global
  private def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  private val tokenJson =
    """{"token_type":"bearer","expires_in":7200,"access_token":"new-access",
      |"scope":"tweet.read users.read offline.access","refresh_token":"new-refresh"}""".stripMargin
  private val pkce =
    Pkce.fromVerifier("dBjftJeZ4CVP-mJ92K9qcbH5yuJ-ksuL6M4Jd1Fq3Sc")

  private def flow(stub: StubHttp, secret: Option[String] = None) =
    OAuth2Flow.withHttp(
      stub.http,
      "client-id",
      "https://example.com/cb",
      secret,
      clock,
    )

  "authorizationUrl" should "send the user to X with PKCE and the state" in {
    val url = Uri.unsafeParse(flow(StubHttp(ok(""))).authorizationUrl(
      Set("users.read", "tweet.read", "offline.access"),
      "state-123",
      pkce,
    ))
    url.host shouldBe Some("x.com")
    url.path shouldBe Seq("i", "oauth2", "authorize")
    url.params.toMap shouldBe Map(
      "response_type" -> "code",
      "client_id" -> "client-id",
      "redirect_uri" -> "https://example.com/cb",
      "scope" -> "offline.access tweet.read users.read",
      "state" -> "state-123",
      "code_challenge" -> pkce.challenge,
      "code_challenge_method" -> "S256",
    )
  }

  "exchangeCode" should "post the code and verifier, as a public client" in {
    val stub = StubHttp(ok(tokenJson))
    val token = await(flow(stub).exchangeCode("the-code", pkce))

    stub.requests.head.uri.toString shouldBe "https://api.x.com/2/oauth2/token"
    stub.requests.head.header("Authorization") shouldBe None
    stub.form() shouldBe Map(
      "grant_type" -> "authorization_code",
      "code" -> "the-code",
      "redirect_uri" -> "https://example.com/cb",
      "code_verifier" -> pkce.verifier,
      "client_id" -> "client-id",
    )
    token shouldBe OAuth2Token(
      accessToken = "new-access",
      tokenType = "bearer",
      expiresAt = Some(now.plusSeconds(7200)),
      refreshToken = Some("new-refresh"),
      scopes = Set("tweet.read", "users.read", "offline.access"),
    )
  }

  it should "authenticate a confidential client with HTTP Basic" in {
    val stub = StubHttp(ok(tokenJson))
    await(flow(stub, Some("s3cret")).exchangeCode("the-code", pkce))

    val expected =
      Base64.getEncoder.encodeToString("client-id:s3cret".getBytes("UTF-8"))
    stub.requests.head.header("Authorization") shouldBe Some(s"Basic $expected")
    stub.form().get("client_id") shouldBe None
  }

  it should "fail with X's OAuth error description" in {
    val stub = StubHttp(status(
      400,
      """{"error":"invalid_request","error_description":"Value passed for the authorization code was invalid."}""",
    ))
    val error = intercept[BadRequestException](
      await(flow(stub).exchangeCode("stale", pkce)),
    )
    error.getMessage should include("authorization code was invalid")
  }

  "refresh" should "trade the refresh token for a new token" in {
    val stub = StubHttp(ok(tokenJson))
    val token = await(flow(stub).refresh("old-refresh"))
    stub.form() shouldBe Map(
      "grant_type" -> "refresh_token",
      "refresh_token" -> "old-refresh",
      "client_id" -> "client-id",
    )
    token.refreshToken shouldBe Some("new-refresh")
  }

  it should "keep the old refresh token when X sends no new one" in {
    val stub = StubHttp(ok("""{"access_token":"a","expires_in":60}"""))
    await(flow(stub).refresh("old-refresh")).refreshToken shouldBe
      Some("old-refresh")
  }

  "revoke" should "post the token with its type" in {
    val stub = StubHttp(ok("""{"revoked":true}"""))
    await(flow(stub).revoke("r", isRefreshToken = true))
    stub.requests.head.uri.toString shouldBe "https://api.x.com/2/oauth2/revoke"
    stub.form() shouldBe Map(
      "token" -> "r",
      "token_type_hint" -> "refresh_token",
      "client_id" -> "client-id",
    )
  }

  "appOnlyBearerToken" should "use client credentials with the consumer key and secret" in {
    val stub = StubHttp(ok("""{"token_type":"bearer","access_token":"AAAA"}"""))
    await(OAuth2Flow.appOnlyBearerToken(stub.http, "key", "secret")) shouldBe
      "AAAA"
    stub.requests.head.uri.toString shouldBe "https://api.x.com/oauth2/token"
    stub.form() shouldBe Map("grant_type" -> "client_credentials")
    stub.requests.head.header("Authorization") shouldBe
      Some("Basic " + Base64.getEncoder.encodeToString("key:secret".getBytes))
  }

  "OAuth2Token" should "not print its secrets" in {
    val token =
      OAuth2Token("access-secret", refreshToken = Some("refresh-secret"))
    (token.toString should not).include("secret")
  }
}
