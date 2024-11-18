package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant

import derevo.circe.decoder
import derevo.circe.encoder
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.refined.*
import io.scalaland.chimney.dsl.TransformationOps
import sttp.tapir.codec.refined.*
import sttp.tapir.derevo.schema

import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID

@derive(encoder, decoder, schema)
final case class GrpcStubView(
    id: SID[GrpcStub],
    scope: Scope,
    created: Instant,
    service: NonEmptyString,
    times: Option[NonNegInt],
    methodName: String,
    name: NonEmptyString,
    requestSchema: GrpcProtoDefinition,
    requestClass: String,
    responseSchema: GrpcProtoDefinition,
    responseClass: String,
    response: GrpcStubResponse,
    seed: Option[Json],
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    requestPredicates: JsonPredicate,
    persist: Option[Map[JsonOptic, Json]],
    labels: Seq[String]
)

object GrpcStubView {
  def makeFrom(stub: GrpcStub, description: GrpcMethodDescription): GrpcStubView =
    stub
      .into[GrpcStubView]
      .withFieldConst(_.service, description.service)
      .withFieldConst(_.methodName, description.methodName)
      .withFieldConst(_.requestSchema, description.requestSchema)
      .withFieldConst(_.requestClass, description.requestClass)
      .withFieldConst(_.responseSchema, description.responseSchema)
      .withFieldConst(_.responseClass, description.responseClass)
      .transform
}
