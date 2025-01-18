package ru.tinkoff.tcb.bson.enumeratum.values

import enumeratum.values.*
import oolong.bson.*
import org.mongodb.scala.bson.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

trait EnumBsonHandlerHelpers { this: AnyFunSpec with Matchers =>
  def testWriter[EntryType <: ValueEnumEntry[ValueType], ValueType](
      enumKind: String,
      `enum`: ValueEnum[ValueType, EntryType],
      providedWriter: Option[BsonEncoder[EntryType]] = None
  )(implicit baseHandler: BsonEncoder[ValueType]): Unit = {
    implicit val writer: BsonEncoder[EntryType] = providedWriter.getOrElse(EnumHandler.writer(`enum`))
    describe(enumKind) {
      it("should write proper BSONValue") {
        `enum`.values.foreach(entry => entry.bson shouldBe entry.value.bson)
      }
    }
  }

  def testReader[EntryType <: ValueEnumEntry[ValueType], ValueType](
      enumKind: String,
      `enum`: ValueEnum[ValueType, EntryType],
      providedReader: Option[BsonDecoder[EntryType]] = None
  )(implicit baseEncoder: BsonEncoder[ValueType], baseDecoder: BsonDecoder[ValueType]): Unit = {
    val reader = providedReader.getOrElse(EnumHandler.reader(`enum`))
    describe(enumKind) {
      it("should read valid values") {
        `enum`.values.foreach(entry => reader.fromBson(entry.value.bson).get shouldBe entry)
      }
      it("should fail to read with invalid values") {
        reader.fromBson(BsonInt32(Int.MaxValue)) shouldBe Symbol("failure")
        reader.fromBson(BsonString("boon")) shouldBe Symbol("failure")
      }
    }
  }
}
