package com.github.unknownnpc.scalaxcom.client

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.auth
import com.github.unknownnpc.scalaxcom.auth.Credentials
import com.github.unknownnpc.scalaxcom.exception.*
import com.github.unknownnpc.scalaxcom.generated.model.CreatePostsRequest

import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.Header
import sttp.model.StatusCode

// Plain (non-Async) spec: `executeSync` blocks, and doing that inside an
// AsyncFlatSpec body starves the pool the Future needs to complete on.
class ScalaxcomTest extends AnyFlatSpec with Matchers {

  private given ExecutionContext = ExecutionContext.global

  private val bearer = Credentials.bearerToken("app-token")
  private val userJson =
    """{"data":{"id":"2244994945","username":"XDevelopers","name":"Developers"}}"""
  private val postJson = """{"data":{"id":"1","text":"hello"}}"""
  private val fastRetries = RetryPolicy(
    maxRetries = 2,
    initialDelay = 1.milli,
    maxDelay = 5.millis,
  )
  private val fixedClock =
    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

  // A stub answering with `responses` in order (the last one repeats),
  // recording every request it receives.
  private class Recording(responses: Response[String]*) {
    val requests = ConcurrentLinkedQueue[Request[?, ?]]()
    private val next = AtomicInteger(0)
    val backend: SttpBackend[Future, Any] =
      SttpBackendStub.asynchronousFuture.whenAnyRequest.thenRespondF {
        (request: Request[?, ?]) =>
          requests.add(request)
          val i = next.getAndIncrement()
          Future.successful(responses(math.min(i, responses.size - 1)))
      }
    def count: Int = requests.size
    def all: List[Request[?, ?]] = requests.asScala.toList
  }

  private def ok(body: String) = Response(body, StatusCode.Ok)
  private def status(code: Int, headers: Header*) =
    Response(
      """{"title":"oops","detail":"went wrong"}""",
      StatusCode(code),
      "",
      headers,
    )

  private def client(
      backend: SttpBackend[Future, Any],
      credentials: Credentials = bearer,
      timeout: FiniteDuration = 30.seconds,
      retryPolicy: RetryPolicy = fastRetries,
    ) =
    Scalaxcom.withBackend(
      backend,
      credentials,
      timeout,
      retryPolicy,
      clock = fixedClock,
    )

  private def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  "execute" should "decode a successful response" in {
    val stub = Recording(ok(userJson))
    val user =
      await(client(
        stub.backend,
      ).execute(Requests.users.getUsersByUsername("XDevelopers")))
    user.data.flatMap(_.username) shouldBe Some("XDevelopers")
  }

  it should "send to the X API at the request's path, query parameters comma-joined" in {
    val stub = Recording(ok(userJson))
    await(client(stub.backend).execute(
      Requests.users.getUsersByUsername(
        "XDevelopers",
        userFields = Set("created_at", "description"),
      ),
    ))
    stub.all.head.uri.toString shouldBe
      "https://api.x.com/2/users/by/username/XDevelopers?user.fields=created_at,description"
  }

  it should "authenticate with the bearer token where the endpoint accepts it" in {
    val stub = Recording(ok(userJson))
    await(client(stub.backend).execute(Requests.users.getUsersByUsername("x")))
    stub.all.head.header("Authorization") shouldBe Some("Bearer app-token")
    stub.all.head.header("User-Agent") shouldBe Some("scalaxcom")
  }

  it should "use the OAuth 2.0 user token for a user-context endpoint" in {
    val stub = Recording(ok(userJson))
    val credentials = bearer.withOAuth2UserToken("user-token")
    await(client(
      stub.backend,
      credentials,
    ).execute(Requests.users.getUsersMe()))
    stub.all.head.header("Authorization") shouldBe Some("Bearer user-token")
  }

  it should "sign a write with OAuth 1.0a, afresh on every attempt" in {
    val stub = Recording(status(429, Header("Retry-After", "0")), ok(postJson))
    val credentials = bearer.withOAuth1(
      auth.OAuth1Credentials(
        "ck",
        "cs",
        "at",
        "ats",
      ),
    )
    await(client(stub.backend, credentials).execute(
      Requests.posts.createPosts(CreatePostsRequest(text = Some("hello"))),
    ))
    val headers = stub.all.flatMap(_.header("Authorization"))
    headers should have size 2
    all(headers) should startWith("OAuth ")
    all(headers) should include("oauth_consumer_key=\"ck\"")
    headers.distinct should have size 2 // a new nonce each time
  }

  it should "fail before sending when no held credential fits the endpoint" in {
    val stub = Recording(ok(postJson))
    val error = intercept[ScalaxcomAuthenticationException](await(
      client(stub.backend).execute(
        Requests.posts.createPosts(CreatePostsRequest(text = Some("hello"))),
      ),
    ))
    error.getMessage should include("BearerToken")
    stub.count shouldBe 0
  }

  it should "keep an Authorization header the caller set" in {
    val stub = Recording(ok(userJson))
    await(client(stub.backend).execute(
      Requests.users.getUsersByUsername("x").header(
        "Authorization",
        "Bearer mine",
      ),
    ))
    stub.all.head.header("Authorization") shouldBe Some("Bearer mine")
  }

  it should "send no credentials to an endpoint that declares no scheme" in {
    val stub = Recording(ok("""{"info":{"version":"2.175"}}"""))
    await(client(stub.backend).execute(Requests.general.getOpenApiSpec()))
    stub.all.head.header("Authorization") shouldBe None
  }

  it should "describe an error without a body by its status alone" in {
    val stub = Recording(Response("", StatusCode.NotFound))
    val error = intercept[NotFoundException](
      await(
        client(stub.backend).execute(Requests.users.getUsersByUsername("x")),
      ),
    )
    error.getMessage shouldBe "Not Found (404)"
  }

  it should "send the request body as snake_case JSON without nulls" in {
    val stub = Recording(ok(postJson))
    val credentials = Credentials.oauth2UserToken("user-token")
    await(client(stub.backend, credentials).execute(
      Requests.posts.createPosts(
        CreatePostsRequest(text = Some("hello"), quoteTweetId = Some("42")),
      ),
    ))
    stub.all.head.body match
      case StringBody(json, _, _) =>
        json shouldBe """{"quote_tweet_id":"42","text":"hello"}"""
      case other => fail(s"unexpected body $other")
  }

  "OAuth 2.0 sessions" should "refresh a rejected token and retry once" in {
    val tokenStub = auth.StubHttp(
      auth.StubHttp.ok(
        """{"access_token":"fresh","expires_in":7200,"refresh_token":"r2"}""",
      ),
    )
    val session = auth.OAuth2Session.withClock(
      auth.OAuth2Flow.withHttp(
        tokenStub.http,
        "id",
        "https://cb",
        None,
        fixedClock,
      ),
      auth.OAuth2Token(
        "revoked",
        refreshToken = Some("r1"),
      ),
      _ => (),
      1.minute,
      fixedClock,
    )
    val stub = Recording(status(401), ok(userJson))
    await(
      client(stub.backend, Credentials.oauth2User(session))
        .execute(Requests.users.getUsersMe()),
    ).data.flatMap(_.username) shouldBe Some("XDevelopers")

    stub.all.map(_.header("Authorization")) shouldBe
      List(Some("Bearer revoked"), Some("Bearer fresh"))
    tokenStub.count shouldBe 1
  }

  it should "not loop when the refreshed token is rejected too" in {
    val tokenStub = auth.StubHttp(
      auth.StubHttp.ok(
        """{"access_token":"fresh","refresh_token":"r2"}""",
      ),
    )
    val session = auth.OAuth2Session.withClock(
      auth.OAuth2Flow.withHttp(
        tokenStub.http,
        "id",
        "https://cb",
        None,
        fixedClock,
      ),
      auth.OAuth2Token(
        "revoked",
        refreshToken = Some("r1"),
      ),
      _ => (),
      1.minute,
      fixedClock,
    )
    val stub = Recording(status(401))
    an[UnauthorizedException] should be thrownBy await(
      client(stub.backend, Credentials.oauth2User(session))
        .execute(Requests.users.getUsersMe()),
    )
    stub.count shouldBe 2
    tokenStub.count shouldBe 1
  }

  it should "not refresh a fixed token" in {
    val stub = Recording(status(401))
    an[UnauthorizedException] should be thrownBy await(
      client(stub.backend, Credentials.oauth2UserToken("fixed"))
        .execute(Requests.users.getUsersMe()),
    )
    stub.count shouldBe 1
  }

  "retries" should "retry a GET on 503 and succeed" in {
    val stub = Recording(status(503), status(503), ok(userJson))
    val user =
      await(
        client(stub.backend).execute(Requests.users.getUsersByUsername("x")),
      )
    user.data.flatMap(_.id) shouldBe Some("2244994945")
    stub.count shouldBe 3
  }

  it should "give up after maxRetries with the last error" in {
    val stub = Recording(status(503))
    val error = intercept[ServerErrorException](
      await(
        client(stub.backend).execute(Requests.users.getUsersByUsername("x")),
      ),
    )
    error.code shouldBe 503
    error.getMessage should include("went wrong")
    stub.count shouldBe 3 // 1 + maxRetries
  }

  it should "not retry a POST on 503: it may have gone through" in {
    val stub = Recording(status(503), ok(postJson))
    an[ServerErrorException] should be thrownBy await(
      client(stub.backend, Credentials.oauth2UserToken("t")).execute(
        Requests.posts.createPosts(CreatePostsRequest(text = Some("hello"))),
      ),
    )
    stub.count shouldBe 1
  }

  it should "retry a POST on 429: it was not carried out" in {
    val stub = Recording(status(429, Header("Retry-After", "0")), ok(postJson))
    await(client(stub.backend, Credentials.oauth2UserToken("t")).execute(
      Requests.posts.createPosts(CreatePostsRequest(text = Some("hello"))),
    )).data.map(_.id) shouldBe Some("1")
    stub.count shouldBe 2
  }

  it should "fail fast on a 429 whose reset is too far away, with the reset time" in {
    val reset = fixedClock.instant().plusSeconds(900)
    val stub = Recording(status(
      429,
      Header("x-rate-limit-limit", "300"),
      Header("x-rate-limit-remaining", "0"),
      Header("x-rate-limit-reset", reset.getEpochSecond.toString),
    ))
    val error = intercept[RateLimitExceededException](
      await(
        client(stub.backend).execute(Requests.users.getUsersByUsername("x")),
      ),
    )
    error.rateLimit shouldBe Some(RateLimit(Some(300), Some(0), Some(reset)))
    stub.count shouldBe 1
  }

  it should "not retry a 4xx" in {
    val stub = Recording(status(404))
    an[NotFoundException] should be thrownBy await(
      client(stub.backend).execute(Requests.users.getUsersByUsername("x")),
    )
    stub.count shouldBe 1
  }

  it should "retry a request that could not connect" in {
    val calls = AtomicInteger(0)
    val backend =
      SttpBackendStub.asynchronousFuture.whenAnyRequest.thenRespondF(
        (request: Request[?, ?]) =>
          if calls.getAndIncrement() == 0 then
            Future.failed(SttpClientException.ConnectException(
              request,
              java.net.ConnectException("refused"),
            ))
          else Future.successful(ok(userJson)),
      )
    await(client(backend).execute(Requests.users.getUsersByUsername("x")))
      .data.flatMap(_.username) shouldBe Some("XDevelopers")
    calls.get shouldBe 2
  }

  it should "wrap a failure that never produced a response" in {
    val backend =
      SttpBackendStub.asynchronousFuture.whenAnyRequest.thenRespondF(
        Future.failed(RuntimeException("boom")),
      )
    val error = intercept[ScalaxcomClientException](
      await(client(backend).execute(Requests.users.getUsersByUsername("x"))),
    )
    error.getCause.getMessage shouldBe "boom"
  }

  it should "not retry a body that fails to decode" in {
    val stub = Recording(ok("""{"data": 42}"""))
    an[ScalaxcomDecodingException] should be thrownBy await(
      client(stub.backend).execute(Requests.users.getUsersByUsername("x")),
    )
    stub.count shouldBe 1
  }

  "timeout" should "be each request's read timeout" in {
    val stub = Recording(ok(userJson))
    await(client(stub.backend, timeout = 7.seconds).execute(
      Requests.users.getUsersByUsername("x"),
    ))
    stub.all.head.options.readTimeout shouldBe 7.seconds
  }

  it should "leave a request's own read timeout alone" in {
    val stub = Recording(ok(userJson))
    await(client(stub.backend, timeout = 7.seconds).execute(
      Requests.users.getUsersByUsername("x").readTimeout(2.seconds),
    ))
    stub.all.head.options.readTimeout shouldBe 2.seconds
  }

  it should "cut off a slow server, then retry it, against a real socket" in {
    val hits = AtomicInteger(0)
    val server = com.sun.net.httpserver.HttpServer.create(
      java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0),
      0,
    )
    server.createContext(
      "/",
      exchange => {
        // First request hangs past the timeout, the second answers at once.
        if hits.getAndIncrement() == 0 then Thread.sleep(2000)
        val body = userJson.getBytes("UTF-8")
        try
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, body.length.toLong)
          exchange.getResponseBody.write(body)
        catch case _: java.io.IOException => ()
        finally exchange.close()
      },
    )
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    server.start()
    val x = Scalaxcom(
      bearer,
      timeout = 300.millis,
      retryPolicy = fastRetries,
      baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}",
    )
    try
      x.executeSync(Requests.users.getUsersByUsername("x")).map(
        _.data.flatMap(_.username),
      ) shouldBe Right(Some("XDevelopers"))
      hits.get shouldBe 2
    finally
      x.close()
      server.stop(0)
  }

  "executeSync" should "return API errors as values" in {
    val stub = Recording(status(401))
    client(
      stub.backend,
    ).executeSync(Requests.users.getUsersByUsername("x")) match
      case Left(e: UnauthorizedException) => e.code shouldBe 401
      case other => fail(s"unexpected $other")
  }

  it should "wait up to maxWait: every attempt plus every backoff" in {
    client(Recording(ok("")).backend, timeout = 10.seconds).maxWait shouldBe
      fastRetries.maxTotalTime(10.seconds)
  }
}
