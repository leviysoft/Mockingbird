package ru.tinkoff.tcb.predicatedsl.xml

import scala.xml.NodeSeq

import cats.data.NonEmptyList
import cats.data.Validated
import cats.data.ValidatedNel
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.parser.parse
import oolong.bson.*
import oolong.bson.given
import org.bson.BsonInvalidOperationException
import sttp.tapir.Schema

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.generic.RootOptionFields
import ru.tinkoff.tcb.instances.predicate.and.*
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.Keyword.*
import ru.tinkoff.tcb.predicatedsl.PredicateConstructionError
import ru.tinkoff.tcb.predicatedsl.SpecificationError
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.json.JObject
import ru.tinkoff.tcb.xpath.SXpath

abstract class XmlPredicate extends (NodeSeq => Boolean) {
  def definition: XmlPredicate.Spec

  override def hashCode(): Int = definition.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case xp: XmlPredicate => xp.definition == definition
    case _                => false
  }
}

object XmlPredicate {
  type Spec = Map[SXpath, Map[Keyword.Xml, Json]]

  implicit val xmlPredicateDecoder: Decoder[XmlPredicate] =
    Decoder[Spec].emap(apply(_).toEither.leftMap(_.toList.mkString(", ")))

  implicit val xmlPredicateEncoder: Encoder[XmlPredicate] =
    Encoder[Spec].contramap(_.definition)

  implicit val xmlPredicateSchema: Schema[XmlPredicate] =
    implicitly[Schema[Spec]].as[XmlPredicate]

  implicit val xmlPredicateBsonDecoder: BsonDecoder[XmlPredicate] =
    BsonDecoder[Spec].afterReadTry(
      apply(_).toEither.leftMap(errs => new BsonInvalidOperationException(errs.toList.mkString(", "))).toTry
    )

  implicit val xmlPredicateBsonEncoder: BsonEncoder[XmlPredicate] =
    BsonEncoder[Spec].beforeWrite(_.definition)

  implicit val xmlPredicateRootOptionFields: RootOptionFields[XmlPredicate] =
    RootOptionFields.mk[XmlPredicate](RootOptionFields[Spec].fields, RootOptionFields[Spec].isOptionItself)

  /**
   * @param description
   *   Looks like: {"/xpath": <predicate description>]
   * @return
   */
  def apply(
      description: Spec
  ): ValidatedNel[PredicateConstructionError, XmlPredicate] =
    description.toVector
      .map { case (xPath, spec) =>
        spec.toVector
          .map(mkPredicate.tupled)
          .reduceOption(_ |+| _)
          .getOrElse(Validated.valid((_: Option[NodeSeq]) => true))
          .leftMap(errors => NonEmptyList.one(SpecificationError(xPath.raw, errors)))
          .map(pred => (n: NodeSeq) => xPath.toZoom.bind(n).run[Option].pipe(pred))
      }
      .reduceOption(_ |+| _)
      .getOrElse(Validated.valid((_: NodeSeq) => true))
      .map(f =>
        new XmlPredicate {
          override val definition: Map[SXpath, Map[Keyword.Xml, Json]] = description

          override def apply(xml: NodeSeq): Boolean = f(xml)
        }
      )

  private val mkPredicate: (Keyword.Xml, Json) => ValidatedNel[(Keyword, Json), Option[NodeSeq] => Boolean] =
    (kwd, jv) =>
      (kwd, jv) match {
        case (Equals, JsonString(str)) =>
          Validated.valid(_.exists(_.text == str))
        case (Equals, JsonNumber(jnum)) =>
          Validated.valid(_.exists(n => jnum.toBigDecimal.contains(BigDecimal(n.text))))
        case (NotEq, JsonString(str)) =>
          Validated.valid(_.exists(_.text != str))
        case (NotEq, JsonNumber(jnum)) =>
          Validated.valid(_.exists(n => !jnum.toBigDecimal.contains(BigDecimal(n.text))))
        case (Greater, JsonNumber(jnum)) =>
          Validated.valid(_.exists(n => jnum.toBigDecimal.exists(_ < BigDecimal(n.text))))
        case (Gte, JsonNumber(jnum)) =>
          Validated.valid(_.exists(n => jnum.toBigDecimal.exists(_ <= BigDecimal(n.text))))
        case (Less, JsonNumber(jnum)) =>
          Validated.valid(_.exists(n => jnum.toBigDecimal.exists(_ > BigDecimal(n.text))))
        case (Lte, JsonNumber(jnum)) =>
          Validated.valid(_.exists(n => jnum.toBigDecimal.exists(_ >= BigDecimal(n.text))))
        case (Rx, JsonString(str)) =>
          Validated.valid(_.exists(_.text.matches(str)))
        case (Size, JsonNumber(jnum)) if jnum.toInt.isDefined =>
          Validated.valid(_.exists(_.text.length == jnum.toInt.get))
        case (Exists, JsonBoolean(true))  => Validated.valid(_.isDefined)
        case (Exists, JsonBoolean(false)) => Validated.valid(_.isEmpty)
        case (Cdata, JsonDocument(JObject("==" -> JsonString(value)))) =>
          Validated.valid(_.exists(_.text == value))
        case (Cdata, JsonDocument(JObject("~=" -> JsonString(value)))) =>
          Validated.valid(_.exists(_.text.matches(value)))
        case (JCdata, spec) =>
          Validated
            .fromEither(for {
              jspec <- spec
                .as[Map[JsonOptic, Map[Keyword.Json, Json]]]
                .leftMap(_ => (kwd.asInstanceOf[Keyword] -> spec))
              jpred <- JsonPredicate(jspec).toEither.leftMap(errs =>
                (kwd.asInstanceOf[Keyword] -> Json.fromString(errs.toList.mkString(", ")))
              )
            } yield jpred)
            .leftMap(NonEmptyList.one)
            .map(jpred =>
              (op: Option[NodeSeq]) => op.map(_.text.trim).flatMap(parse(_).toOption).map(jpred).getOrElse(false)
            )
        case (kwd, j) => Validated.invalidNel(kwd -> j)
      }
}
