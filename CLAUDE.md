# scalaxcom

A Scala 3 client for the X (formerly Twitter) API v2. It follows the
architecture of the sibling project masscaladon (Mastodon client): download
the spec → generate a client from it → wrap it in a hand-written, idiomatic
Scala 3 API. The behavior of the hand-written layer (auth selection, defaults)
is modeled on X's official TypeScript XDK (github.com/xdevplatform/xdk-typescript),
which is generated from the same spec.

## Design goal for `scala-client`

A *pure, beautiful Scala 3 API*:
- Private constructors with companion-object factories.
- Plain methods with explicit arguments: no `given`/`using` ceremony beyond
  the `ExecutionContext`.
- Functional error handling: `Either`, typed exceptions.

Prefer that style over adding boilerplate classes.

## Modules

- **`openapi-client`**: downloads the X spec (`downloadXSchema`, from
  `https://api.x.com/2/openapi.json`, committed under
  `openapi-client/schema/x-openapi-<version>.json`), patches it (see "Codegen"),
  and generates the raw client (`generateClient`). Generated sources under
  `openapi-client/src/main/scala/.../generated/` are committed (vendored).
  `generateClient` deletes that package first, so schemas removed from the
  spec don't linger.
- **`scala-client`**: hand-written layer. See "The `scala-client` API".

## sbt tasks

```
sbt openApiClient/downloadXSchema    # fetch the spec (skipped if the file exists: bump xOpenApiVersion in build.sbt to refresh)
sbt openApiClient/generateClient     # regenerate generated/
sbt compile
sbt test
sbt scalafmtAll / scalafmtCheckAll   # excludes generated/
sbt scalaClient/scalafixAll [--check]
sbt publishLocal
```

`hooks/pre-commit` runs `scalafmtAll`. Enable it with `git config core.hooksPath hooks`.

## The `scala-client` API

Request *creation* and *execution* are decoupled:

- **Creation**: every generated `*Api` is a plain `object` whose methods build
  an sttp `Request` with a relative URI, and tag it (`JsonSupport.SecurityTag`)
  with the spec names of the security schemes the operation accepts.
  `Requests` (`scala-client/.../client/Requests.scala`, hand-written) lists
  them all. Add an entry by hand when a new API tag appears.
- **Execution**: `Scalaxcom` (`client/Scalaxcom.scala`). Its companion `apply`
  takes:
  - `Credentials`
  - `timeout` (default 30s)
  - `RetryPolicy` (default on)
  - `baseUrl` (default `https://api.x.com`)
  - optionally a JDK `HttpClient`

  `.execute` returns a `Future[T]` that always fails with a
  `ScalaxcomException`. `.executeSync` returns
  `Either[ScalaxcomException, T]` and waits up to `maxWait`.

### Authentication (`auth/`)

- `Credentials` holds any of: bearer token, OAuth 2.0 user token, OAuth 1.0a
  keys. It has a private constructor and `with*` builders, and its `toString`
  redacts secrets.
  - The OAuth 2.0 user token is an `AccessTokenSource` (sealed,
    `private[scalaxcom]`): either `Static(token)` or an `OAuth2Session`.
- `SecurityScheme.select` ports the TS XDK's `selectAuthMethod`:
  - It picks among the request's accepted schemes, filtered to the ones held.
  - Write priority: UserToken > OAuth2UserToken > BearerToken.
  - Read priority: the reverse.
  - If the request lists no schemes, it is sent unauthenticated (a deliberate
    departure from the XDK, which uses the best one held). Only
    `GET /2/openapi.json` is like that, and X answers it 401 when it gets a
    token it rejects.
  - If the request lists schemes and none of them is held, it fails with
    `ScalaxcomAuthenticationException` before sending.
- `OAuth1` does HMAC-SHA1 signing per RFC 5849:
  - It signs the query parameters (parsed from the final URL, `+` = space,
    because that's how sttp encodes) and form-urlencoded bodies. JSON and
    multipart bodies are never signed.
  - Its general overload takes an optional token and extra protocol params
    (`oauth_callback`, `oauth_verifier`) for the login flow.
  - It's tested against X's documented example. X's docs show two different
    signatures for that example; `hCtSmYh+iHYCEqBWrE7C7hYmtUk=` is the one
    that reproduces (verified independently).
- Each retry signs again, with a fresh nonce.
- A caller-set `Authorization` header is left alone.

### Login flows (`auth/`, ports of the TS XDK's `oauth1_auth.ts` / `oauth2_auth.ts`)

- `AuthHttp` (internal) is the form POST every OAuth step makes:
  - Non-2xx answers map to `ScalaxcomApiException`. `describe` also reads
    OAuth's `error_description`.
  - **Never retried:** codes are single-use and refresh tokens rotate.
- `OAuth2Flow` covers Authorization Code + PKCE:
  - `authorizationUrl` (`https://x.com/i/oauth2/authorize`), `exchangeCode`,
    `refresh` and `revoke`, all against `api.x.com/2/oauth2/*`.
  - A confidential client uses HTTP Basic; a public client sends `client_id`.
  - `appOnlyBearerToken` uses client credentials on `api.x.com/oauth2/token`.
  - `Pkce` uses S256 and SecureRandom.
- `OAuth2Session` is the refreshing token source. The TS XDK leaves this as a
  TODO.
  - It refreshes `refreshMargin` before expiry, single-flight (X rotates
    refresh tokens), and calls `onRefresh` with every new token.
  - `afterRejection` lets `Scalaxcom` refresh after a 401 and retry once. This
    retry is outside `RetryPolicy`; the `refreshed` flag in `attempt` prevents
    loops.
  - A refresh response without a `refresh_token` keeps the old one.
- `OAuth1Flow` is the 3-legged / PIN flow:
  - `requestToken(callback)` (`oauth_callback` signed in the header, no
    token), `authorizationUrl` (authorize / authenticate),
    `accessToken(requestToken, verifier)` (returns `user_id` and
    `screen_name`), and `invalidate`.
- Test seams: `withHttp`/`withClock` factories, used with the test helper
  `auth/StubHttp`. Mixing a fixed clock with the system clock makes tokens
  look expired, so tests keep them consistent.

### Timeout and retry

- `timeout` is each attempt's read timeout. It replaces sttp's 1-minute
  `DefaultReadTimeout`, and a request's own `.readTimeout` is kept. It is
  also the connect timeout.
- `RetryPolicy` (case class, `default`/`disabled`) holds `delayBefore`, a pure
  decision function that is unit-tested:
  - **429:** any method; waits `Retry-After` or until `x-rate-limit-reset`,
    capped by `maxRateLimitWait`. Beyond the cap it fails fast.
  - **ConnectException:** any method.
  - **500/502/503/504, sttp Timeout/ReadException:** idempotent methods only,
    unless `retryNonIdempotent`.
  - Backoff is exponential with 50–100% jitter.
- The TS XDK declares `retry`/`maxRetries` config but never implements it.
  This is the real thing.
- Waits run on a daemon single-thread scheduler that `close()` shuts down.
- `maxWait = retryPolicy.maxTotalTime(timeout)` bounds `executeSync`.

## Codegen: what's real and why it's customized

`generateClient` shells out to openapi-generator-cli 7.10.0 (`scala-sttp`),
resolved as a jar through a hidden ivy config. The templates in
`openapi-client/templates/` started as masscaladon's (Scala 3 `derives`,
`bodyOpt`, Option-wrapped multipart parts, header params, standalone
serializer objects). Changes for X:

- **`api.mustache`**:
  - Tags each request with `x-security-schemes` (see `SecuritySpecFix`) instead
    of the stock auth parameters.
  - Renders query params via `queryParam(...)`.
  - `application/octet-stream` responses come back as `Array[Byte]` (with
    `operationReturnType.mustache`).
- **`methodParameters.mustache`**: drops the stock leading auth parameter list
  (`(bearerToken: String)(...)`). Optional array params default to
  `Set.empty`/`Seq.empty` rather than being mandatory.
- **`jsonSupport.mustache`**:
  - Adds `SecurityTag`.
  - Adds the `QueryValue` type class: arrays comma-joined (X's
    `style: form, explode: false`; sttp would repeat the parameter), timestamps
    as RFC 3339 with seconds, `None`/empty collections omitted.
- **`model.mustache`**:
  - Each case class's companion holds a `Configuration` mapping every field to
    its exact spec name (`baseName`). circe's snake_case would get
    `playback_0_count` wrong.
  - Enum givens are anonymous: a backticked enum name such as `` `Type` ``
    can't be spliced into a given's name.
- **`additionalTypeSerializers.mustache`**: no global naming `Configuration`
  (see above).
- **`openapi-config.yaml`**: maps `object`/`AnyType` to `io.circe.Json`, with an
  `importMappings` entry so it isn't imported as a model.
- **`.openapi-generator-ignore`**: skips `UploadComplianceJobSubmissionRequest`.
  It's an unreferenced schema wrapping a raw file body, and a JSON codec for
  `java.io.File` is meaningless.

Spec patches, applied in order before generation (`project/*.scala`):

1. `RequiredOnlyCompositionSpecFix`: drops `anyOf`/`oneOf` made only of
   `{required: [...]}` members ("at least one of these fields"). Otherwise the
   generator treats the schema as composed and loses its inline enums.
2. `ScalarCompositionSpecFix`: an `anyOf`/`oneOf` whose members are all
   inline scalars becomes one plain schema. Otherwise the generator emits a
   model with no fields (`AppendMediaUploadRequestMedia()`), which encodes as
   `{}`, so the value cannot be sent. One member: that member, enum kept.
   Same type: that type (an upload segment, `binary` or `byte`, is a `String`
   holding the base64 text). Different types (`integer` or `string`):
   untyped, so `io.circe.Json`.
3. `EnumNameClashSpecFix`: a string enum whose values collide once
   camel-cased (`chat.conversation.join` vs `chat.conversation_join`) becomes a
   plain string.
4. `OneOfSpecFix` (from masscaladon): `oneOf` required = intersection.
5. `HeaderParamSpecFix` (from masscaladon): header params typed as strings.
6. `SecuritySpecFix`: copies each operation's `security` scheme names into
   `x-security-schemes`. openapi-generator drops `OAuth2UserToken` and doesn't
   understand `UserToken` (`http` scheme `OAuth`).

The generator logs one "Illegal schema ... non-object type combined with
properties" error. It's harmless: an array schema with stray `properties`,
which the generator ignores.

Don't hand-edit `generated/`. Change templates, config or spec fixes, then
re-run `generateClient`.
