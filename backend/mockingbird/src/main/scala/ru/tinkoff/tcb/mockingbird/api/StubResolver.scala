package ru.tinkoff.tcb.mockingbird.api

import scala.xml.Node

import com.github.dwickern.macros.NameOf.*
import eu.timepit.refined.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.*
import io.circe.Json
import mouse.boolean.*
import mouse.option.*
import oolong.dsl.*
import oolong.mongo.*
import org.mongodb.scala.bson.*
import zio.interop.catz.core.*

import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.dal.HttpStubDAO
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.error.*
import ru.tinkoff.tcb.mockingbird.misc.Renderable.ops.*
import ru.tinkoff.tcb.mockingbird.model.HttpMethod
import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.RequestBody
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.regex.*
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox

final class StubResolver(
    stubDAO: HttpStubDAO[Task],
    stateDAO: PersistentStateDAO[Task],
    implicit val jsSandbox: GraalJsSandbox
) {
  private val log = MDCLogging.`for`[WLD](this)

  private type StateSpec = Map[JsonOptic, Map[Keyword.Json, Json]]

  def findStubAndState(
      method: HttpMethod,
      path: String,
      headers: Map[String, String],
      queryObject: Json,
      body: RequestBody
  )(
      scope: Scope
  ): RIO[WLD, Option[(HttpStub, Option[PersistentState])]] =
    (
      for {
        _ <- log.info("Searching stubs for request to {} of type {}", path, scope)
        pathPatternExpr = BsonDocument(
          "$expr" -> BsonDocument(
            "$regexMatch" -> BsonDocument(
              "input" -> path,
              "regex" -> s"$$${nameOf[HttpStub](_.pathPattern)}"
            )
          )
        )
        condition0 = query[HttpStub](hs =>
          hs.method == lift(method) && (hs.path.!! == lift(path) || (hs.path.isEmpty && unchecked(pathPatternExpr)))
          && hs.scope == lift(scope)
        )
        //TODO
        /*
        condition = (scope == Scope.Countdown).fold(
          condition0 && prop[HttpStub](_.times) > Option(refineMV[NonNegative](0)),
          condition0
        )*/
        candidates0 <- stubDAO.findChunk(condition0, 0, Int.MaxValue)
        _ <- ZIO.when(candidates0.isEmpty)(
          log.info("Can't find any handler for {} of type {}", path, scope) *> ZIO.fail(EarlyReturn)
        )
        _ <- log.info("Candidates are: {}", candidates0.map(_.id))
        candidates1 = candidates0.filter(_.request.checkQueryParams(queryObject))
        _ <- ZIO.when(candidates1.isEmpty)(
          log.warn(
            "After checking query parameters, there are no candidates left. Please verify the parameters: {}",
            queryObject.noSpaces
          ) *> ZIO.fail(EarlyReturn)
        )
        _ <- log.info("After query parameters check: {}", candidates1.map(_.id))
        candidates2 = candidates1.filter(_.request.checkHeaders(headers))
        _ <- ZIO.when(candidates2.isEmpty)(
          log.warn("After checking headers, there are no candidates left. Please verify the headers: {}", headers) *>
            ZIO.fail(EarlyReturn)
        )
        _ <- log.info("After headers check: {}", candidates2.map(_.id))
        candidates3 = candidates2.filter(_.request.checkBody(body))
        _ <- ZIO.when(candidates3.isEmpty)(
          log.warn(
            "After checking the request body, there are no candidates left. Please verify the request body: {}",
            body
          ) *>
            ZIO.fail(EarlyReturn)
        )
        _ <- log.info("After body check: {}", candidates3.map(_.id))
        candidates4 <- candidates3.traverse { stubc =>
          val bodyJson = stubc.request.extractJson(body)
          val bodyXml  = stubc.request.extractXML(body)
          val groups = for {
            pattern <- stubc.pathPattern
            mtch    <- pattern.findFirstMatchIn(path)
          } yield pattern.groups.map(g => g -> mtch.group(g)).to(Map)
          val segments = groups.map(segs => Json.fromFields(segs.view.mapValues(Json.fromString))).getOrElse(Json.Null)
          val headerJsonMap = Json.fromFields(headers.view.mapValues(Json.fromString))
          computeStateSpec(
            stubc.state.map(
              _.fill(Json.obj("__query" -> queryObject, "__segments" -> segments, "__headers" -> headerJsonMap))
            ),
            bodyJson,
            bodyXml
          )
            .cata(
              spec => findStates(stubc.id, spec).map(stubc -> _),
              ZIO.succeed(stubc -> Vector.empty[PersistentState])
            )
        }
        _ <- ZIO.when(candidates4.exists(_._2.size > 1))(
          log.error("For one or more stubs, multiple suitable states were found") *>
            ZIO.fail(StubSearchError("For one or more stubs, multiple suitable states were found"))
        )
        _ <- ZIO.when(candidates4.count(_._2.nonEmpty) > 1)(
          log.error("For more than one stub, suitable states were found") *>
            ZIO.fail(StubSearchError("For more than one stub, suitable states were found"))
        )
        _ <- ZIO.when(candidates4.size > 1 && candidates4.forall(c => c._1.state.isDefined && c._2.isEmpty))(
          log.error("No suitable state found for any stub") *>
            ZIO.fail(StubSearchError("No suitable state found for any stub"))
        )
        _ <- ZIO.when(candidates4.size > 1 && candidates4.forall(_._1.state.isEmpty))(
          log.error("More than one stateless stub found") *>
            ZIO.fail(StubSearchError("More than one stateless stub found"))
        )
        res = candidates4.find(_._2.size == 1) orElse candidates4.find(_._1.state.isEmpty)
      } yield res.map { case (stub, states) => stub -> states.headOption }
    ).catchSome { case EarlyReturn =>
      ZIO.none
    }

  private def computeStateSpec(
      spec: Option[StateSpec],
      bodyJson: Option[Json],
      bodyXml: Option[Node]
  ): Option[StateSpec] =
    (spec, bodyJson)
      .mapN(_.fill(_))
      .orElse((spec, bodyXml).mapN(_.fill(_)))
      .orElse(spec)

  private def findStates[E](id: SID[E], spec: StateSpec): RIO[WLD, Vector[PersistentState]] =
    for {
      _      <- log.info("Searching state for {} by condition {}", id, spec.renderJson.noSpaces)
      states <- stateDAO.findBySpec(spec)
      _ <-
        if (states.nonEmpty) log.info("Found states for {}: {}", id, states.map(_.id))
        else log.info("Unable to find suitable states for {}", id)
    } yield states
}

object StubResolver {
  val live = ZLayer {
    for {
      hsd       <- ZIO.service[HttpStubDAO[Task]]
      ssd       <- ZIO.service[PersistentStateDAO[Task]]
      jsSandbox <- ZIO.service[GraalJsSandbox]
    } yield new StubResolver(hsd, ssd, jsSandbox)
  }
}
