package org.broadinstitute.transporter.transfer

import java.util.UUID

import io.circe.Encoder

/**
  * Response generated by the successful submission of a batch of transfer requests.
  *
  * @param id unique ID for the transfer batch which can be used for status queries
  */
case class TransferAck(id: UUID)

object TransferAck {
  implicit val encoder: Encoder[TransferAck] = io.circe.derivation.deriveEncoder
}
