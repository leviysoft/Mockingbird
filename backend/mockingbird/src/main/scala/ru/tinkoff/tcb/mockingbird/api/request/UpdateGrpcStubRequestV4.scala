package ru.tinkoff.tcb.mockingbird.api.request

import eu.timepit.refined.numeric.*
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.derivation.ConfiguredDecoder
import io.circe.derivation.ConfiguredEncoder
import io.circe.refined.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import oolong.bson.refined.given
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.GrpcMethodDescription
import ru.tinkoff.tcb.mockingbird.model.GrpcStub
import ru.tinkoff.tcb.mockingbird.model.GrpcStubResponse
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.json.given
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID

final case class UpdateGrpcStubRequestV4(
    @description("gRPC method description id")
    methodDescriptionId: SID[GrpcMethodDescription],
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[NonNegInt] = Some(refineV[NonNegative].unsafeFrom(1)),
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
object UpdateGrpcGrpcStubRequestV4 {
  implicitly[PropSubset[GrpcStubPatch, GrpcStub]]
}

final case class GrpcStubPatch(
    id: SID[GrpcStub],
    methodDescriptionId: SID[GrpcMethodDescription],
    scope: Scope,
    times: Option[NonNegInt],
    name: NonEmptyString,
    response: GrpcStubResponse,
    seed: Option[Json],
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    requestPredicates: JsonPredicate,
    persist: Option[Map[JsonOptic, Json]],
    labels: Seq[String]
) derives BsonEncoder

object GrpcStubPatch {
  inline given QueryMeta[GrpcStubPatch] = queryMeta(_.id -> "_id")
}
