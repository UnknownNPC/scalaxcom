package com.github.unknownnpc.scalaxcom.client

import com.github.unknownnpc.scalaxcom.generated.api.AccountActivityApi
import com.github.unknownnpc.scalaxcom.generated.api.AccountApi
import com.github.unknownnpc.scalaxcom.generated.api.ActivityApi
import com.github.unknownnpc.scalaxcom.generated.api.ArticlesApi
import com.github.unknownnpc.scalaxcom.generated.api.BotsApi
import com.github.unknownnpc.scalaxcom.generated.api.BroadcastsApi
import com.github.unknownnpc.scalaxcom.generated.api.CallsApi
import com.github.unknownnpc.scalaxcom.generated.api.ChatApi
import com.github.unknownnpc.scalaxcom.generated.api.CommunitiesApi
import com.github.unknownnpc.scalaxcom.generated.api.CommunityNotesApi
import com.github.unknownnpc.scalaxcom.generated.api.ComplianceApi
import com.github.unknownnpc.scalaxcom.generated.api.ConnectionsApi
import com.github.unknownnpc.scalaxcom.generated.api.DirectMessagesApi
import com.github.unknownnpc.scalaxcom.generated.api.GeneralApi
import com.github.unknownnpc.scalaxcom.generated.api.ListsApi
import com.github.unknownnpc.scalaxcom.generated.api.MediaApi
import com.github.unknownnpc.scalaxcom.generated.api.NewsApi
import com.github.unknownnpc.scalaxcom.generated.api.PostsApi
import com.github.unknownnpc.scalaxcom.generated.api.SpacesApi
import com.github.unknownnpc.scalaxcom.generated.api.StreamApi
import com.github.unknownnpc.scalaxcom.generated.api.TrendsApi
import com.github.unknownnpc.scalaxcom.generated.api.UsageApi
import com.github.unknownnpc.scalaxcom.generated.api.UsersApi
import com.github.unknownnpc.scalaxcom.generated.api.WebhooksApi

/** Every request-creation object in one place, so they're easy to find — the
  * same grouping as the X TypeScript XDK's `client.posts`, `client.users`, ...
  */
object Requests:
  val account = AccountApi
  val accountActivity = AccountActivityApi
  val activity = ActivityApi
  val articles = ArticlesApi
  val bots = BotsApi
  val broadcasts = BroadcastsApi
  val calls = CallsApi
  val chat = ChatApi
  val communities = CommunitiesApi
  val communityNotes = CommunityNotesApi
  val compliance = ComplianceApi
  val connections = ConnectionsApi
  val directMessages = DirectMessagesApi
  val general = GeneralApi
  val lists = ListsApi
  val media = MediaApi
  val news = NewsApi
  val posts = PostsApi
  val spaces = SpacesApi
  val stream = StreamApi
  val trends = TrendsApi
  val usage = UsageApi
  val users = UsersApi
  val webhooks = WebhooksApi
