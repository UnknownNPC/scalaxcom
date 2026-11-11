package com.github.unknownnpc.scalaxcom.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import sttp.client3.Request
import sttp.client3.Response
import sttp.client3.StringBody
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode

/** An `AuthHttp` over a stub answering `responses` in order (the last one
  * repeats), recording what it was sent.
  */
class StubHttp(
    responses: (() => Future[Response[String]])*,
  )(using ExecutionContext) {
  private val received = ConcurrentLinkedQueue[Request[?, ?]]()
  private val next = AtomicInteger(0)

  val http: AuthHttp = new AuthHttp(
    SttpBackendStub.asynchronousFuture.whenAnyRequest.thenRespondF {
      (request: Request[?, ?]) =>
        received.add(request)
        val i = next.getAndIncrement()
        responses(math.min(i, responses.size - 1))()
    },
    5.seconds,
  )

  def requests: List[Request[?, ?]] = received.asScala.toList
  def count: Int = received.size

  def form(i: Int = 0): Map[String, String] = requests(i).body match
    case StringBody(content, _, _) => OAuth1.parseForm(content).toMap
    case _ => Map.empty
}

object StubHttp {
  val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
  val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

  def ok(body: String): () => Future[Response[String]] =
    () => Future.successful(Response(body, StatusCode.Ok))

  def status(code: Int, body: String): () => Future[Response[String]] =
    () => Future.successful(Response(body, StatusCode(code)))
}
