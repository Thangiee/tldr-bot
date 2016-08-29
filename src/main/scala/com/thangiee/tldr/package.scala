package com.thangiee

import cats.{Eval, Foldable}
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
}
