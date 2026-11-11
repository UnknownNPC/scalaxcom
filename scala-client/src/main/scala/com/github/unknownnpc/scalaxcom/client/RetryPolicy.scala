package com.github.unknownnpc.scalaxcom.client

import java.time.Instant

import scala.concurrent.duration.*

import com.github.unknownnpc.scalaxcom.exception.RateLimitExceededException
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomClientException
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomException
import com.github.unknownnpc.scalaxcom.exception.ServerErrorException

import sttp.client3.SttpClientException
import sttp.model.Method

/** When and how long to wait before sending a failed request again.
  *
  * A request is retried, up to `maxRetries` times, when:
  *   - X rate-limited it (429). Nothing was done, so any request is retried,
  *     after the wait the server asks for (`Retry-After`, or until
  *     `x-rate-limit-reset`) — unless that is longer than `maxRateLimitWait`:
  *     then it fails straight away with a `RateLimitExceededException` whose
  *     `rateLimit.reset` says when to come back.
  *   - the connection could not be made. Nothing was sent, so any request is
  *     retried.
  *   - X answered 500, 502, 503 or 504, or the request timed out or the
  *     connection broke mid-request. The request may have been carried out, so
  *     only idempotent ones (GET, HEAD, OPTIONS, PUT, DELETE) are retried,
  *     unless `retryNonIdempotent` is set: retrying a `createPosts` that
  *     actually went through posts twice.
  *
  * Waits between attempts grow exponentially from `initialDelay` by
  * `multiplier`, capped at `maxDelay`, with jitter (each wait is 50–100% of
  * that) so that many clients failing together don't retry together.
  */
final case class RetryPolicy(
    maxRetries: Int = 3,
    initialDelay: FiniteDuration = 1.second,
    maxDelay: FiniteDuration = 30.seconds,
    multiplier: Double = 2.0,
    maxRateLimitWait: FiniteDuration = 1.minute,
    retryNonIdempotent: Boolean = false):

  require(maxRetries >= 0, "maxRetries must be >= 0")
  require(initialDelay >= Duration.Zero, "initialDelay must be >= 0")
  require(maxDelay >= initialDelay, "maxDelay must be >= initialDelay")
  require(multiplier >= 1.0, "multiplier must be >= 1")
  require(maxRateLimitWait >= Duration.Zero, "maxRateLimitWait must be >= 0")

  /** The wait before retry number `retry + 1` (0-based `retry`), given a
    * uniformly random `jitter` in [0, 1).
    */
  def backoff(retry: Int, jitter: Double): FiniteDuration =
    val exponential = initialDelay.toNanos * math.pow(multiplier, retry)
    val capped = math.min(exponential, maxDelay.toNanos.toDouble)
    (capped * (0.5 + jitter / 2)).toLong.nanos

  /** How long to wait before retrying after `failure`, or `None` to give up.
    *
    * @param retry
    *   how many retries were already made for this request.
    */
  def delayBefore(
      retry: Int,
      method: Method,
      failure: ScalaxcomException,
      now: Instant,
      jitter: Double,
    ): Option[FiniteDuration] =
    def idempotentOrAllowed =
      retryNonIdempotent || RetryPolicy.IdempotentMethods.contains(method)
    if retry >= maxRetries then None
    else
      failure match
        case e: RateLimitExceededException =>
          e.retryAfter(now) match
            case Some(wait) if wait > maxRateLimitWait => None
            case Some(wait) => Some(wait)
            case None => Some(backoff(retry, jitter))
        case e: ServerErrorException
            if RetryPolicy.RetryableStatuses.contains(e.code) &&
              idempotentOrAllowed =>
          Some(backoff(retry, jitter))
        case e: ScalaxcomClientException => e.getCause match
            case _: SttpClientException.ConnectException =>
              Some(backoff(retry, jitter))
            case _: SttpClientException.TimeoutException |
                _: SttpClientException.ReadException if idempotentOrAllowed =>
              Some(backoff(retry, jitter))
            case _ => None
        case _ => None

  /** The longest a request can take, all attempts and waits included, when each
    * attempt is bounded by `timeout`.
    */
  def maxTotalTime(timeout: FiniteDuration): FiniteDuration =
    timeout * (maxRetries + 1).toLong +
      (if maxDelay > maxRateLimitWait then maxDelay
       else maxRateLimitWait) * maxRetries.toLong

object RetryPolicy:

  /** 3 retries, 1s initial backoff doubling up to 30s, rate-limit waits up to a
    * minute, idempotent requests only.
    */
  val default: RetryPolicy = RetryPolicy()

  /** Every request is sent exactly once. */
  val disabled: RetryPolicy = RetryPolicy(maxRetries = 0)

  private val IdempotentMethods =
    Set(Method.GET, Method.HEAD, Method.OPTIONS, Method.PUT, Method.DELETE)

  private val RetryableStatuses = Set(500, 502, 503, 504)
