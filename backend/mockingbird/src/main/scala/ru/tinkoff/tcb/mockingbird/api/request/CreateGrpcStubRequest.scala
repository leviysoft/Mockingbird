package ru.tinkoff.tcb.mockingbird.api.request

import eu.timepit.refined.*
import eu.timepit.refined.numeric.*
import eu.timepit.refined.types.numeric.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.derivation.ConfiguredDecoder
import io.circe.derivation.ConfiguredEncoder
import io.circe.refined.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*

import ru.tinkoff.tcb.mockingbird.model.ByteArray
import ru.tinkoff.tcb.mockingbird.model.GrpcStubResponse
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.protocol.json.given
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic

final case class CreateGrpcStubRequest(
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[NonNegInt] = Some(refineV[NonNegative].unsafeFrom(1)),
    @description("Service name")
    service: String,
    @description("gRPC base64 encoded request proto")
    requestCodecs: ByteArray.Type,
    @description("gRPC base64 encoded response proto")
    responseCodecs: ByteArray.Type,
    @description("gRPC request class")
    requestClass: String,
    @description("gRPC response class")
    responseClass: String,
    @description("gRPC method")
    methodName: String,
    @description("Mock name")
    name: NonEmptyString,
    @description("Response specification")
    response: GrpcStubResponse,
    @description("Json request predicates")
    requestPredicates: JsonPredicate,
    @description("State search predicate")
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    @description("Random value generation specification")
    seed: Option[Json],
    @description("Persisted data")
    persist: Option[Map[JsonOptic, Json]],
    @description("Tags")
    labels: Seq[String] = Seq.empty
) derives ConfiguredDecoder,
      ConfiguredEncoder,
      Schema
