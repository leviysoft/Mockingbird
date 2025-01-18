package ru.tinkoff.tcb.xpath

import scala.util.Try

import advxml.transform.XmlZoom
import advxml.xpath.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.KeyDecoder
import io.circe.KeyEncoder
import org.mongodb.scala.bson.BsonString
import sttp.tapir.Schema

import oolong.bson.*
import oolong.bson.given

/*
 * toZoom inside extra braces is important. It excludes this value from hash and equals method.
 */
final case class SXpath(raw: String)(val toZoom: XmlZoom) {
  override def toString: String = raw
}

object SXpath {
  def fromString(pathStr: String): Try[SXpath] =
    XmlZoom
      .fromXPath(pathStr)
      .toEither
      .leftMap(errs => new Exception(errs.toList.mkString(",")))
      .toTry
      .map(zoom => SXpath(pathStr)(zoom))

  def unapply(str: String): Option[SXpath] =
    fromString(str).toOption

  implicit val sxpathDecoder: Decoder[SXpath] =
    Decoder.decodeString.emapTry(fromString)

  implicit val sxpathEncoder: Encoder[SXpath] =
    Encoder.encodeString.contramap(_.raw)

  implicit val sxpathKeyDecoder: KeyDecoder[SXpath] = (key: String) => fromString(key).toOption

  implicit val sxpathKeyEncoder: KeyEncoder[SXpath] = _.raw

  implicit val sxpathBsonDecoder: BsonDecoder[SXpath] =
    BsonDecoder[String].afterReadTry(fromString)

  implicit val sxpathBsonEncoder: BsonEncoder[SXpath] =
    BsonEncoder[String].beforeWrite(_.raw)

  implicit val sxpathBsonKeyDecoder: BsonKeyDecoder[SXpath] =
    (value: String) => sxpathBsonDecoder.fromBson(BsonString(value))

  implicit val sxpathBsonKeyEncoder: BsonKeyEncoder[SXpath] = _.raw

  implicit val sxpathSchema: Schema[SXpath] =
    Schema.schemaForString.as[SXpath]
}
