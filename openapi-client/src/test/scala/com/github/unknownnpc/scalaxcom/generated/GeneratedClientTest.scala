package com.github.unknownnpc.scalaxcom.generated

import java.time.OffsetDateTime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.github.unknownnpc.scalaxcom.generated.api.MediaApi
import com.github.unknownnpc.scalaxcom.generated.api.PostsApi
import com.github.unknownnpc.scalaxcom.generated.api.UsersApi
import com.github.unknownnpc.scalaxcom.generated.core.JsonSupport.SecurityTag
import com.github.unknownnpc.scalaxcom.generated.model.AppendMediaUploadRequest
import com.github.unknownnpc.scalaxcom.generated.model.CreateUserBroadcastRequest
import com.github.unknownnpc.scalaxcom.generated.model.GetPostsByIdResponse
import com.github.unknownnpc.scalaxcom.generated.model.MediaOrganicMetrics
import com.github.unknownnpc.scalaxcom.generated.model.PostReferencedPostsInner
import com.github.unknownnpc.scalaxcom.generated.model.PostReferencedPostsInnerEnums

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*

class GeneratedClientTest extends AnyFlatSpec with Matchers {

  "query parameters" should "be comma-joined arrays, absent values left out" in {
    PostsApi.searchPostsRecent(
      "from:XDevelopers cats",
      maxResults = Some(10),
      startTime = Some(OffsetDateTime.parse("2026-01-02T03:04:00Z")),
      postFields = Set("created_at", "author_id"),
    ).uri.toString shouldBe
      "/2/tweets/search/recent?query=from:XDevelopers+cats&max_results=10" +
      "&start_time=2026-01-02T03:04:00Z&post.fields=created_at,author_id"
  }

  "security schemes" should "be recorded on each request, as the spec lists them" in {
    PostsApi.getPostsById("1").tag(SecurityTag) shouldBe
      Some(Seq("OAuth2UserToken", "UserToken", "BearerToken"))
    UsersApi.getUsersMe().tag(SecurityTag) shouldBe
      Some(Seq("OAuth2UserToken", "UserToken"))
  }

  "models" should "decode X's snake_case JSON" in {
    val json =
      """{"data":{"id":"20","text":"just setting up my twttr","author_id":"12",
        |"created_at":"2006-03-21T20:50:14.000Z","edit_history_post_ids":["20"]}}""".stripMargin
    val post = decode[GetPostsByIdResponse](json).toTry.get.data.get
    post.authorId shouldBe Some("12")
    post.createdAt shouldBe Some(OffsetDateTime.parse("2006-03-21T20:50:14Z"))
    post.editHistoryPostIds shouldBe Some(Seq("20"))
  }

  it should "use the spec's exact names, digits included" in {
    val metrics = decode[MediaOrganicMetrics](
      """{"playback_0_count":5,"playback_100_count":1}""",
    ).toTry.get
    metrics.playback0Count shouldBe Some(5L)
    metrics.playback100Count shouldBe Some(1L)
    metrics.asJson.noSpaces should include("\"playback_0_count\":5")
  }

  // ScalarCompositionSpecFix: a union of scalars is a plain value, not an empty model.
  "a union of scalars" should "carry an upload segment as base64 text" in {
    val request = MediaApi.appendMediaUpload(
      "1880028106020515840",
      AppendMediaUploadRequest(media = "AAECAw==", segmentIndex = 2),
    )
    request.uri.toString shouldBe "/2/media/upload/1880028106020515840/append"
    request.body.toString should include(
      """{"media":"AAECAw==","segment_index":2}""",
    )
  }

  it should "keep the enum of a single-member union" in {
    val reference = decode[PostReferencedPostsInner](
      """{"id":"20","type":"quoted"}""",
    ).toTry.get
    reference.`type` shouldBe PostReferencedPostsInnerEnums.`Type`.Quoted
  }

  it should "take either type when the members differ" in {
    CreateUserBroadcastRequest(
      Some(Json.True),
      sourceId = "s1",
    ).asJson.noSpaces should
      include("\"is_low_latency\":true")
    CreateUserBroadcastRequest(
      Some(Json.fromString("true")),
      sourceId = "s1",
    ).asJson.noSpaces should
      include("\"is_low_latency\":\"true\"")
  }
}
