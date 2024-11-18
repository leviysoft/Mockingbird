package ru.tinkoff.tcb.mockingbird.grpc

import com.google.protobuf.InvalidProtocolBufferException
import eu.timepit.refined.*
import eu.timepit.refined.numeric.*
import io.circe.Json
import io.circe.ParsingFailure
import mouse.option.*
import zio.interop.catz.core.*

import ru.tinkoff.tcb.criteria.*
import ru.tinkoff.tcb.criteria.Typed.*
import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.dal.GrpcMethodDescriptionDAO
import ru.tinkoff.tcb.mockingbird.dal.GrpcStubDAO
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.error.StubSearchError
import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.FromGrpcProtoDefinition
import ru.tinkoff.tcb.mockingbird.misc.Renderable.ops.*
import ru.tinkoff.tcb.mockingbird.model.GrpcMethodDescription
import ru.tinkoff.tcb.mockingbird.model.GrpcStub
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox

trait GrpcStubResolver {
  def findStubAndState(
      methodDescriptionPromise: Promise[StubSearchError, GrpcMethodDescription],
      service: String,
      request: Array[Byte]
  )(scope: Scope):  RIO[WLD, Option[(GrpcStub, Json, Option[PersistentState])]]
}

class GrpcStubResolverImpl(
    stubDAO: GrpcStubDAO[Task],
    stateDAO: PersistentStateDAO[Task],
    methodDescriptionDAO: GrpcMethodDescriptionDAO[Task],
    implicit val jsSandbox: GraalJsSandbox
) extends GrpcStubResolver {

  private type StateSpec = Map[JsonOptic, Map[Keyword.Json, Json]]

  private val log = MDCLogging.`for`[WLD](this)

  override def findStubAndState(
      methodDescriptionPromise: Promise[StubSearchError, GrpcMethodDescription],
      methodName: String,
      request: Array[Byte]
  )(scope: Scope): RIO[WLD, Option[(GrpcStub, Json, Option[PersistentState])]] =
    (for {
      methodDescription <- getMethodDescription(methodDescriptionPromise, methodName, scope).some
      _ <- log.info("1. got method description")
      json <- parseJson(methodDescription, request).some
      _ <- log.info("2. parsed json")
      res <- findStubAndState(methodDescription.id, json).some
      _ <- log.info("3. found stub")
      _ <- methodDescriptionPromise.succeed(methodDescription)
    } yield res).unsome

  private def getMethodDescription(
      methodDescriptionPromise: Promise[StubSearchError, GrpcMethodDescription],
      methodName: String,
      scope: Scope,
  ): Task[Option[GrpcMethodDescription]] = methodDescriptionPromise.poll.flatMap {
    case Some(io) => io.asSome
    case _ => methodDescriptionDAO
      .findOne(
        prop[GrpcMethodDescription](_.methodName) === methodName &&
          prop[GrpcMethodDescription](_.scope) === scope
      )
  }

  private def findStubAndState(
      methodDescriptionId: SID[GrpcMethodDescription],
      json: Json
  ): RIO[WLD, Option[(GrpcStub, Json, Option[PersistentState])]] = for {
    stubs <- stubDAO
      .findChunk(
        prop[GrpcStub](_.methodDescriptionId) === methodDescriptionId &&
          prop[GrpcStub](_.times) > Option(refineMV[NonNegative](0)),
        0,
        Integer.MAX_VALUE
      )
    pairs = stubs.map(json -> _)
      .filter { case (json, stub) =>
        stub.requestPredicates(json)
      }
    candidates <- pairs
      .traverse { case (json, stub) =>
        stub.state
          .map(_.fill(json))
          .cata(
            spec => findStates(stub.id, spec).map(stub -> _),
            ZIO.succeed(stub -> Vector.empty[PersistentState])
          )
      }
    _ <- ZIO.when(candidates.exists(_._2.size > 1))(
      log.error("For one or more stubs, multiple suitable states were found") *>
        ZIO.fail(StubSearchError("For one or more stubs, multiple suitable states were found"))
    )
    _ <- ZIO.when(candidates.count(_._2.nonEmpty) > 1)(
      log.error("For more than one stub, suitable states were found") *>
        ZIO.fail(StubSearchError("For more than one stub, suitable states were found"))
    )
    _ <- ZIO.when(candidates.size > 1 && candidates.forall(c => c._1.state.isDefined && c._2.isEmpty))(
      log.error("No suitable state found for any stub") *>
        ZIO.fail(StubSearchError("No suitable state found for any stub"))
    )
    _ <- ZIO.when(candidates.size > 1 && candidates.forall(_._1.state.isEmpty))(
      log.error("More than one stateless stub found") *>
        ZIO.fail(StubSearchError("More than one stateless stub found"))
    )
    res = candidates.find(_._2.size == 1) orElse candidates.find(_._1.state.isEmpty)
  } yield res.map { case (stub, states) =>
    (stub, pairs.find(_._2.id == stub.id).map(_._1).get, states.headOption)
  }

  private def parseJson(methodDescription: GrpcMethodDescription, bytes: Array[Byte]): URIO[WLD, Option[Json]] =
    ZIO
      .blocking(
        methodDescription.requestSchema.convertMessageToJson(bytes, methodDescription.requestClass).map(json => json.some)
      )
      .catchSome { case e @ (_: InvalidProtocolBufferException | ParsingFailure(_, _)) =>
        log.infoCause(
          "Failed to parse gRPC request {} for method description {}",
          e,
          methodDescription.requestClass,
          methodDescription.id
        ) *>
          ZIO.none
      }
      .tapError(e =>
        log.errorCause(
          "Failed to parse gRPC request {} for method description {}",
          e,
          methodDescription.requestClass,
          methodDescription.id
        )
      )
      .orDie

  private def findStates(id: SID[GrpcStub], spec: StateSpec): RIO[WLD, Vector[PersistentState]] =
    for {
      _      <- log.info("Searching for state for {} based on condition {}", id, spec.renderJson.noSpaces)
      states <- stateDAO.findBySpec(spec)
      _ <-
        if (states.nonEmpty) log.info("States found for {}: {}", id, states.map(_.id))
        else log.info("No suitable states found for {}", id)
    } yield states
}

object GrpcStubResolverImpl {
  val live: URLayer[
    GrpcStubDAO[Task] & PersistentStateDAO[Task] & GrpcMethodDescriptionDAO[Task] & GraalJsSandbox,
    GrpcStubResolver
  ] =
    ZLayer {
      for {
        gsd       <- ZIO.service[GrpcStubDAO[Task]]
        psd       <- ZIO.service[PersistentStateDAO[Task]]
        gmdd      <- ZIO.service[GrpcMethodDescriptionDAO[Task]]
        jsSandbox <- ZIO.service[GraalJsSandbox]
      } yield new GrpcStubResolverImpl(gsd, psd, gmdd, jsSandbox)
    }
}
