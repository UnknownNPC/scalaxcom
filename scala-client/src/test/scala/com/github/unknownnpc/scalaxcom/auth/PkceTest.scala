package com.github.unknownnpc.scalaxcom.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PkceTest extends AnyFlatSpec with Matchers {

  "fromVerifier" should "derive the S256 challenge" in {
    // Cross-checked with Python: base64url(sha256(verifier)), unpadded.
    val pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mJ92K9qcbH5yuJ-ksuL6M4Jd1Fq3Sc")
    pkce.challenge shouldBe "krxgO6ptFjcwJHrsPFlOFG0C_GQuCb3zeqFDRweHRtw"
    pkce.method shouldBe "S256"
  }

  it should "reject a verifier RFC 7636 doesn't allow" in {
    an[IllegalArgumentException] should be thrownBy Pkce.fromVerifier("short")
    an[IllegalArgumentException] should be thrownBy
      Pkce.fromVerifier("a" * 42 + "!")
  }

  "generate" should "make a fresh, valid 43-character verifier each time" in {
    val a = Pkce.generate()
    val b = Pkce.generate()
    (a.verifier should have).length(43)
    (a.verifier should fullyMatch).regex("[A-Za-z0-9\\-_]+")
    a.verifier should not be b.verifier
    Pkce.fromVerifier(a.verifier) shouldBe a
    (a.toString should not).include(a.verifier)
  }
}
