package com.github.unknownnpc.scalaxcom.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.auth.SecurityScheme.*

import sttp.model.Method

class SecuritySchemeTest extends AnyFlatSpec with Matchers {

  private val all = Seq("OAuth2UserToken", "UserToken", "BearerToken")
  private val everything = Set(BearerToken, OAuth2UserToken, UserToken)

  "select" should "prefer the app-only bearer token for reads" in {
    select(Method.GET, all, everything) shouldBe Right(Some(BearerToken))
  }

  it should "prefer user context (OAuth 1.0a first) for writes" in {
    select(Method.POST, all, everything) shouldBe Right(Some(UserToken))
    select(Method.DELETE, all, Set(BearerToken, OAuth2UserToken)) shouldBe
      Right(Some(OAuth2UserToken))
  }

  it should "only pick a scheme the endpoint accepts" in {
    select(Method.GET, Seq("OAuth2UserToken", "UserToken"), everything) shouldBe
      Right(Some(OAuth2UserToken))
  }

  it should "fail when the endpoint accepts nothing the client holds" in {
    val result =
      select(Method.POST, Seq("OAuth2UserToken", "UserToken"), Set(BearerToken))
    result.isLeft shouldBe true
    result.left.toOption.get.getMessage should include("BearerToken")
  }

  it should "send no credentials when the endpoint declares no scheme" in {
    select(Method.GET, Nil, everything) shouldBe Right(None)
    select(Method.POST, Nil, Set(UserToken)) shouldBe Right(None)
    select(Method.GET, Nil, Set.empty) shouldBe Right(None)
  }

  "Credentials" should "report what they hold without printing secrets" in {
    val c = Credentials.bearerToken("secret-1").withOAuth2UserToken("secret-2")
    c.available shouldBe Set(BearerToken, OAuth2UserToken)
    (c.toString should not).include("secret")
  }
}
