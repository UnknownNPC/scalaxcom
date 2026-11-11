package com.github.unknownnpc.scalaxcom.client

import java.net.http.HttpClient
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Random
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import com.github.unknownnpc.scalaxcom.auth.Credentials
import com.github.unknownnpc.scalaxcom.auth.OAuth1
import com.github.unknownnpc.scalaxcom.auth.SecurityScheme
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomClientException
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomDecodingException
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomException
import com.github.unknownnpc.scalaxcom.exception.ScalaxcomTimeoutException
import com.github.unknownnpc.scalaxcom.exception.UnauthorizedException
import com.github.unknownnpc.scalaxcom.generated.core.JsonSupport.SecurityTag
import com.github.unknownnpc.scalaxcom.logging.Logging

import sttp.client3.DefaultReadTimeout
import sttp.client3.DeserializationException
import sttp.client3.HttpClientFutureBackend
import sttp.client3.HttpError
import sttp.client3.Request
import sttp.client3.Response
import sttp.client3.ResponseException
import sttp.client3.StringBody
import sttp.client3.SttpBackend
import sttp.client3.SttpBackendOptions
import sttp.model.HeaderNames
import sttp.model.MediaType
import sttp.model.Uri

/** Runs requests built by any generated `*Api` object (see [[Requests]])
  * against the X API. Every generated method builds a request with a relative
  * URI and the list of security schemes its endpoint accepts; this client
  * attaches `baseUrl`, picks one of those schemes it holds [[Credentials]] for
  * and authenticates the request with it, then sends it — retrying per its
  * [[RetryPolicy]].
  *
  * {{{
  * val x = Scalaxcom(Credentials.bearerToken("..."))
  *
  * // async
  * x.execute(Requests.users.getUsersByUsername("XDevelopers"))
  *
  * // blocking
  * x.executeSync(Requests.posts.getPostsById("20", postFields = Set("created_at"))) match
  *   case Right(response) => println(response.data)
  *   case Left(error)     => println(s"failed: ${error.getMessage}")
  * }}}
  *
  * `timeout` bounds each attempt: it is the read timeout of every request sent
  * (unless the request sets its own, see [[execute]]) and the connect timeout.
  * With retries one call can take several attempts plus the waits between them;
  * [[maxWait]] is the most it can take in total, and how long [[executeSync]]
  * waits.
  */
final class Scalaxcom private (
    baseUrl: Uri,
    credentials: Credentials,
    timeout: FiniteDuration,
    retryPolicy: RetryPolicy,
    backend: SttpBackend[Future, Any],
    clock: Clock,
    random: Random,
  )(using ExecutionContext)
    extends Logging
    with AutoCloseable:

  /** The longest one [[execute]] can take: every attempt and every wait in
    * between. [[executeSync]] waits this long.
    */
  val maxWait: FiniteDuration = retryPolicy.maxTotalTime(timeout)

  // Runs the waits between retries; daemon so it never keeps the JVM alive.
  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable =>
      val thread = Thread(runnable, "scalaxcom-retry")
      thread.setDaemon(true)
      thread
    }

  /** Sends `request` to the X API, retrying per the client's [[RetryPolicy]].
    * Each attempt's read timeout is the client's `timeout`, unless the request
    * was given one of its own with `.readTimeout(...)`, which is kept.
    *
    * Always fails with a [[ScalaxcomException]]: HTTP errors are mapped by
    * status code (`RateLimitExceededException`, `NotFoundException`, ...),
    * anything else is wrapped.
    */
  def execute[T](
      request: Request[Either[ResponseException[String, Exception], T], Any],
    ): Future[T] =
    val prepared = withDefaults(request)
    val accepted = request.tag(SecurityTag) match
      case Some(schemes: Seq[?]) => schemes.collect { case s: String => s }
      case _ => Nil
    SecurityScheme.select(
      request.method,
      accepted,
      credentials.available,
    ) match
      case Left(error) => Future.failed(error)
      case Right(scheme) => attempt(prepared, scheme, retry = 0)

  /** Blocking [[execute]], for scripts and synchronous code. Waits up to
    * [[maxWait]]; exceptions come back as values.
    */
  def executeSync[T](
      request: Request[Either[ResponseException[String, Exception], T], Any],
    ): Either[ScalaxcomException, T] =
    try Right(Await.result(execute(request), maxWait))
    catch
      case e: ScalaxcomException => Left(e)
      case e: java.util.concurrent.TimeoutException =>
        Left(ScalaxcomTimeoutException(maxWait, e))
      case NonFatal(t) =>
        Left(ScalaxcomClientException("Unexpected failure", t))

  private def attempt[T](
      request: Request[Either[ResponseException[String, Exception], T], Any],
      scheme: Option[SecurityScheme],
      retry: Int,
      refreshed: Boolean = false,
    ): Future[T] =
    // Authenticated afresh on every attempt: an OAuth 1.0a signature carries
    // a nonce and a timestamp, and X rejects a replayed one; an OAuth 2.0
    // token may have been refreshed since.
    authenticate(request, scheme)
      .flatMap((authed, usedToken) =>
        authed.send(backend).map(response => (response, usedToken)),
      )
      .transform {
        case Success((response, usedToken)) =>
          Success((outcome(Success(response)), usedToken))
        case Failure(t) => Success((outcome(Failure(t)), None))
      }
      .flatMap {
        case (Right(value), _) => Future.successful(value)
        // An OAuth 2.0 user token X no longer accepts: refresh it, once.
        case (Left(e: UnauthorizedException), Some(usedToken)) if !refreshed =>
          credentials.oauth2User.flatMap(_.afterRejection(usedToken)) match
            case Some(replacement) =>
              logger.info("OAuth 2.0 user token rejected (401); refreshing it")
              replacement.transformWith {
                case Success(_) =>
                  attempt(request, scheme, retry, refreshed = true)
                case Failure(refreshError) =>
                  logger.warn(
                    s"Token refresh failed: ${refreshError.getMessage}",
                  )
                  Future.failed(e)
              }
            case None => Future.failed(e)
        case (Left(failure), _) => retryPolicy.delayBefore(
            retry,
            request.method,
            failure,
            clock.instant(),
            random.nextDouble(),
          ) match
            case Some(delay) =>
              logger.warn(
                s"${request.method} ${request.uri.path.mkString("/", "/", "")} failed " +
                  s"(${failure.getMessage}); retry ${retry + 1} of " +
                  s"${retryPolicy.maxRetries} in ${delay.toMillis} ms",
              )
              after(delay).flatMap(_ =>
                attempt(request, scheme, retry + 1, refreshed),
              )
            case None => Future.failed(failure)
      }

  private def outcome[T](
      result: Try[Response[Either[ResponseException[String, Exception], T]]],
    ): Either[ScalaxcomException, T] =
    result match
      case Success(response) => response.body match
          case Right(value) => Right(value)
          case Left(HttpError(body, code)) => Left(
              ScalaxcomException.fromStatusCode(code, body, response.headers),
            )
          case Left(DeserializationException(body, error)) =>
            Left(ScalaxcomDecodingException(body, error))
      case Failure(e: ScalaxcomException) => Left(e)
      case Failure(t) => Left(ScalaxcomClientException(
          s"Request failed: ${Option(t.getMessage).getOrElse(t.toString)}",
          t,
        ))

  // The authenticated request, and the OAuth 2.0 user token it carries (to
  // know which token a 401 rejected).
  private def authenticate[T](
      request: Request[T, Any],
      scheme: Option[SecurityScheme],
    ): Future[(Request[T, Any], Option[String])] =
    // An Authorization header set by the caller wins.
    if request.header(HeaderNames.Authorization).isDefined then
      Future.successful((request, None))
    else
      scheme match
        case Some(SecurityScheme.BearerToken) =>
          Future.successful(
            (
              credentials.bearerToken.fold(request)(request.auth.bearer(_)),
              None,
            ),
          )
        case Some(SecurityScheme.OAuth2UserToken) =>
          credentials.oauth2User match
            case Some(source) => source.accessToken().map(token =>
                (request.auth.bearer(token), Some(token)),
              )
            case None => Future.successful((request, None))
        case Some(SecurityScheme.UserToken) =>
          Future.fromTry(Try {
            val signed = credentials.oauth1.fold(request)(oauth1 =>
              request.header(
                HeaderNames.Authorization,
                OAuth1.authorizationHeader(
                  oauth1,
                  request.method.method,
                  request.uri.toString,
                  formParams(request),
                  nonce = random.alphanumeric.take(32).mkString,
                  timestamp = clock.instant().getEpochSecond,
                ),
              ),
            )
            (signed, None)
          })
        case None => Future.successful((request, None))

  // An application/x-www-form-urlencoded body's parameters are signed too.
  private def formParams(request: Request[?, Any]): Seq[(String, String)] =
    val isForm = request.header(HeaderNames.ContentType).exists(
      _.toLowerCase.startsWith(MediaType.ApplicationXWwwFormUrlencoded.toString),
    )
    request.body match
      case StringBody(content, _, _) if isForm => OAuth1.parseForm(content)
      case _ => Nil

  // sttp gives every request a 1-minute read timeout; a request still
  // carrying that default gets the client's timeout instead.
  private def withDefaults[T](request: Request[T, Any]): Request[T, Any] =
    val timed =
      if request.options.readTimeout == DefaultReadTimeout then
        request.readTimeout(timeout)
      else request
    val withAgent =
      if timed.header(HeaderNames.UserAgent).isDefined then timed
      else timed.header(HeaderNames.UserAgent, Scalaxcom.UserAgent)
    withAgent.copy(uri = baseUrl.resolve(request.uri))

  private def after(delay: FiniteDuration): Future[Unit] =
    val promise = Promise[Unit]()
    try
      scheduler.schedule(
        (() => promise.success(())): Runnable,
        delay.toNanos,
        TimeUnit.NANOSECONDS,
      )
    catch
      case NonFatal(e) => promise.failure(
          ScalaxcomClientException("Client is closed", e),
        )
    promise.future

  private val shutdownHook: Thread = sys.addShutdownHook(
    try backend.close()
    catch
      case e: Throwable =>
        logger.error(s"Failed to auto-close backend: ${e.getMessage}", e),
  )

  override def close(): Unit =
    try Runtime.getRuntime.removeShutdownHook(shutdownHook)
    catch case _: IllegalStateException => ()
    scheduler.shutdownNow()
    backend.close()

object Scalaxcom:

  val DefaultBaseUrl: String = "https://api.x.com"

  /** Same as the X TypeScript XDK: 30 seconds per attempt. */
  val DefaultTimeout: FiniteDuration = 30.seconds

  private val UserAgent = "scalaxcom"

  def apply(
      credentials: Credentials,
      timeout: FiniteDuration = DefaultTimeout,
      retryPolicy: RetryPolicy = RetryPolicy.default,
      baseUrl: String = DefaultBaseUrl,
    )(using ExecutionContext,
    ): Scalaxcom =
    val backend = HttpClientFutureBackend(options =
      SttpBackendOptions.connectionTimeout(timeout),
    )
    new Scalaxcom(
      Uri.unsafeParse(baseUrl),
      credentials,
      timeout,
      retryPolicy,
      backend,
      Clock.systemUTC(),
      Random(),
    )

  /** Same as the default `apply`, but requests go through the given JDK
    * `HttpClient` (HTTP version, proxy, executor, `SSLContext`, its own connect
    * timeout). The caller owns `httpClient`: closing the returned client does
    * not shut it down.
    */
  def apply(
      credentials: Credentials,
      timeout: FiniteDuration,
      retryPolicy: RetryPolicy,
      baseUrl: String,
      httpClient: HttpClient,
    )(using ExecutionContext,
    ): Scalaxcom =
    new Scalaxcom(
      Uri.unsafeParse(baseUrl),
      credentials,
      timeout,
      retryPolicy,
      HttpClientFutureBackend.usingClient(httpClient),
      Clock.systemUTC(),
      Random(),
    )

  /** Test-only escape hatch for a stub backend, a fixed clock and seeded
    * randomness.
    */
  private[client] def withBackend(
      backend: SttpBackend[Future, Any],
      credentials: Credentials,
      timeout: FiniteDuration = DefaultTimeout,
      retryPolicy: RetryPolicy = RetryPolicy.default,
      baseUrl: String = DefaultBaseUrl,
      clock: Clock = Clock.systemUTC(),
      random: Random = Random(0),
    )(using ExecutionContext,
    ): Scalaxcom =
    new Scalaxcom(
      Uri.unsafeParse(baseUrl),
      credentials,
      timeout,
      retryPolicy,
      backend,
      clock,
      random,
    )
