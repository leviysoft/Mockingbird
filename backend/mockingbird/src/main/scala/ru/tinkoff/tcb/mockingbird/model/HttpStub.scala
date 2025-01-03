package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant
import scala.util.matching.Regex

import com.github.dwickern.macros.NameOf.*
import eu.timepit.refined.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.refined.*
import mouse.boolean.*
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*
import sttp.tapir.Schema

import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.mockingbird.model.StubCode
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.unpack.*
import ru.tinkoff.tcb.validation.Rule

final case class HttpStub(
    @description("Mock id")
    id: SID[HttpStub],
    @description("Mock creation time")
    created: Instant,
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[Int],
    serviceSuffix: String,
    @description("Mock name")
    name: String,
    @description("HTTP method")
    method: HttpMethod,
    @description("The path suffix where the mock triggers")
    path: Option[String],
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
    @description("tags")
    labels: Seq[String]
) derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema

object HttpStub extends CallbackChecker {
  inline given QueryMeta[HttpStub] = queryMeta(_.id -> "_id")

  private val pathOrPattern: Rule[HttpStub] = stub =>
    (stub.path, stub.pathPattern) match {
      case Some(_) <*> None | None <*> Some(_) => Vector.empty
      case Some(_) <*> Some(_)                 => Vector("A path or path pattern may be specified")
      case None <*> None                       => Vector("A path or path pattern must be specified")
    }

  private val stateNonEmpty: Rule[HttpStub] =
    _.state.exists(_.isEmpty).valueOrZero(Vector("The state predicate cannot be empty"))

  private val persistNonEmpty: Rule[HttpStub] =
    _.persist.exists(_.isEmpty).valueOrZero(Vector("The persist specification cannot be empty"))

  private val jsonProxyReq: Rule[HttpStub] = stub =>
    (stub.request, stub.response) match {
      case (JsonRequest(_, _, _) | JLensRequest(_, _, _), JsonProxyResponse(_, _, _, _)) => Vector.empty
      case (_, JsonProxyResponse(_, _, _, _)) =>
        Vector(s"${nameOfType[JsonProxyResponse]} can only be used together with ${nameOfType[JsonRequest]} or ${nameOfType[JLensRequest]}")
      case (XmlRequest(_, _, _, _, _) | XPathRequest(_, _, _, _, _), XmlProxyResponse(_, _, _, _)) => Vector.empty
      case (_, XmlProxyResponse(_, _, _, _)) =>
        Vector(s"${nameOfType[XmlProxyResponse]} can only be used together with ${nameOfType[XmlRequest]} or ${nameOfType[XPathRequest]}")
      case _ => Vector.empty
    }

  private val responseCodes204and304: Rule[HttpStub] = stub =>
    stub.response match {
      case StubCode(rc) if rc == refineV[HttpStatusCodeRange].unsafeFrom(204) || rc == refineV[HttpStatusCodeRange].unsafeFrom(304) =>
        stub.response match {
          case EmptyResponse(_, _, _) => Vector.empty
          case _ =>
            Vector(s"Response codes 204 and 304 can only be used with mode '${HttpStubResponse.modes(nameOfType[EmptyResponse])}'")
        }
      case _ => Vector.empty
    }

  def validationRules(destinations: Set[SID[DestinationConfiguration]]): Rule[HttpStub] =
    Vector(
      pathOrPattern,
      (h: HttpStub) => checkCallback(h.callback, destinations),
      stateNonEmpty,
      persistNonEmpty,
      jsonProxyReq,
      responseCodes204and304
    ).reduce(_ |+| _)
}
