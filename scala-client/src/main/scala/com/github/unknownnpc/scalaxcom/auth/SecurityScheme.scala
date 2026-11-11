package com.github.unknownnpc.scalaxcom.auth

import com.github.unknownnpc.scalaxcom.exception.ScalaxcomAuthenticationException

import sttp.model.Method

/** The security schemes of the X OpenAPI spec, under their spec names. */
enum SecurityScheme:
  /** App-only OAuth 2.0 bearer token. */
  case BearerToken

  /** OAuth 2.0 user-context access token. */
  case OAuth2UserToken

  /** OAuth 1.0a user context. */
  case UserToken

object SecurityScheme:

  def fromSpecName(name: String): Option[SecurityScheme] =
    values.find(_.toString == name)

  private val WriteMethods =
    Set(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)

  // Same priorities as X's own TypeScript XDK: user context first for writes
  // (most of them require it), the simpler app-only token first for reads.
  private val WritePriority = Seq(UserToken, OAuth2UserToken, BearerToken)
  private val ReadPriority = Seq(BearerToken, OAuth2UserToken, UserToken)

  /** Picks how to authenticate a request.
    *
    * @param accepted
    *   the spec names of the schemes the endpoint accepts (any one of them).
    *   Empty: the endpoint declares none (it is public), so the request goes
    *   out unauthenticated. X answers 401 to a token it rejects even there, and
    *   a public endpoint has no business seeing credentials.
    * @return
    *   the scheme to use (`None`: send unauthenticated), or an error when the
    *   endpoint requires a scheme the client holds no credentials for.
    */
  def select(
      method: Method,
      accepted: Seq[String],
      available: Set[SecurityScheme],
    ): Either[ScalaxcomAuthenticationException, Option[SecurityScheme]] =
    if accepted.isEmpty then Right(None)
    else
      val priority =
        if WriteMethods.contains(method) then WritePriority else ReadPriority
      val acceptable = accepted.flatMap(fromSpecName).toSet
      priority.find(s => acceptable.contains(s) && available.contains(s)) match
        case found @ Some(_) => Right(found)
        case None => Left(ScalaxcomAuthenticationException(
            s"This endpoint requires one of: ${accepted.mkString(", ")}; " +
              s"the client has credentials for: " +
              (if available.isEmpty then "none"
               else available.toSeq.map(_.toString).sorted.mkString(", ")),
          ))
