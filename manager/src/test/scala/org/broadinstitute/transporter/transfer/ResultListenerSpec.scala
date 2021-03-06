package org.broadinstitute.transporter.transfer

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import io.circe.Json
import io.circe.literal._
import org.broadinstitute.transporter.db.DbClient
import org.broadinstitute.transporter.kafka.{KafkaConsumer, KafkaProducer}
import org.broadinstitute.transporter.transfer
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext

class ResultListenerSpec extends FlatSpec with Matchers with MockFactory {

  private val db = mock[DbClient]
  private val consumer = mock[KafkaConsumer[UUID, TransferSummary]]
  private val producer = mock[KafkaProducer[UUID, Json]]

  private implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private def listener = new transfer.ResultListener.Impl(consumer, producer, db)

  behavior of "ResultListener"

  it should "record successes and fatal failures" in {
    val results = List(
      TransferSummary(TransferResult.Success, Some(json"{}")),
      TransferSummary(TransferResult.FatalFailure, None),
      TransferSummary(TransferResult.Success, None),
      TransferSummary(TransferResult.FatalFailure, Some(json"[]"))
    ).map(s => UUID.randomUUID() -> s)

    (db.updateTransfers _).expects(results).returning(IO.unit)

    listener.processBatch(results.map(Right(_))).unsafeRunSync()
  }

  it should "record and resubmit transient failures" in {
    val results = List(
      TransferSummary(TransferResult.Success, Some(json"{}")),
      TransferSummary(TransferResult.FatalFailure, None),
      TransferSummary(TransferResult.TransientFailure, Some(json"1")),
      TransferSummary(TransferResult.Success, None),
      TransferSummary(TransferResult.FatalFailure, Some(json"[]")),
      TransferSummary(TransferResult.TransientFailure, None)
    ).map(s => UUID.randomUUID() -> s)

    val resubmitIds = NonEmptyList.of(results(2)._1, results(5)._1)
    val resubmitInfo = resubmitIds.map(id => (id, s"queue.$id", json"{}")).toList

    (db.updateTransfers _).expects(results).returning(IO.unit)
    (db.getResubmitInfoForTransfers _)
      .expects(resubmitIds)
      .returning(IO.pure(resubmitInfo))
    resubmitInfo.foreach {
      case (id, topic, message) =>
        (producer.submit _).expects(topic, List(id -> message)).returning(IO.unit)
    }

    listener.processBatch(results.map(Right(_))).unsafeRunSync()
  }

  it should "batch resubmissions by topic" in {
    val results = List(
      TransferSummary(TransferResult.Success, Some(json"{}")),
      TransferSummary(TransferResult.FatalFailure, None),
      TransferSummary(TransferResult.TransientFailure, Some(json"1")),
      TransferSummary(TransferResult.Success, None),
      TransferSummary(TransferResult.FatalFailure, Some(json"[]")),
      TransferSummary(TransferResult.TransientFailure, None)
    ).map(s => UUID.randomUUID() -> s)

    val resubmitIds = NonEmptyList.of(results(2)._1, results(5)._1)
    val resubmitInfo = resubmitIds.map(id => (id, "queue", json"{}")).toList

    (db.updateTransfers _).expects(results).returning(IO.unit)
    (db.getResubmitInfoForTransfers _)
      .expects(resubmitIds)
      .returning(IO.pure(resubmitInfo))
    (producer.submit _)
      .expects("queue", resubmitInfo.map(info => info._1 -> info._3))
      .returning(IO.unit)

    listener.processBatch(results.map(Right(_))).unsafeRunSync()
  }

  it should "not crash if Kafka receives malformed data" in {
    val results = List(
      TransferSummary(TransferResult.Success, Some(json"{}")),
      TransferSummary(TransferResult.FatalFailure, None),
      TransferSummary(TransferResult.Success, None),
      TransferSummary(TransferResult.FatalFailure, Some(json"[]"))
    ).map(s => UUID.randomUUID() -> s)

    val batch = List.concat(
      List(Left(new IllegalStateException("WAT"))),
      results.map(Right(_)),
      List(Left(new IllegalStateException("WOT")))
    )

    (db.updateTransfers _).expects(results).returning(IO.unit)

    listener.processBatch(batch).unsafeRunSync()
  }
}
