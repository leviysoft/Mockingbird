package ru.tinkoff.tcb.mockingbird.api.request

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import io.circe.Encoder
import io.circe.refined.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import oolong.bson.refined.given
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*

import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.ByteArray
import ru.tinkoff.tcb.mockingbird.model.GrpcConnectionType
import ru.tinkoff.tcb.mockingbird.model.GrpcMethodDescription
import ru.tinkoff.tcb.mockingbird.model.GrpcProtoDefinition
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.utils.id.SID

final case class UpdateGrpcMethodDescriptionRequest(
    @description("Description of the method description")
    description: String,
    @description("Service name")
    service: NonEmptyString,
    @description("gRPC method")
    methodName: String,
    @description("gRPC connection type")
    connectionType: GrpcConnectionType,
    @description("Proxy url. Only relevant for proxy stubs")
    proxyUrl: Option[String],
    @description("gRPC request class")
    requestClass: String,
    @description("gRPC base64 encoded request proto")
    requestCodecs: ByteArray.Type,
    @description("gRPC response class")
    responseClass: String,
    @description("gRPC base64 encoded response proto")
    responseCodecs: ByteArray.Type
) derives Decoder,
      Encoder,
      Schema
object UpdateGrpcMethodDescriptionRequest {
  implicitly[PropSubset[GrpcMethodDescriptionPatch, GrpcMethodDescription]]
}

final case class GrpcMethodDescriptionPatch(
    id: SID[GrpcMethodDescription],
    description: String,
    service: NonEmptyString,
    methodName: String,
    connectionType: GrpcConnectionType,
    proxyUrl: Option[String],
    requestClass: String,
    requestSchema: GrpcProtoDefinition,
    responseClass: String,
    responseSchema: GrpcProtoDefinition
) derives BsonEncoder

object GrpcMethodDescriptionPatch {
  inline given QueryMeta[GrpcMethodDescriptionPatch] = queryMeta(_.id -> "_id")
}
