package org.broadinstitute.transporter

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect._
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.apache.kafka.streams.KafkaStreams
import org.broadinstitute.transporter.kafka.{KStreamsConfig, TransferStream}
import org.broadinstitute.transporter.queue.{Queue, QueueConfig}
import org.broadinstitute.transporter.transfer.TransferRunner
import org.http4s.Request
import org.http4s.circe.CirceEntityDecoder
import org.http4s.client.blaze.BlazeClientBuilder
import pureconfig.ConfigReader
import pureconfig.module.catseffect._

import scala.concurrent.ExecutionContext

/**
  * Main entry-point for Transporter agent programs.
  *
  * Extending this base class should provide concrete agent programs with
  * the full framework needed to hook into Transporter's Kafka infrastructure
  * and run data transfers.
  */
abstract class TransporterAgent[RC: ConfigReader]
    extends IOApp.WithContext
    with CirceEntityDecoder {

  private val logger = Slf4jLogger.getLogger[IO]

  // Use a single thread for the streams run loop.
  // Kafka Streams is an inherently synchronous API, so there's no point in
  // keeping multiple threads around.
  override val executionContextResource: Resource[SyncIO, ExecutionContext] = {
    val allocate = SyncIO(Executors.newSingleThreadExecutor())
    val free = (es: ExecutorService) => SyncIO(es.shutdown())
    Resource.make(allocate)(free).map(ExecutionContext.fromExecutor)
  }

  /**
    * Construct the agent component which can actually run data transfers.
    *
    * Modeled as a `Resource` so agent programs can hook in setup / teardown
    * logic for config, thread pools, etc.
    */
  def runnerResource(config: RC): Resource[IO, TransferRunner[RC]]

  /** [[IOApp]] equivalent of `main`. */
  final override def run(args: List[String]): IO[ExitCode] =
    loadConfigF[IO, AgentConfig[RC]]("org.broadinstitute.transporter").flatMap { config =>
      for {
        maybeQueue <- lookupQueue(config.queue)
        retCode <- maybeQueue match {
          case Some(queue) =>
            runnerResource(config.runnerConfig).use { runner =>
              runStream(queue, runner, config.kafka)
            }
          case None =>
            logger.error(s"No such queue: ${config.queue.queueName}").as(ExitCode.Error)
        }
      } yield {
        retCode
      }
    }

  /** Query the configured Transporter manager for information about a queue. */
  private def lookupQueue(config: QueueConfig): IO[Option[Queue]] =
    BlazeClientBuilder[IO](executionContext).resource.use { client =>
      val request = Request[IO](
        uri = config.managerUri / "api" / "transporter" / "v1" / "queues" / config.queueName
      )
      logger
        .info(
          s"Getting Kafka topics for queue '${config.queueName}' from Transporter at ${config.managerUri}"
        )
        .flatMap(_ => client.expectOption[Queue](request))
    }

  /**
    * Build and launch the Kafka stream which receives, processes, and reports
    * on data transfer requests.
    *
    * This method should only return if the underlying stream is interrupted
    * (i.e. by a Ctrl-C).
    */
  private def runStream(
    queue: Queue,
    runner: TransferRunner[RC],
    kafkaConfig: KStreamsConfig
  ): IO[ExitCode] = {
    val topology = TransferStream.build(queue, runner)
    for {
      stream <- IO.delay(new KafkaStreams(topology, kafkaConfig.asProperties))
      _ <- IO.delay(stream.start())
      _ <- IO.cancelable[Unit](_ => IO.delay(stream.close()))
    } yield {
      ExitCode.Success
    }
  }
}
