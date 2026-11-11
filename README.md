# scalaxcom

[![Maven Central](https://img.shields.io/maven-central/v/com.github.unknownnpc/scalaxcom-client_3.svg)](https://central.sonatype.com/artifact/com.github.unknownnpc/scalaxcom-client_3/overview)

A Scala 3 client for the [X API v2](https://docs.x.com/x-api/introduction),
built on [sttp](https://sttp.softwaremill.com/) and [circe](https://circe.github.io/circe/).
It is generated from X's official OpenAPI spec, the same one X's
[TypeScript XDK](https://github.com/xdevplatform/xdk-typescript) is built
from. On top of that sit:
- automatic per-endpoint authentication (OAuth 1.0a, OAuth 2.0),
- retries and timeouts,
- functional error handling instead of unchecked exceptions.

> **Note:** This project — the build pipeline, the OpenAPI codegen
> customization, and the client API — was largely put together via vibe
> coding with [Claude Code](https://claude.com/claude-code). Review it before
> relying on it for anything production-critical.

## Getting started

Requirements: JDK 17+, [sbt](https://www.scala-sbt.org/) 1.12+.

```sh
sbt compile   # builds both modules; spec + generated client are already committed
sbt test
```

```scala
import scala.concurrent.ExecutionContext

import com.github.unknownnpc.scalaxcom.auth.Credentials
import com.github.unknownnpc.scalaxcom.client.{Requests, Scalaxcom}

given ExecutionContext = ExecutionContext.global
val x = Scalaxcom(Credentials.bearerToken("..."))

// async
x.execute(Requests.users.getUsersByUsername("XDevelopers"))

// blocking, e.g. from a script
x.executeSync(Requests.posts.getPostsById("20", postFields = Set("created_at"))) match
  case Right(response) => println(response.data)
  case Left(error)     => println(s"failed: ${error.getMessage}")
```

`Requests` groups the endpoints the way the TypeScript XDK does: `posts`,
`users`, `media`, `lists`, `directMessages`, and so on.

## Authentication

Give the client whatever credentials you have. For each request, it picks one
that the endpoint accepts:
- for reads, the app-only token first;
- for writes, user context first.

```scala
Credentials.bearerToken("...")                          // app-only
Credentials.oauth2UserToken("...")                      // OAuth 2.0 user, fixed token
Credentials.oauth2User(session)                         // OAuth 2.0 user, auto-refreshed
Credentials.oauth1(consumerKey, consumerSecret, accessToken, accessTokenSecret)
```

Getting a user token with OAuth 2.0 (Authorization Code + PKCE):

```scala
val flow = OAuth2Flow("client-id", "https://example.com/callback")
val pkce = Pkce.generate()
val url  = flow.authorizationUrl(Set("tweet.read", "users.read", "offline.access"), state, pkce)

// after X redirects back with ?code=...
flow.exchangeCode(code, pkce).map { token =>
  // refreshed before it expires; save every new token, X rotates refresh tokens
  Scalaxcom(Credentials.oauth2User(OAuth2Session(flow, token, onRefresh = save)))
}
```

OAuth 1.0a works the same way:
- `OAuth1Flow.requestToken`, then `authorizationUrl`, then `accessToken`.
- Use the callback `"oob"` for the PIN flow.

`OAuth2Flow.appOnlyBearerToken(key, secret)` gets an app-only token.

## Timeouts and retries

```scala
import scala.concurrent.duration.*

val x = Scalaxcom(
  credentials,
  timeout = 10.seconds,                                  // per attempt, 30s by default
  retryPolicy = RetryPolicy(maxRetries = 5),             // RetryPolicy.disabled to turn off
)
```

Retries are on by default: 3 retries, with exponential backoff and jitter.
- **429:** retried after the wait X asks for, up to `maxRateLimitWait`.
- **Connection failures:** always retried.
- **5xx and timeouts:** retried only for idempotent requests, so a post is
  never published twice.

`executeSync` waits up to `x.maxWait`: every attempt plus every wait in
between.

Errors are typed `ScalaxcomException`s. HTTP errors are mapped by status
code (`RateLimitExceededException` carries `rateLimit.reset`, among others).
Network failures come back as `ScalaxcomClientException`.

## Project layout

Two sbt modules, one pipeline: download the spec, generate a raw client from
it, wrap it in the hand-written `scala-client` API shown above.

```
sbt openApiClient/downloadXSchema    # fetch the OpenAPI spec
sbt openApiClient/generateClient     # regenerate the raw client from it
sbt compile                          # build everything
sbt test                             # run tests
sbt scalafmtAll                      # format all sources
sbt scalaClient/scalafixAll          # lint scala-client
sbt publishLocal                     # publish to the local Ivy repo
```

See [`CLAUDE.md`](./CLAUDE.md) for the full architecture: why the codegen
pipeline is customized the way it is, and the design of the `scala-client`
API layer.
