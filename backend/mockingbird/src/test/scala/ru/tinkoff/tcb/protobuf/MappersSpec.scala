package ru.tinkoff.tcb.protobuf

import com.github.os72.protobuf.dynamic.DynamicSchema
import zio.test.*

import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.toDynamicSchema
import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.toGrpcProtoDefinition
import ru.tinkoff.tcb.mockingbird.model.GrpcEnumSchema
import ru.tinkoff.tcb.mockingbird.model.GrpcMessageSchema
import ru.tinkoff.tcb.mockingbird.model.GrpcProtoDefinition
import ru.tinkoff.tcb.mockingbird.model.GrpcRootMessage

object MappersSpec extends ZIOSpecDefault {
  val allTypesInRequests = Set(
    ".BrandAndModelRequest",
    ".CarGenRequest",
    ".CarSearchRequest",
    ".CarConfigurationsRequest",
    ".Condition",
    ".CarPriceRequest",
    ".PreciseCarPriceRequest",
    ".PriceRequest",
    ".MemoRequest",
    ".MemoRequest.ReqEntry", // <-- It's type of the "req" field
  )

  val allTypesInNested = Set(
    ".GetStocksRequest",
    ".GetStocksResponse",
    ".GetStocksResponse.StockKinds",
    ".GetStocksResponse.Stock",
    ".GetStocksResponse.Stocks",
    ".GetStocksResponse.Event",
    ".GetStocksResponse.Event.Code",
    ".GetStocksResponse.Event.Data",
    ".GetStocksResponse.Event.Error",
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Mappers suite")(
      test("Mappers from DynamicSchema to GrpcProtoDefinition") {
        for {
          content <- Utils.getProtoDescriptionFromResource("requests.proto")
          schema          = DynamicSchema.parseFrom(content)
          protoDefinition = schema.toGrpcProtoDefinition
        } yield assertTrue(getAllTypes(protoDefinition) == allTypesInRequests)
      },
      test("Mappers from DynamicSchema to GrpcProtoDefinition and back are consistent") {
        for {
          content <- Utils.getProtoDescriptionFromResource("requests.proto")
          schema               = DynamicSchema.parseFrom(content)
          protoDefinition      = schema.toGrpcProtoDefinition
          protoDefinitionAgain = protoDefinition.toDynamicSchema.toGrpcProtoDefinition
        } yield assertTrue(protoDefinition == protoDefinitionAgain)
      },
      test("Mappers from nested DynamicSchema to GrpcProtoDefinition and back are consistent") {
        for {
          content <- Utils.getProtoDescriptionFromResource("nested.proto")
          schema               = DynamicSchema.parseFrom(content)
          protoDefinition      = schema.toGrpcProtoDefinition
          protoDefinitionAgain = protoDefinition.toDynamicSchema.toGrpcProtoDefinition
        } yield assertTrue(protoDefinition == protoDefinitionAgain)
      }
    )

  def getAllTypes(pd: GrpcProtoDefinition): Set[String] =
    pd.schemas.flatMap(getAllTypes("")).toSet

  def getAllTypes(packageName: String)(m: GrpcRootMessage): List[String] =
    m match {
      case GrpcEnumSchema(name, values) => s"$packageName.$name" :: Nil
      case GrpcMessageSchema(name, fields, oneofs, nested, nestedEnums) =>
        val npn = s"$packageName.$name"
        npn :: nested.getOrElse(Nil).flatMap(getAllTypes(npn)) ::: nestedEnums.getOrElse(Nil).flatMap(getAllTypes(npn))
    }
}
