package com.github.unknownnpc.scalaxcom.auth

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A PKCE pair (RFC 7636) for the OAuth 2.0 Authorization Code flow: the
  * `challenge` goes into the authorization URL, the `verifier` — kept secret
  * until then — into the code exchange. Keep the verifier with the `state`
  * between the two steps; it's what proves the exchange comes from whoever
  * started the flow.
  */
final case class Pkce private (verifier: String, challenge: String):
  /** Always `S256`: the challenge is the SHA-256 of the verifier. */
  def method: String = "S256"

  override def toString: String = s"Pkce(verifier=***, challenge=$challenge)"

object Pkce:

  private val random = SecureRandom()
  private val base64Url = Base64.getUrlEncoder.withoutPadding
  private val VerifierPattern = "[A-Za-z0-9\\-._~]{43,128}".r

  /** A fresh pair: 32 random bytes, i.e. a 43-character verifier. */
  def generate(): Pkce =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    fromVerifier(base64Url.encodeToString(bytes))

  /** The pair for a verifier kept from earlier (43–128 characters of
    * `A-Z a-z 0-9 - . _ ~`).
    */
  def fromVerifier(verifier: String): Pkce =
    require(
      VerifierPattern.matches(verifier),
      "A PKCE verifier is 43-128 characters of A-Z a-z 0-9 - . _ ~",
    )
    val digest =
      MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII))
    new Pkce(verifier, base64Url.encodeToString(digest))
