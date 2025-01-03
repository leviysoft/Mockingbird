package ru.tinkoff.tcb.utils

import scala.util.Try
import scala.xml.Elem
import scala.xml.Node

import io.circe.Decoder
import io.circe.Encoder
import neotype.*
import oolong.bson.*
import oolong.bson.given
import sttp.tapir.Schema

import ru.tinkoff.tcb.generic.RootOptionFields

package object xml {
  val emptyNode: Elem = <empty/>

  object XMLString extends Newtype[Node] {
    def fromString(xml: String): Try[XMLString.Type] =
      Try(SafeXML.loadString(xml).asInstanceOf[Node]).map(this.unsafeMake)

    def fromNode(node: Node): XMLString.Type = this.unsafeMake(node)

    def unapply(str: String): Option[XMLString.Type] =
      fromString(str).toOption

    implicit val xmlStringDecoder: Decoder[XMLString.Type] =
      Decoder.decodeString.emapTry(fromString)

    implicit val xmlStringEncoder: Encoder[XMLString.Type] =
      Encoder.encodeString.contramap[XMLString.Type](_.asString)

    implicit val xmlStringBsonDecoder: BsonDecoder[XMLString.Type] =
      BsonDecoder[String].afterReadTry(fromString)

    implicit val xmlStringBsonEncoder: BsonEncoder[XMLString.Type] =
      BsonEncoder[String].beforeWrite(_.asString)

    implicit val xmlStringSchema: Schema[XMLString.Type] =
      Schema.schemaForString.as[XMLString.Type]

    implicit val xmlStringRof: RootOptionFields[XMLString.Type] =
      RootOptionFields.mk(Set.empty)
  }

  implicit class XMLStringSyntax(private val self: XMLString.Type) extends AnyVal {
    def asString: String = self.unwrap.toString
  }
}
