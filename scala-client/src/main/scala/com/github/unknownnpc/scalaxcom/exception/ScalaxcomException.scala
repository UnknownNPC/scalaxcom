package com.github.unknownnpc.scalaxcom.exception

import java.time.Instant

import scala.concurrent.duration.*
import scala.util.Try

import io.circe.parser.parse
import sttp.model.Header
import sttp.model.StatusCode

sealed abstract class ScalaxcomException(
    message: String,
    cause: Throwable = null)
    extends RuntimeException(message, cause)

/** The request never produced an HTTP response: connection refused, DNS
  * failure, a read timeout, ... The underlying sttp exception is the cause
  * (e.g. `sttp.client3.SttpClientException.TimeoutException`).
  */
class ScalaxcomClientException(message: String, cause: Throwable = null)
    extends ScalaxcomException(message, cause)

/** `executeSync` stopped waiting; the request may still complete. */
class ScalaxcomTimeoutException(timeout: FiniteDuration, cause: Throwable)
    extends ScalaxcomClientException(
      s"Request did not complete within $timeout",
      cause,
    )

/** The endpoint accepts none of the authentication methods the client holds
  * credentials for. Raised before anything is sent.
  */
class ScalaxcomAuthenticationException(message: String)
    extends ScalaxcomException(message)

/** A successful response whose body did not decode into the expected model. */
class ScalaxcomDecodingException(val body: String, cause: Throwable)
    extends ScalaxcomException(
      s"Could not decode response body: ${cause.getMessage}",
      cause,
    )

/** X's rate limit state, from the `x-rate-limit-*` response headers. */
final case class RateLimit(
    limit: Option[Int],
    remaining: Option[Int],
    reset: Option[Instant])

object RateLimit:

  def fromHeaders(headers: Seq[Header]): Option[RateLimit] =
    def value(name: String) =
      headers.find(_.is(name)).map(_.value.trim)
    val limit = value("x-rate-limit-limit").flatMap(_.toIntOption)
    val remaining = value("x-rate-limit-remaining").flatMap(_.toIntOption)
    val reset = value("x-rate-limit-reset")
      .flatMap(_.toLongOption)
      .map(Instant.ofEpochSecond)
    Option.when(limit.isDefined || remaining.isDefined || reset.isDefined)(
      RateLimit(limit, remaining, reset),
    )

/** X answered with an error status. `body` is the raw response body (usually an
  * RFC 7807 problem, `{"title": ..., "detail": ..., "type": ...}`).
  */
sealed abstract class ScalaxcomApiException(
    val code: Int,
    val body: String,
    val headers: Seq[Header],
    label: String)
    extends ScalaxcomException(
      ScalaxcomApiException.describe(body) match
        case "" => s"$label ($code)"
        case description => s"$label ($code): $description",
    ):

  /** The rate limit state reported alongside this error, if any. */
  def rateLimit: Option[RateLimit] = RateLimit.fromHeaders(headers)

object ScalaxcomApiException:

  // The problem's `detail` (or `title`, the first `errors[].message`, or an
  // OAuth error's `error_description`/`error`), falling back to the raw body.
  // Empty for an empty body: X's 404 for an unknown path has none.
  private[exception] def describe(body: String): String =
    parse(body).toOption
      .flatMap { json =>
        val c = json.hcursor
        c.get[String]("detail").toOption
          .orElse(c.get[String]("title").toOption)
          .orElse(
            c.downField("errors").downArray.get[String]("message").toOption,
          )
          .orElse(c.get[String]("error_description").toOption)
          .orElse(c.get[String]("error").toOption)
      }
      .getOrElse(body.trim)

class BadRequestException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(400, body, headers, "Bad Request")

class UnauthorizedException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(401, body, headers, "Unauthorized")

/** X's pay-per-use API answers 402 once the account has run out of credits. */
class PaymentRequiredException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(402, body, headers, "Payment Required")

class ForbiddenException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(403, body, headers, "Forbidden")

class NotFoundException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(404, body, headers, "Not Found")

class ConflictException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(409, body, headers, "Conflict")

class PayloadTooLargeException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(413, body, headers, "Payload Too Large")

class UnprocessableEntityException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(422, body, headers, "Unprocessable Entity")

class RateLimitExceededException(body: String, headers: Seq[Header])
    extends ScalaxcomApiException(429, body, headers, "Rate Limit Exceeded"):

  /** How long the server asked to wait: `Retry-After` if sent, otherwise the
    * time left until `x-rate-limit-reset`.
    */
  def retryAfter(now: Instant): Option[FiniteDuration] =
    headers.find(_.is("Retry-After"))
      .flatMap(h => Try(h.value.trim.toLong).toOption)
      .map(_.seconds)
      .orElse(
        rateLimit.flatMap(_.reset).map(reset =>
          math.max(0L, reset.toEpochMilli - now.toEpochMilli).millis,
        ),
      )

class ServerErrorException(code: Int, body: String, headers: Seq[Header])
    extends ScalaxcomApiException(code, body, headers, "Server Error")

class UnknownApiException(code: Int, body: String, headers: Seq[Header])
    extends ScalaxcomApiException(code, body, headers, "Unknown API Error")

object ScalaxcomException:

  def fromStatusCode(
      statusCode: StatusCode,
      body: String,
      headers: Seq[Header] = Nil,
    ): ScalaxcomApiException =
    statusCode.code match
      case 400 => BadRequestException(body, headers)
      case 401 => UnauthorizedException(body, headers)
      case 402 => PaymentRequiredException(body, headers)
      case 403 => ForbiddenException(body, headers)
      case 404 => NotFoundException(body, headers)
      case 409 => ConflictException(body, headers)
      case 413 => PayloadTooLargeException(body, headers)
      case 422 => UnprocessableEntityException(body, headers)
      case 429 => RateLimitExceededException(body, headers)
      case c if c >= 500 => ServerErrorException(c, body, headers)
      case c => UnknownApiException(c, body, headers)
