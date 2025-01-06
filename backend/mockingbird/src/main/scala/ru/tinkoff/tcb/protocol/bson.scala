package ru.tinkoff.tcb.protocol

import scala.util.Try

import cats.data.NonEmptyVector
import oolong.bson.*
import oolong.bson.given
import org.mongodb.scala.bson.*

import ru.tinkoff.tcb.utils.circe.optics.JLens
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.string.*

object bson {
  implicit val jsonOpticBsonEncoder: BsonEncoder[JsonOptic] =
    (value: JsonOptic) => BsonString(value.path.replace('.', '⋮'))

  implicit val jsonOpticBsonDecoder: BsonDecoder[JsonOptic] =
    (value: BsonValue) =>
      Try(value.asString().getValue)
        .map(_.nonEmptyString.map(_.replace('⋮', '.')).map(JsonOptic.fromPathString).getOrElse(JLens))

  implicit val jsonOpticBsonKeyEncoder: BsonKeyEncoder[JsonOptic] = (j: JsonOptic) => j.path.replace('.', '⋮')

  implicit val jsonOpticBsonKeyDecoder: BsonKeyDecoder[JsonOptic] = (value: String) =>
    Try(value.nonEmptyString.map(_.replace('⋮', '.')).map(JsonOptic.fromPathString).getOrElse(JLens))

  implicit final def nonEmptyVectorBsonEncoder[T: BsonEncoder]: BsonEncoder[NonEmptyVector[T]] =
    BsonEncoder[Vector[T]].beforeWrite(_.toVector)

  implicit final def nonEmptyVectorBsonDecoder[T: BsonDecoder]: BsonDecoder[NonEmptyVector[T]] =
    BsonDecoder[Vector[T]].afterReadTry(v => Try(NonEmptyVector.fromVectorUnsafe(v)))
}
