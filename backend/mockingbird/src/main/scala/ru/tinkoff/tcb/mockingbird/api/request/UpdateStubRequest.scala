package ru.tinkoff.tcb.mockingbird.api.request

import scala.util.matching.Regex

import io.circe.Decoder
import io.circe.Encoder
import eu.timepit.refined.*
import eu.timepit.refined.numeric.*
import eu.timepit.refined.types.numeric.NonNegInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.refined.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.refined.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*
import sttp.tapir.Schema

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.Callback
import ru.tinkoff.tcb.mockingbird.model.HttpMethod
import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mockingbird.model.HttpStubRequest
import ru.tinkoff.tcb.mockingbird.model.HttpStubResponse
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID

final case class UpdateStubRequest(
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[NonNegInt] = Some(refineV[NonNegative].unsafeFrom(1)),
    @description("Mock name")
    name: NonEmptyString,
    @description("HTTP method")
    method: HttpMethod,
    @description("The path suffix where the mock triggers")
    path: Option[NonEmptyString],
    pathPattern: Option[Regex],
    seed: Option[Json],
    @description("State search predicate")
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    @description("Request specification")
    request: HttpStubRequest,
    @description("Persisted data")
    persist: Option[Map[JsonOptic, Json]],
    @description("Response specification")
    response: HttpStubResponse,
    @description("Callback specification")
    callback: Option[Callback],
    @description("Tags")
    labels: Seq[String]
) derives Decoder, Encoder, Schema
object UpdateStubRequest {
  implicitly[PropSubset[UpdateStubRequest, StubPatch]]
}

final case class StubPatch(
    id: SID[HttpStub],
    scope: Scope,
    times: Option[NonNegInt],
    name: NonEmptyString,
    method: HttpMethod,
    path: Option[NonEmptyString],
    pathPattern: Option[Regex],
    seed: Option[Json],
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    request: HttpStubRequest,
    persist: Option[Map[JsonOptic, Json]],
    response: HttpStubResponse,
    callback: Option[Callback],
    labels: Seq[String]
) derives BsonEncoder

object StubPatch {
  inline given QueryMeta[StubPatch  ] = queryMeta(_.id -> "_id")

  implicitly[PropSubset[StubPatch, HttpStub]]
}
