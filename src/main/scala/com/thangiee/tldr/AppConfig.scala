package com.thangiee.tldr

import com.typesafe.config.ConfigFactory

object AppConfig {
  val config = ConfigFactory.load()

  object redis {
    val host = config.getString("redis.host")
    val port = config.getInt("redis.port")
  }

  object textAnalysis {
    val appId = config.getString("textAnalysis.appId")
    val appKey = config.getString("textAnalysis.appKey")
  }

  object reddit {
    val user = config.getString("reddit.user")
    val passwd = config.getString("reddit.passwd")
    val id = config.getString("reddit.id")
    val secret = config.getString("reddit.secret")
  }

  object spark {
    val master = config.getString("spark.master")
  }

  val pollingSize = config.getInt("tldrBot.pollingSize")
  val targetedSubreddits = config.getStringList("tldrBot.targetedSubreddits")
  val blacklistedSites = config.getStringList("tldrBot.blacklistedSites")
}
