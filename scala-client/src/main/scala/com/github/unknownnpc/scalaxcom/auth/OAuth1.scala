package com.github.unknownnpc.scalaxcom.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** OAuth 1.0a request signing (HMAC-SHA1, RFC 5849), as X expects it. */
object OAuth1:

  /** Builds the `Authorization` header value for one request made with a user's
    * access token.
    *
    * @param url
    *   the full request URL, query string included: query parameters are part
    *   of the signature.
    * @param formParams
    *   the decoded parameters of an `application/x-www-form-urlencoded` body,
    *   which are signed too. JSON and multipart bodies are not (RFC 5849,
    *   section 3.4.1.3), so pass nothing for them.
    * @param nonce
    *   unique per request.
    * @param timestamp
    *   seconds since the epoch.
    */
  def authorizationHeader(
      credentials: OAuth1Credentials,
      method: String,
      url: String,
      formParams: Seq[(String, String)],
      nonce: String,
      timestamp: Long,
    ): String =
    authorizationHeader(
      consumerKey = credentials.consumerKey,
      consumerSecret = credentials.consumerSecret,
      token = Some(credentials.accessToken -> credentials.accessTokenSecret),
      method = method,
      url = url,
      formParams = formParams,
      protocolParams = Nil,
      nonce = nonce,
      timestamp = timestamp,
    )

  /** The general form, also used by the 3-legged login ([[OAuth1Flow]]).
    *
    * @param token
    *   the token and its secret: a user's access token, a temporary request
    *   token, or none at all (requesting a request token).
    * @param protocolParams
    *   extra `oauth_*` parameters sent and signed in the header, such as
    *   `oauth_callback` or `oauth_verifier`.
    */
  def authorizationHeader(
      consumerKey: String,
      consumerSecret: String,
      token: Option[(String, String)],
      method: String,
      url: String,
      formParams: Seq[(String, String)],
      protocolParams: Seq[(String, String)],
      nonce: String,
      timestamp: Long,
    ): String =
    val oauthParams = Seq(
      "oauth_consumer_key" -> consumerKey,
      "oauth_nonce" -> nonce,
      "oauth_signature_method" -> "HMAC-SHA1",
      "oauth_timestamp" -> timestamp.toString,
      "oauth_version" -> "1.0",
    ) ++ token.map((t, _) => "oauth_token" -> t) ++ protocolParams
    val sig = signature(
      consumerSecret,
      token.fold("")(_._2),
      method,
      url,
      oauthParams ++ formParams,
    )
    (oauthParams :+ "oauth_signature" -> sig)
      .sortBy(_._1)
      .map((k, v) => s"""${percentEncode(k)}="${percentEncode(v)}"""")
      .mkString("OAuth ", ", ", "")

  /** The base64 HMAC-SHA1 signature of a request. `params` are the oauth_* and
    * form parameters; the query parameters are read from `url`.
    */
  def signature(
      consumerSecret: String,
      tokenSecret: String,
      method: String,
      url: String,
      params: Seq[(String, String)],
    ): String =
    val uri = URI(url)
    val allParams = params ++ queryParams(uri)
    val normalized = allParams
      .map((k, v) => percentEncode(k) -> percentEncode(v))
      .sorted
      .map((k, v) => s"$k=$v")
      .mkString("&")
    val base = Seq(method.toUpperCase, baseUrl(uri), normalized)
      .map(percentEncode)
      .mkString("&")
    val key = percentEncode(consumerSecret) + "&" + percentEncode(tokenSecret)
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key.getBytes(UTF_8), "HmacSHA1"))
    Base64.getEncoder.encodeToString(mac.doFinal(base.getBytes(UTF_8)))

  /** RFC 3986 percent-encoding: everything but `A-Z a-z 0-9 - . _ ~`. */
  def percentEncode(s: String): String =
    URLEncoder.encode(s, UTF_8)
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")

  /** Decodes `application/x-www-form-urlencoded` content. */
  def parseForm(encoded: String): Seq[(String, String)] =
    if encoded.isEmpty then Nil
    else
      encoded.split('&').toSeq.filter(_.nonEmpty).map(pair =>
        pair.split("=", 2) match
          case Array(k, v) => decode(k) -> decode(v)
          case Array(k) => decode(k) -> "",
      )

  private def decode(s: String) = URLDecoder.decode(s, UTF_8)

  // Decoded like form content, `+` being a space: that's how sttp encodes
  // query values (a literal plus goes out as `%2B`), and how X reads them.
  private def queryParams(uri: URI): Seq[(String, String)] =
    Option(uri.getRawQuery).toSeq.flatMap(parseForm)

  // scheme://host[:port]/path, lowercase scheme and host, default port dropped.
  private def baseUrl(uri: URI): String =
    val scheme = uri.getScheme.toLowerCase
    val port = uri.getPort match
      case -1 => ""
      case 80 if scheme == "http" => ""
      case 443 if scheme == "https" => ""
      case p => s":$p"
    val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    s"$scheme://${uri.getHost.toLowerCase}$port$path"
