package ru.tinkoff.tcb.protobuf

import com.github.os72.protobuf.dynamic.DynamicSchema
import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.util.JsonFormat
import io.circe.*
import zio.test.*
import zio.test.Assertion.*

import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.toGrpcProtoDefinition
import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.toDynamicSchema

object SerializationOptionalFieldsSpec extends ZIOSpecDefault {
  val msgOptionalFieldAbsent          = Array[Byte](0x0a, 0x04, 0x31, 0x71, 0x77, 0x65)
  val msgOptionalFieldHasDefaultValue = Array[Byte](0x0a, 0x04, 0x31, 0x71, 0x77, 0x65, 0x18, 0x00)
  val msgOptionalFieldHasAnotherValue = Array[Byte](0x0a, 0x04, 0x31, 0x71, 0x77, 0x65, 0x18, 0x01)
  val typeName                        = "Foo"
  val printer = JsonFormat.printer().includingDefaultValueFields().preservingProtoFieldNames().sortingMapKeys()

  val optionalSyntax2    = "optional_proto2.proto"
  val notOptionalSyntax2 = "not_optional_proto2.proto"
  val optionalSyntax3    = "optional_proto3.proto"
  val notOptionalSyntax3 = "not_optional_proto3.proto"

  val field1Val        = "1qwe"
  val field2DefaultVal = "BAR_ZERO"
  val field2AnotherVal = "BAR_ONE"

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Serialization of optional fields suite")(
      test("An optional field in proto2 syntax: the field is absent") {
        for {
          msg    <- parseWithProtoFromResource(optionalSyntax2, msgOptionalFieldAbsent)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2DefaultVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An optional field in proto2 syntax: the field has default value") {
        for {
          msg    <- parseWithProtoFromResource(optionalSyntax2, msgOptionalFieldHasDefaultValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2DefaultVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An optional field in proto2 syntax: the field has another value") {
        for {
          msg    <- parseWithProtoFromResource(optionalSyntax2, msgOptionalFieldHasAnotherValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2AnotherVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An required field in proto2 syntax: the field is absent") {
        for {
          result <- parseWithProtoFromResource(notOptionalSyntax2, msgOptionalFieldAbsent).exit
        } yield assert(result)(failsWithA[InvalidProtocolBufferException])
      },
      test("An required field in proto2 syntax: the field has default value") {
        for {
          msg    <- parseWithProtoFromResource(notOptionalSyntax2, msgOptionalFieldHasDefaultValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2DefaultVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An required field in proto2 syntax: the field has another value") {
        for {
          msg    <- parseWithProtoFromResource(notOptionalSyntax2, msgOptionalFieldHasAnotherValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2AnotherVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An optional field in proto3 syntax: the field is absent") {
        for {
          msg    <- parseWithProtoFromResource(optionalSyntax3, msgOptionalFieldAbsent)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An optional field in proto3 syntax: the field has default value") {
        for {
          msg    <- parseWithProtoFromResource(optionalSyntax3, msgOptionalFieldHasDefaultValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2DefaultVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An optional field in proto3 syntax: the field has another value") {
        for {
          msg    <- parseWithProtoFromResource(optionalSyntax3, msgOptionalFieldHasAnotherValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2AnotherVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An regular field in proto3 syntax: the field is absent") {
        for {
          msg    <- parseWithProtoFromResource(notOptionalSyntax3, msgOptionalFieldAbsent)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2DefaultVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An regular field in proto3 syntax: the field has default value") {
        for {
          msg    <- parseWithProtoFromResource(notOptionalSyntax3, msgOptionalFieldHasDefaultValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2DefaultVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
      test("An regular field in proto3 syntax: the field has another value") {
        for {
          msg    <- parseWithProtoFromResource(notOptionalSyntax3, msgOptionalFieldHasAnotherValue)
          obtain <- parseJson(printer.print(msg))
          expected <- parseJson(s"""{
               |  "field1": "$field1Val",
               |  "field2": "$field2AnotherVal"
               |}""".stripMargin)
        } yield assertTrue(obtain == expected)
      },
    )

  def parseWithProtoFromResource(protoName: String, rawData: Array[Byte]) =
    for {
      schema <- getSchemaFromResource(protoName)
      desc = schema.getMessageDescriptor(typeName)
      result <- ZIO.attempt(DynamicMessage.parseFrom(desc, rawData))
    } yield result

  def getSchemaFromResource(name: String) =
    for {
      bytes <- Utils.getProtoDescriptionFromResource(name)
      // We are checking reconstructed schema
      schema <- ZIO.attempt(DynamicSchema.parseFrom(bytes).toGrpcProtoDefinition.toDynamicSchema)
    } yield schema

  def parseJson(js: String): Task[Json] =
    ZIO.fromEither(parser.parse(js))
}
