package ru.tinkoff.tcb.bson

import oolong.bson.*
import oolong.bson.given
import org.mongodb.scala.bson.BsonDocument
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final case class TestEntity(_id: String, name: String, externalKey: Option[Int]) derives BsonDecoder, BsonEncoder

class PatchGeneratorSpec extends AnyFunSuite with Matchers {
  test("Generate update with Some") {
    val entity = TestEntity("42", "name", Some(442))

    val (_, patch) = PatchGenerator.mkPatch(entity)

    patch shouldBe BsonDocument(
      "$set" -> BsonDocument(
        "name"        -> "name",
        "externalKey" -> 442
      )
    )
  }

  test("Generate update with None") {
    val entity = TestEntity("42", "name", None)

    val (_, patch) = PatchGenerator.mkPatch(entity)

    patch shouldBe BsonDocument(
      "$set" -> BsonDocument(
        "name" -> "name"
      ),
      "$unset" -> BsonDocument(
        "externalKey" -> ""
      )
    )
  }
}
