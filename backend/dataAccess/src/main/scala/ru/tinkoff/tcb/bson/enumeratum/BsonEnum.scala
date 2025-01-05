package ru.tinkoff.tcb.bson.enumeratum

import enumeratum.*
import oolong.bson.BsonDecoder
import oolong.bson.BsonEncoder

trait BsonEnum[A <: EnumEntry] { self: Enum[A] =>
  implicit val bsonEncoder: BsonEncoder[A] =
    EnumHandler.writer(this)

  implicit val bsonDecoder: BsonDecoder[A] =
    EnumHandler.reader(this)
}
