package com.github.unknownnpc.scalaxcom.auth

import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.auth.StubHttp.*

import sttp.client3.Response
import sttp.model.StatusCode

class OAuth2SessionTest extends AnyFlatSpec with Matchers {

  private given ExecutionContext = ExecutionContext.global
  private def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  private val refreshed =
    """{"access_token":"fresh","expires_in":7200,"refresh_token":"r2"}"""

  private def session(
      stub: StubHttp,
      token: OAuth2Token,
      saved: ConcurrentLinkedQueue[OAuth2Token] = ConcurrentLinkedQueue(),
    ) =
    OAuth2Session.withClock(
      OAuth2Flow.withHttp(stub.http, "id", "https://cb", None, clock),
      token,
      t => {
        saved.add(t)
        ()
      },
      1.minute,
      clock,
    )

  private def expiringIn(d: FiniteDuration) =
    OAuth2Token(
      "old",
      expiresAt = Some(now.plusMillis(d.toMillis)),
      refreshToken = Some("r1"),
    )

  "accessToken" should "use the current token while it's valid" in {
    val stub = StubHttp(ok(refreshed))
    await(session(stub, expiringIn(10.minutes)).accessToken()) shouldBe "old"
    stub.count shouldBe 0
  }

  it should "refresh a token about to expire, and report the new one" in {
    val stub = StubHttp(ok(refreshed))
    val saved = ConcurrentLinkedQueue[OAuth2Token]()
    val s = session(stub, expiringIn(30.seconds), saved)

    await(s.accessToken()) shouldBe "fresh"
    stub.form()("refresh_token") shouldBe "r1"
    s.currentToken.refreshToken shouldBe Some("r2")
    saved.asScala.toList.map(_.accessToken) shouldBe List("fresh")
  }

  it should "share one refresh among concurrent requests" in {
    // X rotates refresh tokens: a second refresh with r1 would fail.
    val answer = Promise[Response[String]]()
    val stub = StubHttp(() => answer.future)
    val s = session(stub, expiringIn(Duration.Zero))

    val tokens = (1 to 10).map(_ => s.accessToken())
    answer.success(Response(refreshed, StatusCode.Ok))

    tokens.map(await) shouldBe Seq.fill(10)("fresh")
    stub.count shouldBe 1
  }

  it should "not try to refresh without a refresh token" in {
    val stub = StubHttp(ok(refreshed))
    val token = OAuth2Token("old", expiresAt = Some(now.minusSeconds(1)))
    await(session(stub, token).accessToken()) shouldBe "old"
    stub.count shouldBe 0
  }

  "afterRejection" should "refresh the rejected token" in {
    val stub = StubHttp(ok(refreshed))
    val s = session(stub, expiringIn(1.hour))
    await(s.afterRejection("old").get) shouldBe "fresh"
  }

  it should "hand over a token refreshed meanwhile without refreshing again" in {
    val stub = StubHttp(ok(refreshed))
    val s = session(stub, expiringIn(1.hour))
    await(s.refresh())
    await(s.afterRejection("old").get) shouldBe "fresh"
    stub.count shouldBe 1
  }

  it should "give up without a refresh token" in {
    val s = session(StubHttp(ok(refreshed)), OAuth2Token("old"))
    s.afterRejection("old") shouldBe None
  }

  "a failed refresh" should "fail the request and allow a later retry" in {
    val stub =
      StubHttp(status(400, """{"error":"invalid_grant"}"""), ok(refreshed))
    val s = session(stub, expiringIn(Duration.Zero))
    an[Exception] should be thrownBy await(s.accessToken())
    await(s.accessToken()) shouldBe "fresh"
  }
}
