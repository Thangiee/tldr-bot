package com.thangiee.tldr

import java.net.URL

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import com.aylien.textapi.TextAPIClient
import com.aylien.textapi.parameters.ExtractParams
import com.aylien.textapi.responses.Article
import net.dean.jraw.RedditClient
import net.dean.jraw.http.UserAgent
import net.dean.jraw.http.oauth.Credentials
import net.dean.jraw.models.Submission
import net.dean.jraw.paginators.{Sorting, SubredditPaginator, TimePeriod}
import org.apache.spark.{SparkConf, SparkContext}
import org.clulab.processors.Document
import org.clulab.processors.corenlp.CoreNLPProcessor

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scalacache._
import scalacache.redis._

object RedditBot {
  val decider: Supervision.Decider = {
    error => error.printStackTrace(); Supervision.Resume
  }

  // setup Akka
  implicit val system       = ActorSystem()
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withAutoFusing(false).withSupervisionStrategy(decider))

  // setup Redis
  implicit val scalaCache   = ScalaCache(RedisCache(AppConfig.redis.host, AppConfig.redis.port))

  // setup Spark
  val conf = new SparkConf()
    .setAppName("TL;DR")
    .setMaster("local[4]")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
  val sc   = new SparkContext(conf)

  // setup Core-NLP
  val proc = new CoreNLPProcessor(withDiscourse = false)
  proc.annotate("a") // load this now, takes a while for first annotate run


  // setup text analysis to extract article text
  val textApiClient    = new TextAPIClient(AppConfig.textAnalysis.appId, AppConfig.textAnalysis.appKey)
  val articleExtractor = ExtractParams.newBuilder().setBestImage(false)

  // setup reddit api wrapper
  val credentials   = Credentials.script(AppConfig.reddit.user, AppConfig.reddit.passwd, AppConfig.reddit.id, AppConfig.reddit.secret)
  val agent         = UserAgent.of("desktop", "com.thangiee.tldr", "v0.1.0", "tldr-b0t")
  val reddit        = new RedditClient(agent)
  reddit.authenticate(reddit.getOAuthHelper.easyAuth(credentials))
  val accMng = new net.dean.jraw.managers.AccountManager(reddit)

  val paginator = new SubredditPaginator(reddit)
  AppConfig.targetedSubreddits.toList match {
    case h :: Nil => paginator.setSubreddit(h)
    case h :: t   => paginator.setSubreddit(h, t: _*)
    case _        =>
  }
  paginator.setTimePeriod(TimePeriod.HOUR)
  paginator.setSorting(Sorting.NEW)
  paginator.setLimit(AppConfig.pollingSize)

  def main(args: Array[String]): Unit = {
    import shapeless._

    val longPollSubRedditsSrc: Source[SubredditPaginator, Cancellable] =
      Source.tick(0.second, 5.seconds, paginator).logInfo(_ => "POLLING...")

    val fetchPosts: Flow[SubredditPaginator, Vector[Submission], NotUsed] =
      Flow[SubredditPaginator].map(subReddits => {
        subReddits.reset() // rest to first page
        subReddits.next(true).toVector
      })

    val newPostsFilter: Flow[Vector[Submission], Vector[Submission], NotUsed] =
      Flow[Vector[Submission]].via(NewSubmissionFilter(Vector.empty))

    val notSelfPostOrAvoidSiteFilter: Flow[Submission, Submission, NotUsed] =
      Flow[Submission].filter(post => !post.isSelfPost && !AppConfig.blacklistedSites.exists(post.getUrl.contains))

    type Post = ::[Submission, ::[Article, HNil]]
    val extractArticle: Flow[Submission, Post, NotUsed] =
      Flow[Submission].map(post => {
        articleExtractor.setUrl(new URL(post.getUrl))
        val article = textApiClient.extract(articleExtractor.build())
        post :: article :: HNil
      })

    type PostWithArticleTxt = ::[Submission, ::[ArticleTxt, HNil]]
    val formatArticleText: Flow[Post, PostWithArticleTxt, NotUsed] =
      Flow[Post].map { post =>
        val txt = ArticleTxt(
          post.select[Article].getArticle
            .replaceAll("""(\p{Ll})\.(\p{Lu})""", "$1. $2") // add space after period at end of sent
            .replaceAll("""(\p{Ll})\."(\p{Lu})""", "$1. \" $2")
        )
        post.head :: txt :: HNil
      }

    type PostWithWordCount = ::[Submission, ::[WordCount, HNil]]
    val approxWordCount: Flow[Post, PostWithWordCount, NotUsed] =
      Flow[Post].map(post => post.head :: WordCount(post.select[Article].getArticle) :: HNil)

    type SummarizedPost = ::[Submission, ::[Summarization, HNil]]
    val summarizeText: Flow[PostWithArticleTxt, SummarizedPost, NotUsed] =
      Flow[PostWithArticleTxt]
        .logInfo(post => s"processing ${post.select[Submission].getTitle}")
        .map { case post :: articleTxt :: HNil =>
          val summ = sync.caching(post.getUrl) {
            val doc: Document = proc.annotate(articleTxt.value)
            Summarization(doc, sc)
          }
          post :: summ :: HNil
        }
        .logInfo(post => s"Summarized ${post.select[Submission].getTitle}")

    def formatPost(url: String, summ: Summarization): String = {
      val pct = summ.summReducePct * 100
      f"""
         |Beep Boop! I have reduced this [article]($url) to ${summ.summWordCount} of the original ${summ.docWordCount} words (**$pct%.1f%%** less):
         |
         |${summ.chronologicalSentsText.map(s => s">$s").mkString("\n\n")}
         |
         |*Top key phrases*: ${summ.phrasesText.take(5).map(s => s"**$s**").mkString(" | ")}
      """.stripMargin
    }

    val validPostsSrc: Source[Post, Cancellable] =
      longPollSubRedditsSrc
        .via(fetchPosts)
        .via(newPostsFilter)
        .mapConcat(identity)
        .via(notSelfPostOrAvoidSiteFilter)
        .via(extractArticle)

    val postSummaryToRedditSink = Sink.foreach[SummarizedPost] { case post :: summ :: HNil =>
      if (summ.summReducePct > .5) accMng.reply(post, formatPost(post.getUrl, summ))
      else logger.info("Not posting due to less than 50% reduction")
    }

    val insufficientWordCountSink = Sink.foreach[PostWithWordCount] { case post :: wc :: HNil =>
      logger.info(s"Not enough words(${wc.value}), skipping ${post.getTitle}")
    }

    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._

      val bcast = builder.add(Broadcast[Post](2))
      val zip   = builder.add(Zip[PostWithArticleTxt, PostWithWordCount])
      val wcPartition = builder.add(Partition[(PostWithArticleTxt, PostWithWordCount)](2, {
        case (post :: _ :: HNil, post2 :: wc :: HNil) =>
          assert(post.getUrl == post2.getUrl)
          if (wc.value > 300) 1 else 0
      }))

      validPostsSrc ~> bcast.in
                       bcast.out(0) ~> formatArticleText ~> zip.in0
                       bcast.out(1) ~> approxWordCount   ~> zip.in1

      zip.out ~> wcPartition.in
                  wcPartition.out(0).map(_._2) ~> insufficientWordCountSink
                  wcPartition.out(1).map(_._1) ~> summarizeText ~> postSummaryToRedditSink

      ClosedShape
    }).run()
  }

}

case class NewSubmissionFilter(initial: Vector[Submission]) extends GraphStage[FlowShape[Vector[Submission], Vector[Submission]]] {
  val in  = Inlet[Vector[Submission]]("NewSubmissionFilter.in")
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

case class ArticleTxt(value: String) extends AnyVal
case class WordCount(value: Int) extends AnyVal
object WordCount {
  def apply(txt: String): WordCount = WordCount(txt.split(" ").length)
}