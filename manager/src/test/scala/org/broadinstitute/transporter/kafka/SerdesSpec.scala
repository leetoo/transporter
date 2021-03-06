package org.broadinstitute.transporter.kafka

import java.util.UUID

import io.circe.{Decoder, Encoder}
import io.circe.derivation.{deriveDecoder, deriveEncoder}
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class SerdesSpec extends FlatSpec with Matchers with EitherValues {
  import SerdesSpec._

  behavior of "Serdes"

  it should "round-trip (de)serialization" in {

    val foo1 = Foo(Some("thing"), 0, Nil)
    val foo2 = Foo(None, -12312234, List.fill(3)(UUID.randomUUID()))
    val foo3 = Foo(
      Some("dfk;sajflk;adsjfsafdsfsadfdsafads"),
      Int.MaxValue,
      List.fill(100)(UUID.randomUUID())
    )

    List(foo1, foo2, foo3).foreach { foo =>
      val serialized = Serdes.encodingSerializer[Foo].serialize("the-topic", foo)
      val deserialized =
        Serdes.decodingDeserializer[Foo].deserialize("the-topic", serialized)

      deserialized.right.value shouldBe foo
    }
  }
}

object SerdesSpec {
  case class Foo(a: Option[String], b: Int, c: List[UUID])
  implicit val decoder: Decoder[Foo] = deriveDecoder
  implicit val encoder: Encoder[Foo] = deriveEncoder
}
