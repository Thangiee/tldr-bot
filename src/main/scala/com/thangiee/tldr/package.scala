package com.thangiee

import akka.stream.scaladsl.{Flow, Source}
import cats.{Eval, Foldable, Functor}
import cats.implicits._
import com.typesafe.scalalogging.Logger
import org.apache.spark.rdd.RDD
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag

package object tldr {
  val logger = Logger(LoggerFactory.getLogger("tldr-bot"))

  implicit val iterableInst = new Foldable[Iterable] {
    def foldLeft[A, B](fa: Iterable[A], b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
    def foldRight[A, B](fa: Iterable[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = fa.foldRight(lb)(f)
  }

  implicit class RDDOps[A: ClassTag](val rdd: RDD[A]) {
    def distinctBy[K: ClassTag](f: A => K): RDD[A] = rdd.groupBy(f).map(_._2.head)
  }

  implicit class SrcLogging[+A, +B](val src: Source[A, B]) extends AnyVal {
    def logInfo(f: A => String): Source[A, B] = src.map { a => logger.info(f(a)); a }
  }

  implicit class FlowLogging[-A, +B, +C](val flow: Flow[A, B, C]) extends AnyVal {
    def logInfo(f: B => String): Flow[A, B, C] = flow.map { b => logger.info(f(b)); b }
  }

}
