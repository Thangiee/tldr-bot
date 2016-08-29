package com.thangiee.tldr

import java.net.URL

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import com.aylien.textapi.TextAPIClient
import com.aylien.textapi.parameters.ExtractParams
import net.dean.jraw.RedditClient
import net.dean.jraw.http.UserAgent
import net.dean.jraw.http.oauth.Credentials
import net.dean.jraw.models.Submission
import net.dean.jraw.paginators.{Sorting, SubredditPaginator, TimePeriod}
import org.apache.spark.{SparkConf, SparkContext}
import org.clulab.processors.Document
import org.clulab.processors.corenlp.CoreNLPProcessor

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scalacache._
import scalacache.redis._

object RedditBot {

  def postFormat(url: String, summ: Summarization): String = {
    val pct = summ.summReducePct * 100
    f"""
      |I am a bot! I have reduced this [article]($url) to ${summ.summWordCount} of the original ${summ.docWordCount} words (**$pct%.1f%%** less):
      |
      |${summ.chronologicalSentsText.map(s => s">$s").mkString("\n\n")}
      |
      |*Top key phrases*: ${summ.phrasesText.take(5).map(s => s"**$s**").mkString(" | ")}
    """.stripMargin
  }

  def wordcount(txt: String): Int = txt.split(" ").length

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()
    implicit val scalaCache = ScalaCache(RedisCache(AppConfig.redis.host, AppConfig.redis.port))

    val conf = new SparkConf()
      .setAppName("TL;DR")
      .setMaster("local[4]")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    val sc = new SparkContext(conf)
    sc.setLogLevel("ERROR")

    val proc = new CoreNLPProcessor(withDiscourse = false)
    proc.annotate("a") // load this now, it takes a while

    val textApiClient = new TextAPIClient(AppConfig.textAnalysis.appId, AppConfig.textAnalysis.appKey)
    val builder = ExtractParams.newBuilder().setBestImage(false)

    val credentials = Credentials.script(AppConfig.reddit.user, AppConfig.reddit.passwd, AppConfig.reddit.id, AppConfig.reddit.secret)
    val agent = UserAgent.of("desktop", "com.thangiee.tldr", "v0.1.0", "tldr-b0t")
    val reddit = new RedditClient(agent)
    val authData = reddit.getOAuthHelper.easyAuth(credentials)
    reddit.authenticate(authData)

    val accMng = new net.dean.jraw.managers.AccountManager(reddit)

    val subReddits = new SubredditPaginator(reddit, "worldnews")
    subReddits.setTimePeriod(TimePeriod.HOUR)
    subReddits.setSorting(Sorting.NEW)
    subReddits.setLimit(1)

    val avoidSites = Seq("reddit", "twitter", "imgur")

    def summarizeText(url: String, txt: String): Future[String] = caching(url)(
      Future.successful {
        println(s"processing: $txt")
        val doc: Document = proc.annotate(txt)
        val summ = Summarization(doc, sc)
        postFormat(url, summ)
      })

    Source.tick(0.second, 5.seconds, subReddits)
      .map(sub => { sub.reset(); sub.next(true).toVector })
      .via(NewSubmissionFilter(Vector.empty))
      .mapConcat(identity)
      .filterNot(_.isSelfPost)
      .filterNot(post => avoidSites.exists(post.getUrl.contains))
      .map(post => (post, {builder.setUrl(new URL(post.getUrl)); textApiClient.extract(builder.build()).getArticle.replaceAll("(\\p{Ll})\\.(\\p{Lu})", "$1. $2")}))
      .filter(kv => wordcount(kv._2) >= 300)
      .mapAsync(4)(kv => summarizeText(kv._1.getUrl, kv._2).map(summ => (kv._1, summ)))
      .map { case (post, summ) => accMng.reply(post, summ); summ }
      .to(Sink.foreach(println))
      .run()
  }
}

case class NewSubmissionFilter(initial: Vector[Submission]) extends GraphStage[FlowShape[Vector[Submission], Vector[Submission]]] {
  val in = Inlet[Vector[Submission]]("NewSubmissionFilter.in")
  val out = Outlet[Vector[Submission]]("NewSubmissionFilter.out")

  val shape = FlowShape.of(in, out)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var currentValue: Vector[Submission] = initial

    setHandlers(in, out, new InHandler with OutHandler {
      def onPush(): Unit = {
        val oldValue = currentValue
        currentValue = grab(in)
        push(out, currentValue diff oldValue)
      }

      def onPull(): Unit = pull(in)
    })
  }
}