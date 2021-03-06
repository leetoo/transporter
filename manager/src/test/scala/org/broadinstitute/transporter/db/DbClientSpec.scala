package org.broadinstitute.transporter.db

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import doobie.implicits._
import doobie.postgres.circe.Instances.JsonInstances
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.literal._
import org.broadinstitute.transporter.PostgresSpec
import org.broadinstitute.transporter.queue.{QueueRequest, QueueSchema}
import org.broadinstitute.transporter.transfer.{
  TransferRequest,
  TransferResult,
  TransferStatus,
  TransferSummary
}
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.ExecutionContext

class DbClientSpec
    extends PostgresSpec
    with EitherValues
    with OptionValues
    with JsonInstances {

  private implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private def testTransactor(password: String): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      container.driverClassName,
      container.jdbcUrl,
      container.username,
      password
    )

  private val schema = json"{}".as[QueueSchema].right.value

  behavior of "DbClient"

  it should "report ready on good configuration" in {
    val client = new DbClient.Impl(testTransactor(container.password))
    client.checkReady.unsafeRunSync() shouldBe true
  }

  it should "report not ready on bad configuration" in {
    val client = new DbClient.Impl(testTransactor("nope"))
    client.checkReady.unsafeRunSync() shouldBe false
  }

  it should "create, lookup, and delete transfer queues" in {
    val request = QueueRequest("test-queue", schema)

    val client = new DbClient.Impl(testTransactor(container.password))

    val check = for {
      res <- client.lookupQueueInfo(request.name)
      _ <- client.createQueue(request)
      res2 <- client.lookupQueueInfo(request.name)
      _ <- client.deleteQueue(request.name)
      res3 <- client.lookupQueueInfo(request.name)
    } yield {
      res shouldBe None
      val (_, _, _, schema) = res2.value
      schema shouldBe request.schema
      res3 shouldBe None
    }

    check.unsafeRunSync()
  }

  it should "fail to double-create a queue by name" in {
    val request = QueueRequest("test-queue2", schema)

    val client = new DbClient.Impl(testTransactor(container.password))

    val tryInsert = for {
      _ <- client.createQueue(request)
      _ <- client.createQueue(request)
    } yield ()

    tryInsert.attempt.unsafeRunSync().isLeft shouldBe true
  }

  it should "no-op when deleting a nonexistent queue" in {
    val client = new DbClient.Impl(testTransactor(container.password))
    client.deleteQueue("nope").unsafeRunSync()
  }

  it should "record and delete new transfer requests under a queue" in {
    val transactor = testTransactor(container.password)
    val client = new DbClient.Impl(transactor)

    val requests = TransferRequest(List.fill(10)(json"{}"))

    val countsQuery = for {
      requestCount <- sql"select count(*) from transfer_requests".query[Long].unique
      transferCount <- sql"select count(*) from transfers".query[Long].unique
    } yield {
      (requestCount, transferCount)
    }

    val checks = for {
      (queueId, _, _, _) <- client.createQueue(QueueRequest("foo", schema))
      (initReqs, initTransfers) <- countsQuery.transact(transactor)
      (requestId, _) <- client.recordTransferRequest(queueId, requests)
      (postReqs, postTransfers) <- countsQuery.transact(transactor)
      _ <- client.deleteTransferRequest(requestId)
      (finalReqs, finalTransfers) <- countsQuery.transact(transactor)
      _ <- client.deleteQueue("foo")
    } yield {
      initReqs shouldBe 0
      initTransfers shouldBe 0
      postReqs shouldBe 1
      postTransfers shouldBe 10
      finalReqs shouldBe 0
      finalTransfers shouldBe 0
    }

    checks.unsafeRunSync()
  }

  it should "update recorded status and info for transfers" in {
    val transactor = testTransactor(container.password)
    val client = new DbClient.Impl(transactor)

    val requests = TransferRequest(List.tabulate(10)(i => json"""{ "i": $i }"""))
    val results =
      List.tabulate(10) { i =>
        TransferSummary(
          // Success: 0, 3, 6, 9
          // TransientFailure: 1, 4, 7
          // FatalFailure: 2, 5, 8
          TransferResult.values(i % 3),
          // Success: 0+1, 6+1
          // TransientFailure: 4+1
          // FatalFailure: 2+1, 8+1
          Some(json"""{ "i+1": ${i + 1} }""").filter(_ => i % 2 == 0)
        )
      }

    val checks = for {
      (queueId, _, _, _) <- client.createQueue(QueueRequest("foo", schema))
      (reqId, reqs) <- client.recordTransferRequest(queueId, requests)
      preResults <- client.lookupTransfers(queueId, reqId)
      _ <- client.updateTransfers(reqs.map(_._1).zip(results))
      postResults <- client.lookupTransfers(queueId, reqId)
      _ <- client.deleteQueue("foo")
    } yield {
      preResults shouldBe Map((TransferStatus.Submitted, (10, Vector.empty[Json])))
      postResults.keys should contain only (TransferStatus.Succeeded, TransferStatus.Retrying, TransferStatus.Failed)

      val (successCount, successInfo) = postResults(TransferStatus.Succeeded)
      val (retryCount, retryInfo) = postResults(TransferStatus.Retrying)
      val (failCount, failInfo) = postResults(TransferStatus.Failed)

      successCount shouldBe 4
      retryCount shouldBe 3
      failCount shouldBe 3

      successInfo should contain only (json"""{ "i+1": 1 }""", json"""{ "i+1": 7 }""")
      retryInfo should contain only json"""{ "i+1": 5 }"""
      failInfo should contain only (json"""{ "i+1": 3 }""", json"""{ "i+1": 9 }""")
    }

    checks.unsafeRunSync()
  }

  it should "get info needed to resubmit transient failures" in {
    val transactor = testTransactor(container.password)
    val client = new DbClient.Impl(transactor)

    val transfers = List.tabulate(10)(i => json"""{ "i": $i }""")
    val queues = List("foo", "bar", "baz")
    val requests =
      transfers
        .grouped((transfers.length / queues.length) + 1)
        .toList
        .mapWithIndex {
          case (ts, i) => queues(i) -> TransferRequest(ts)
        }

    val checks = for {
      queueInfo <- queues.traverse { q =>
        client.createQueue(QueueRequest(q, schema)).map {
          case (id, reqTopic, _, _) => (q, (id, reqTopic))
        }
      }.map(_.toMap)
      transferIdsToSubmitInfo <- requests.flatTraverse {
        case (queue, req) =>
          val (queueId, reqTopic) = queueInfo(queue)
          client.recordTransferRequest(queueId, req).map {
            case (_, transfersWithId) =>
              transfersWithId.map {
                case (id, json) => (id, (reqTopic, json))
              }
          }
      }
      resubmitIds <- NonEmptyList
        .fromList(transferIdsToSubmitInfo)
        .fold(IO.raiseError[NonEmptyList[UUID]](new IllegalStateException("???"))) {
          ids =>
            IO.pure(ids.map(_._1))
        }
      resubmitInfo <- client.getResubmitInfoForTransfers(resubmitIds)
      _ <- queues.traverse_(client.deleteQueue)
    } yield {
      val infoById = resubmitInfo.map {
        case (id, reqTopic, json) => (id, (reqTopic, json))
      }

      infoById should contain theSameElementsAs transferIdsToSubmitInfo
    }

    checks.unsafeRunSync()
  }

}
