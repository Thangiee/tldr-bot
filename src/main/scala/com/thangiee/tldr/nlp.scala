package com.thangiee.tldr

import cats.implicits._
import org.apache.spark.SparkContext
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.clulab.processors.Document

import scala.collection.Map
import scala.language.higherKinds

case class Word(stem: String, tag: String, txt: String, sentIdx: Int, wordIdx: Int) {
  val id: Int = txt.toLowerCase.hashCode
}

case class RankedWord(score: Double, word: Word) {
  val wordIdx = word.wordIdx
}

case class RankedSent(idx: PartitionID, score: Double, words: Iterable[Word]) {
  val text: String = words.map(_.txt).mkString(" ")
}

case class Phrase(score: Double, words: Iterable[Word]) {
  val text: String = words.map(_.txt).mkString(" ")
}

object Phrase {
  def apply(rankedWords: Iterable[RankedWord]): Phrase = {
    val totalScore = rankedWords.foldMap(_.score)
    Phrase(totalScore, rankedWords.map(_.word))
  }
}

object nlp {

  def parseDoc(doc: Document): (SparkContext) => RDD[Word] = sc =>
    sc.parallelize(doc.sentences)
      .zipWithIndex()
      .flatMap { case (sent, sentIdx) =>
        (for {
          stems <- sent.lemmas
          tags <- sent.tags
        } yield stems zip tags zip sent.words)
          .getOrElse(Array.empty)
          .map(tuple3 => (tuple3, sentIdx))
      }
      .zipWithIndex
      .map { case ((((stem, tag), txt), idxS), idxW) => Word(stem, tag, txt, idxS.toInt, idxW.toInt) }

  def syntacticFilters(word: Word, partsOfSpeech: Iterable[String]): Boolean =
    partsOfSpeech.map(pos => word.tag.startsWith(pos)).reduce(_ || _)

  val noun = Vector("NN")
  def isNoun(word: Word): Boolean = syntacticFilters(word, noun)

  val adj = Vector("NN")
  def isAdj(word: Word) = syntacticFilters(word, adj)

  val nounAdj = noun ++ adj
  def isNounOrAdj(word: Word): Boolean = syntacticFilters(word, nounAdj)

  type Sentence = Iterable[Word]
  def similarity(s1: Sentence, s2: Sentence): Double = {
    val score = s1.foldMap(w1 => s2.count(_.stem == w1.stem))
    val norm = math.log(s1.size) + math.log(s2.size)
    score / (if (norm == 0) 1.0 else norm)
  }

  def neighborsWord(windowSize: Int, word: Word, words: Vector[Word]): Vector[Word] = {
    val i = word.wordIdx
    val indices = (i-windowSize to i+windowSize) diff IndexedSeq(i)
    indices.flatMap(words.lift(_)).toVector
  }

  def extractPhrases(words: Vector[RankedWord]): Vector[Phrase] = {
    val consecutiveWords = words
      .sortBy(_.wordIdx)
      .foldLeft((Vector.empty[Vector[RankedWord]], 0, Vector.empty[RankedWord])){ case ((acc, i, phrase), word) =>
        if (word.wordIdx == i) (acc, i+1, phrase :+ word)
        else (acc :+ phrase, word.wordIdx+1, Vector(word))
      }._1

    consecutiveWords.map(Phrase(_)).filter(_.score != 0.0)
  }

  def summarize(words: RDD[Word]): RDD[RankedSent] = {
    val sentences: RDD[(VertexId, Sentence)] = words.groupBy(_.sentIdx.toLong)

    val nodes: RDD[(VertexId, Sentence)] = sentences.mapValues(_.filter(isNounOrAdj))
    val edges: RDD[Edge[Double]] = nodes.cartesian(nodes)
      .filter { case (n1, n2) => n1 != n2 } // remove nodes pairing with themselves
      .map { case ((id1, sent1), (id2, sent2)) => Edge(id1, id2, similarity(sent1, sent2)) }
      .filter(_.attr != 0) // remove edges with 0 similarity

    Graph(nodes, edges)
      .pageRank(.001).vertices.join(sentences)
      .map{ case (id, (score, sent)) => RankedSent(id.toInt, score, sent) }
      .sortBy(_.score, ascending = false)
  }

  def extractKeywords(words: RDD[Word]): RDD[RankedWord] = {
    val orderWords = words.sortBy(_.wordIdx) // chronological order
    val collectedWords = orderWords.collect().toVector
    val filteredWords = orderWords.filter(isNounOrAdj)

    val nodes: RDD[(VertexId, Word)] = filteredWords.distinctBy(_.id).keyBy(_.id)
    val edges: RDD[Edge[Int]] = filteredWords.flatMap { w =>
      neighborsWord(windowSize = 2, w, collectedWords)
        .filter(isNounOrAdj)
        .filterNot(_.stem == w.stem)
        .map(w2 => Edge(w.id, w2.id, 1))
    }.distinct()

    val commonStemEdges: RDD[Edge[Int]] = nodes.map(_._2).groupBy(_.stem).flatMap(_._2.toList.combinations(2)).flatMap {
      case w1 :: w2 :: Nil => Seq(Edge(w1.id, w2.id, 1), Edge(w2.id, w1.id, 1))
      case _ => List.empty
    }

    val graph = Graph(nodes, edges ++ commonStemEdges)
    val scores: Map[Int, Double] = graph.pageRank(.001).vertices.join(nodes).values.mapValues(_.id).map(_.swap).collectAsMap()
    filteredWords
      .map(w => RankedWord(scores.getOrElse(w.id, 0.0), w))
      .filter(_.score != 0.0)
      .sortBy(_.score, ascending = false)
  }

  def extractPhrases(rankedWords: RDD[RankedWord]): Vector[Phrase] = {
    val consecutiveWords = rankedWords
      .filter(r => isNoun(r.word))
      .sortBy(_.wordIdx)
      .collect()
      .foldLeft((Vector.empty[Vector[RankedWord]], 0, Vector.empty[RankedWord])){ case ((acc, i, curr), word) =>
        if (word.wordIdx == i) (acc, i+1, curr :+ word)
        else (acc :+ curr, word.wordIdx+1, Vector(word))
      }._1

    consecutiveWords.map(Phrase(_)).filter(_.score != 0.0).sortBy(_.score).reverse
  }
}

case class Summarization(docWordCount: Long, summWordCount: Long, rankedSents: Vector[RankedSent], phrases: Vector[Phrase]) {
  lazy val chronologicalSentsText: Vector[String] = rankedSents.sortBy(_.idx).map(s => Summarization.reformat(s.text))
  lazy val phrasesText           : Vector[String] = phrases.map(_.text).distinct
  lazy val summReducePct: Double = 1 - summWordCount.toDouble / docWordCount
}

object Summarization {
  private val replace = (old: String, `new`: String) => (_: String).replace(old, `new`)
  private val reformat = replace("`` ", "\"") andThen replace(" ''", "\"") andThen replace(" ,", ",") andThen
                         replace(" .", ".") andThen replace(" '", "'") andThen replace(" n't", "n't") andThen
                         replace("-LRB- ", "(") andThen replace(" -RRB-", ")")

  def apply(doc: Document, sc: SparkContext): Summarization = {
    logger.info("Summarizing...")
    val rankedSents: RDD[RankedSent] = nlp.parseDoc(doc).andThen(nlp.summarize)(sc)
    val docWordCount = rankedSents.flatMap(_.words).count()
    val numOfSent = if (docWordCount < 750) 3 else 4

    val topRankedSents = rankedSents.take(numOfSent)
    val summWords = sc.parallelize(topRankedSents.flatMap(_.words))
    val summWordCount = summWords.count()

    rankedSents.foreach(sent => logger.debug(sent.toString))

    logger.info(s"Extracting keywords from top $numOfSent sentences...")
    val keywords: RDD[RankedWord] = nlp.extractKeywords(summWords)

    logger.info("Extracting phrases from keywords...")
    val phrase = nlp.extractPhrases(keywords)

    Summarization(docWordCount, summWordCount, topRankedSents.toVector, phrase)
  }
}

