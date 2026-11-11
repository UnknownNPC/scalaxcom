package com.github.unknownnpc.scalaxcom.auth

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.auth.StubHttp.*
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomClientException
import com.github.unknownnpc.scalaxcom.exception.UnauthorizedException

class OAuth1FlowTest extends AnyFlatSpec with Matchers {

  private given ExecutionContext = ExecutionContext.global
  private def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  private def flow(stub: StubHttp) =
    OAuth1Flow.withHttp(stub.http, "ck", "cs", clock)

  // The oauth_* parameters of an `OAuth k="v", ...` header, decoded.
  private def oauthParams(header: String): Map[String, String] =
    header.stripPrefix("OAuth ").split(", ").map { kv =>
      val Array(k, v) = kv.split("=", 2)
      k -> java.net.URLDecoder.decode(
        v.stripPrefix("\"").stripSuffix("\""),
        "UTF-8",
      )
    }.toMap

  // Recomputes the signature from the header's own parameters.
  private def verify(
      header: String,
      url: String,
      tokenSecret: String,
    ): Unit =
    val params = oauthParams(header)
    OAuth1.signature(
      "cs",
      tokenSecret,
      "POST",
      url,
      (params - "oauth_signature").toSeq,
    ) shouldBe params("oauth_signature")

  "requestToken" should "sign with the callback and no token" in {
    val stub = StubHttp(ok(
      "oauth_token=req&oauth_token_secret=req-secret&oauth_callback_confirmed=true",
    ))
    await(flow(stub).requestToken("https://example.com/cb")) shouldBe
      OAuth1RequestToken("req", "req-secret")

    val header = stub.requests.head.header("Authorization").get
    val params = oauthParams(header)
    stub.requests.head.uri.toString shouldBe
      "https://api.x.com/oauth/request_token"
    params("oauth_callback") shouldBe "https://example.com/cb"
    params("oauth_consumer_key") shouldBe "ck"
    params("oauth_timestamp") shouldBe now.getEpochSecond.toString
    params.contains("oauth_token") shouldBe false
    verify(header, "https://api.x.com/oauth/request_token", tokenSecret = "")
  }

  it should "fail if X didn't confirm the callback" in {
    val stub = StubHttp(ok(
      "oauth_token=req&oauth_token_secret=s&oauth_callback_confirmed=false",
    ))
    a[ScalaxcomClientException] should be thrownBy
      await(flow(stub).requestToken("oob"))
  }

  it should "surface X's rejection as a typed error" in {
    val stub = StubHttp(status(
      401,
      """{"errors":[{"code":32,"message":"Could not authenticate you."}]}""",
    ))
    val error = intercept[UnauthorizedException](
      await(flow(stub).requestToken("oob")),
    )
    error.getMessage should include("Could not authenticate you.")
  }

  "authorizationUrl" should "point at authorize, or authenticate for Sign in with X" in {
    val token = OAuth1RequestToken("req token", "s")
    val f = flow(StubHttp(ok("")))
    f.authorizationUrl(token) shouldBe
      "https://api.x.com/oauth/authorize?oauth_token=req+token"
    f.authorizationUrl(token, authenticate = true) shouldBe
      "https://api.x.com/oauth/authenticate?oauth_token=req+token"
  }

  "accessToken" should "sign with the request token and the verifier" in {
    val stub = StubHttp(ok(
      "oauth_token=acc&oauth_token_secret=acc-secret&user_id=6253282&screen_name=XDevelopers",
    ))
    val access = await(
      flow(stub).accessToken(OAuth1RequestToken("req", "req-secret"), "v123"),
    )
    access shouldBe OAuth1AccessToken(
      "acc",
      "acc-secret",
      Some("6253282"),
      Some("XDevelopers"),
    )
    access.credentials("ck", "cs") shouldBe
      OAuth1Credentials("ck", "cs", "acc", "acc-secret")

    val header = stub.requests.head.header("Authorization").get
    oauthParams(header)("oauth_token") shouldBe "req"
    oauthParams(header)("oauth_verifier") shouldBe "v123"
    verify(header, "https://api.x.com/oauth/access_token", "req-secret")
  }

  "invalidate" should "sign with the access token being revoked" in {
    val stub = StubHttp(ok("""{"access_token":"acc"}"""))
    await(flow(stub).invalidate(OAuth1Credentials("ck", "cs", "acc", "acc-s")))
    val header = stub.requests.head.header("Authorization").get
    oauthParams(header)("oauth_token") shouldBe "acc"
    verify(header, "https://api.x.com/1.1/oauth/invalidate_token", "acc-s")
  }

  "OAuth1AccessToken" should "not print its secrets" in
    (OAuth1AccessToken(
      "acc",
      "acc-secret",
      None,
      None,
    ).toString should not).include(
      "acc",
    )
}
