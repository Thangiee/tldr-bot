package com.thangiee.tldr

import org.apache.spark.{SparkConf, SparkContext}
import org.clulab.processors.corenlp.CoreNLPProcessor

object Example {
  def main(args: Array[String]): Unit = {
    import better.files._

    val conf = new SparkConf()
      .setAppName("TL;DR")
      .setMaster("local[4]")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    val sc = new SparkContext(conf)
//    sc.setLogLevel("ERROR")

    val proc = new CoreNLPProcessor(withDiscourse = true)

    val files = Seq(
      "./example/articles/1.txt",
      "./example/articles/2.txt",
      "./example/articles/3.txt",
      "./example/articles/4.txt",
      "./example/articles/5.txt",
      "./example/articles/6.txt",
      "./example/articles/7.txt",
      "./example/articles/8.txt",
      "./example/articles/9.txt",
      "./example/articles/10.txt"
    ).map(_.toFile)

    files.foreach { file =>
      println(s"Processing ${file.name}...")
      val doc = proc.annotate(file.contentAsString)
      val summ = Summarization(doc, sc)
      val header = s"${summ.summWordCount} of ${summ.docWordCount} words (${summ.summReducePct}% less)\n\n"

      s"./example/phrases/${file.name}".toFile.overwrite(summ.phrases.map(p => (p.score, p.text)).distinct.mkString("\n"))
      s"./example/summaries/${file.name}".toFile.overwrite(header + summ.chronologicalSentsText.mkString("\n"))
    }
  }
}
