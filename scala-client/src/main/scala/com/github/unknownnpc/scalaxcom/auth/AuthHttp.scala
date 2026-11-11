package com.github.unknownnpc.scalaxcom.auth

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import com.github.unknownnpc.scalaxcom.exception.ScalaxcomClientException
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomException

import sttp.client3.HttpClientFutureBackend
import sttp.client3.SttpBackend
import sttp.client3.SttpBackendOptions
import sttp.client3.asStringAlways
import sttp.client3.basicRequest
import sttp.model.Header
import sttp.model.Uri

/** The one HTTP call every OAuth step makes: a form POST whose answer is a
  * string (JSON for OAuth 2.0, form-encoded for OAuth 1.0a).
  *
  * Never retried: an authorization code is single-use, and X rotates refresh
  * tokens, so a resend after a lost response would fail anyway (or, worse, burn
  * the new token).
  */
private[scalaxcom] final class AuthHttp(
    backend: SttpBackend[Future, Any],
    timeout: FiniteDuration,
  )(using ExecutionContext)
    extends AutoCloseable:

  /** POSTs `form` to `uri`. A non-2xx answer fails with the matching
    * `ScalaxcomApiException`, anything else with `ScalaxcomClientException`.
    */
  def postForm(
      uri: Uri,
      form: Seq[(String, String)],
      headers: Seq[Header] = Nil,
    ): Future[String] =
    basicRequest
      .post(uri)
      .headers(headers*)
      .body(form*)
      .readTimeout(timeout)
      .response(asStringAlways)
      .send(backend)
      .recoverWith {
        case e: ScalaxcomException => Future.failed(e)
        case t => Future.failed(ScalaxcomClientException(
            s"OAuth request to $uri failed: ${t.getMessage}",
            t,
          ))
      }
      .flatMap(response =>
        if response.isSuccess then Future.successful(response.body)
        else
          Future.failed(ScalaxcomException.fromStatusCode(
            response.code,
            response.body,
            response.headers,
          )),
      )

  override def close(): Unit = backend.close()

private[scalaxcom] object AuthHttp:

  def apply(timeout: FiniteDuration)(using ExecutionContext): AuthHttp =
    new AuthHttp(
      HttpClientFutureBackend(options =
        SttpBackendOptions.connectionTimeout(timeout),
      ),
      timeout,
    )
