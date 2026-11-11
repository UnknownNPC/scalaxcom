package com.github.unknownnpc.scalaxcom.client

import java.time.Instant

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.exception.*

import sttp.client3.SttpClientException
import sttp.client3.UriContext
import sttp.client3.basicRequest
import sttp.model.Header
import sttp.model.Method

class RetryPolicyTest extends AnyFlatSpec with Matchers {

  private val now = Instant.parse("2026-01-01T00:00:00Z")
  private val policy = RetryPolicy(
    maxRetries = 3,
    initialDelay = 1.second,
    maxDelay = 5.seconds,
  )
  private val request = basicRequest.get(uri"https://api.x.com/2/users/me")

  private def serverError(code: Int) = ServerErrorException(code, "", Nil)
  private def rateLimited(headers: Header*) =
    RateLimitExceededException("", headers)

  "backoff" should "grow exponentially, capped at maxDelay, with 50-100% jitter" in {
    policy.backoff(0, jitter = 0.999) shouldBe <=(1.second)
    policy.backoff(0, jitter = 0.0) shouldBe 500.millis
    policy.backoff(1, jitter = 0.0) shouldBe 1.second
    policy.backoff(2, jitter = 0.0) shouldBe 2.seconds
    policy.backoff(10, jitter = 0.0) shouldBe 2500.millis
    policy.backoff(10, jitter = 0.999) shouldBe <=(5.seconds)
  }

  "delayBefore" should "retry idempotent requests on 500/502/503/504" in {
    for code <- Seq(500, 502, 503, 504) do
      policy.delayBefore(0, Method.GET, serverError(code), now, 0) shouldBe
        Some(500.millis)
  }

  it should "not retry other server errors" in {
    policy.delayBefore(0, Method.GET, serverError(501), now, 0) shouldBe None
  }

  it should "not retry a non-idempotent request on a server error unless allowed" in {
    policy.delayBefore(0, Method.POST, serverError(503), now, 0) shouldBe None
    policy.copy(retryNonIdempotent = true)
      .delayBefore(0, Method.POST, serverError(503), now, 0) shouldBe
      Some(500.millis)
  }

  it should "stop after maxRetries" in {
    policy.delayBefore(3, Method.GET, serverError(503), now, 0) shouldBe None
  }

  it should "never retry client errors" in {
    policy.delayBefore(
      0,
      Method.GET,
      NotFoundException("", Nil),
      now,
      0,
    ) shouldBe
      None
    policy.delayBefore(
      0,
      Method.GET,
      UnauthorizedException("", Nil),
      now,
      0,
    ) shouldBe None
  }

  it should "wait out a 429 as the server asks, for any method" in {
    policy.delayBefore(
      0,
      Method.POST,
      rateLimited(Header("Retry-After", "7")),
      now,
      0,
    ) shouldBe Some(7.seconds)
    val reset = now.plusSeconds(20).getEpochSecond.toString
    policy.delayBefore(
      0,
      Method.POST,
      rateLimited(Header("x-rate-limit-reset", reset)),
      now,
      0,
    ) shouldBe Some(20.seconds)
  }

  it should "fall back to backoff on a 429 without timing headers" in {
    policy.delayBefore(0, Method.GET, rateLimited(), now, 0) shouldBe
      Some(500.millis)
  }

  it should "give up on a 429 whose reset is beyond maxRateLimitWait" in {
    val reset = now.plusSeconds(15 * 60).getEpochSecond.toString
    policy.delayBefore(
      0,
      Method.GET,
      rateLimited(Header("x-rate-limit-reset", reset)),
      now,
      0,
    ) shouldBe None
  }

  it should "retry any request that could not connect" in {
    val e = ScalaxcomClientException(
      "",
      SttpClientException.ConnectException(request, RuntimeException()),
    )
    policy.delayBefore(0, Method.POST, e, now, 0) shouldBe Some(500.millis)
  }

  it should "retry a timed-out request only when idempotent" in {
    val e = ScalaxcomClientException(
      "",
      SttpClientException.TimeoutException(request, RuntimeException()),
    )
    policy.delayBefore(0, Method.GET, e, now, 0) shouldBe Some(500.millis)
    policy.delayBefore(0, Method.POST, e, now, 0) shouldBe None
  }

  it should "never retry a response that failed to decode" in {
    val e = ScalaxcomDecodingException("{}", RuntimeException("bad"))
    policy.delayBefore(0, Method.GET, e, now, 0) shouldBe None
  }

  "disabled" should "never retry" in {
    RetryPolicy.disabled.delayBefore(
      0,
      Method.GET,
      serverError(503),
      now,
      0,
    ) shouldBe
      None
  }

  "maxTotalTime" should "cover every attempt and the longest waits" in {
    // 4 attempts x 10s + 3 waits x max(5s, 1 min)
    policy.maxTotalTime(10.seconds) shouldBe 40.seconds + 3.minutes
    RetryPolicy.disabled.maxTotalTime(10.seconds) shouldBe 10.seconds
  }
}
