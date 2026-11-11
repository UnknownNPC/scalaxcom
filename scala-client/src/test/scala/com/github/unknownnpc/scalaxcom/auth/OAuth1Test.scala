package com.github.unknownnpc.scalaxcom.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OAuth1Test extends AnyFlatSpec with Matchers {

  // The worked example from X's "Creating a signature" documentation
  // (developer.x.com, OAuth 1.0a): known inputs, known signature
  // `hCtSmYh+iHYCEqBWrE7C7hYmtUk=`. (X's "Authorizing a request" page shows
  // a different signature for the same inputs; it does not reproduce.)
  private val credentials = OAuth1Credentials(
    consumerKey = "xvz1evFS4wEEPTGEFPHBog",
    consumerSecret = "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
    accessToken = "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
    accessTokenSecret = "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
  )
  private val nonce = "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg"
  private val timestamp = 1318622958L
  private val url =
    "https://api.twitter.com/1.1/statuses/update.json?include_entities=true"
  private val form =
    Seq("status" -> "Hello Ladies + Gentlemen, a signed OAuth request!")

  "authorizationHeader" should "match X's documented signature" in {
    val header = OAuth1.authorizationHeader(
      credentials,
      "POST",
      url,
      form,
      nonce,
      timestamp,
    )

    header should startWith("OAuth ")
    header should include(
      "oauth_signature=\"hCtSmYh%2BiHYCEqBWrE7C7hYmtUk%3D\"",
    )
    header should include("oauth_consumer_key=\"xvz1evFS4wEEPTGEFPHBog\"")
    header should include(s"oauth_nonce=\"$nonce\"")
    header should include(s"oauth_timestamp=\"$timestamp\"")
    header should include("oauth_signature_method=\"HMAC-SHA1\"")
    header should include("oauth_version=\"1.0\"")
  }

  it should "sign query parameters the way sttp encodes them (space as +)" in {
    val withPlus = OAuth1.authorizationHeader(
      credentials,
      "GET",
      "https://api.x.com/2/tweets/search/recent?query=cat+dog",
      Nil,
      nonce,
      timestamp,
    )
    val withPercent = OAuth1.authorizationHeader(
      credentials,
      "GET",
      "https://api.x.com/2/tweets/search/recent?query=cat%20dog",
      Nil,
      nonce,
      timestamp,
    )
    withPlus shouldBe withPercent
  }

  it should "change the signature when any signed input changes" in {
    def sign(method: String, u: String) =
      OAuth1.authorizationHeader(credentials, method, u, Nil, nonce, timestamp)
    val base = sign("GET", "https://api.x.com/2/users/me")
    sign("POST", "https://api.x.com/2/users/me") should not be base
    sign("GET", "https://api.x.com/2/users/me?x=1") should not be base
    // Scheme and host are case-insensitive, default port dropped.
    sign("GET", "HTTPS://API.X.COM:443/2/users/me") shouldBe base
  }

  "percentEncode" should "encode per RFC 3986" in {
    OAuth1.percentEncode(
      "Ladies + Gentlemen",
    ) shouldBe "Ladies%20%2B%20Gentlemen"
    OAuth1.percentEncode(
      "An encoded string!",
    ) shouldBe "An%20encoded%20string%21"
    OAuth1.percentEncode(
      "Dogs, Cats & Mice",
    ) shouldBe "Dogs%2C%20Cats%20%26%20Mice"
    OAuth1.percentEncode("☃") shouldBe "%E2%98%83"
    OAuth1.percentEncode("a-b.c_d~e*") shouldBe "a-b.c_d~e%2A"
  }

  "OAuth1Credentials" should "not print its secrets" in {
    (credentials.toString should not).include(credentials.consumerSecret)
    (credentials.toString should not).include(credentials.accessTokenSecret)
  }
}
