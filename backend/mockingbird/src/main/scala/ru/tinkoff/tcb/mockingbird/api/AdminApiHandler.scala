package ru.tinkoff.tcb.mockingbird.api

import scala.util.control.NonFatal

import eu.timepit.refined.*
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.*
import eu.timepit.refined.types.string.NonEmptyString
import io.scalaland.chimney.dsl.*
import kantan.xpath.*
import kantan.xpath.implicits.*
import org.mongodb.scala.bson.*

import ru.tinkoff.tcb.criteria.*
import ru.tinkoff.tcb.criteria.Typed.*
import ru.tinkoff.tcb.criteria.Untyped.*
import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.api.request.*
import ru.tinkoff.tcb.mockingbird.api.response.DestinationDTO
import ru.tinkoff.tcb.mockingbird.api.response.OperationResult
import ru.tinkoff.tcb.mockingbird.api.response.SourceDTO
import ru.tinkoff.tcb.mockingbird.dal.DestinationConfigurationDAO
import ru.tinkoff.tcb.mockingbird.dal.GrpcMethodDescriptionDAO
import ru.tinkoff.tcb.mockingbird.dal.GrpcStubDAO
import ru.tinkoff.tcb.mockingbird.dal.HttpStubDAO
import ru.tinkoff.tcb.mockingbird.dal.LabelDAO
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.dal.ScenarioDAO
import ru.tinkoff.tcb.mockingbird.dal.ServiceDAO
import ru.tinkoff.tcb.mockingbird.dal.SourceConfigurationDAO
import ru.tinkoff.tcb.mockingbird.error.*
import ru.tinkoff.tcb.mockingbird.error.DuplicationError
import ru.tinkoff.tcb.mockingbird.error.ValidationError
import ru.tinkoff.tcb.mockingbird.grpc.ProtobufSchemaResolver
import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.mockingbird.model.GrpcMethodDescription
import ru.tinkoff.tcb.mockingbird.model.GrpcStub
import ru.tinkoff.tcb.mockingbird.model.GrpcStubView
import ru.tinkoff.tcb.mockingbird.model.HttpMethod
import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mockingbird.model.Label
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.RequestBody
import ru.tinkoff.tcb.mockingbird.model.Scenario
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.mockingbird.model.Service
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.mockingbird.resource.ResourceManager
import ru.tinkoff.tcb.mockingbird.scenario.ScenarioResolver
import ru.tinkoff.tcb.mockingbird.stream.SDFetcher
import ru.tinkoff.tcb.protocol.fields.*
import ru.tinkoff.tcb.protocol.rof.*
import ru.tinkoff.tcb.utils.crypto.AES
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.xml.*

final class AdminApiHandler(
    stubDAO: HttpStubDAO[Task],
    scenarioDAO: ScenarioDAO[Task],
    stateDAO: PersistentStateDAO[Task],
    serviceDAO: ServiceDAO[Task],
    labelDAO: LabelDAO[Task],
    grpcStubDAO: GrpcStubDAO[Task],
    grpcMethodDescriptionDAO: GrpcMethodDescriptionDAO[Task],
    sourceDAO: SourceConfigurationDAO[Task],
    destinationDAO: DestinationConfigurationDAO[Task],
    fetcher: SDFetcher,
    stubResolver: StubResolver,
    scenarioResolver: ScenarioResolver,
    protobufSchemaResolver: ProtobufSchemaResolver,
    rm: ResourceManager
)(implicit aes: AES) {
  private val log = MDCLogging.`for`[WLD](this)

  def createHttpStub(body: CreateStubRequest): RIO[WLD, OperationResult[SID[HttpStub]]] =
    for {
      service1 <- ZIO.foreach(body.path.map(_.value))(serviceDAO.getServiceFor).map(_.flatten)
      service2 <- ZIO.foreach(body.pathPattern)(serviceDAO.getServiceFor).map(_.flatten)
      _ <- ZIO.when(service1.isEmpty && service2.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Can't find service for ${body.path.orElse(body.pathPattern.map(_.regex)).getOrElse("")}")
          )
        )
      )
      service = service1.orElse(service2).get
      candidates0 <- stubDAO.findChunk(
        prop[HttpStub](_.method) === body.method &&
          (if (body.path.isDefined) prop[HttpStub](_.path) === body.path
           else prop[HttpStub](_.pathPattern) === body.pathPattern) &&
          prop[HttpStub](_.scope) === body.scope &&
          prop[HttpStub](_.times) > Option(refineMV[NonNegative](0)),
        0,
        Int.MaxValue
      )
      candidates1 = candidates0.filter(_.request == body.request)
      candidates2 = candidates1.filter(_.state == body.state)
      _ <- ZIO.when(candidates2.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a stub or stubs that match completely in terms of conditions and type",
            candidates2.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      stub = body
        .into[HttpStub]
        .withFieldComputed(_.id, _ => SID.random[HttpStub])
        .withFieldConst(_.created, now)
        .withFieldConst(_.serviceSuffix, service.suffix)
        .transform
      destinations <- fetcher.getDestinations
      destNames = destinations.map(_.name).toSet
      vr        = HttpStub.validationRules(destNames)(stub)
      _   <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- stubDAO.insert(stub)
      _   <- labelDAO.ensureLabels(service.suffix, stub.labels.to(Vector))
    } yield if (res > 0) OperationResult("success", stub.id) else OperationResult("nothing inserted")

  def createScenario(body: CreateScenarioRequest): RIO[WLD, OperationResult[SID[Scenario]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      candidates0 <- scenarioDAO.findChunk(
        prop[Scenario](_.source) === body.source &&
          prop[Scenario](_.scope) === body.scope &&
          prop[HttpStub](_.times) > Option(refineMV[NonNegative](0)),
        0,
        Int.MaxValue
      )
      candidates1 = candidates0.filter(_.input == body.input)
      candidates2 = candidates1.filter(_.state == body.state)
      _ <- ZIO.when(candidates2.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exist scenario(s) that match completely in terms of source, conditions, and type",
            candidates2.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      scenario = body
        .into[Scenario]
        .withFieldComputed(_.id, _ => SID.random[Scenario])
        .withFieldConst(_.created, now)
        .transform
      sources <- fetcher.getSources
      sourceNames = sources.map(_.name).toSet
      destinations <- fetcher.getDestinations
      destNames = destinations.map(_.name).toSet
      vr        = Scenario.validationRules(sourceNames, destNames)(scenario)
      _   <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- scenarioDAO.insert(scenario)
      _   <- labelDAO.ensureLabels(service.get.suffix, scenario.labels.to(Vector))
    } yield if (res > 0) OperationResult("success", scenario.id) else OperationResult("nothing inserted")

  def createService(body: CreateServiceRequest): RIO[WLD, OperationResult[String]] =
    for {
      candidates <- serviceDAO.findChunk(prop[Service](_.suffix) === body.suffix.value, 0, Int.MaxValue)
      _ <- ZIO.when(candidates.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            s"There exist service(s) that have a suffix ${body.suffix.value}",
            candidates.map(_.name)
          )
        )
      )
      service = body
        .into[Service]
        .withFieldComputed(_.suffix, _.suffix.value)
        .withFieldComputed(_.name, _.name.value)
        .transform
      res <- serviceDAO.insert(service)
    } yield if (res > 0) OperationResult("success", service.suffix) else OperationResult("nothing inserted")

  def fetchStates(body: SearchRequest): RIO[WLD, Vector[PersistentState]] =
    stateDAO.findBySpec(body.query)

  def testXpath(body: XPathTestRequest): String =
    body.xml.toKNode.evalXPath[Node](body.path.toXPathExpr) match {
      case Left(error) => s"Error: $error"
      case Right(node) => s"Success: ${node.print()}"
    }

  def tryResolveStub(
      method: HttpMethod,
      path: String,
      headers: Map[String, String],
      query: Seq[(String, Seq[String])],
      body: RequestBody
  ): RIO[WLD, SID[HttpStub]] = {
    val queryObject = queryParamsToJsonObject(query)
    val f           = stubResolver.findStubAndState(method, path, headers, queryObject, body) _

    for {
      _ <- Tracing.update(_.addToPayload("path" -> path, "method" -> method.entryName))
      (stub, _) <- f(Scope.Countdown)
        .filterOrElse(_.isDefined)(f(Scope.Ephemeral).filterOrElse(_.isDefined)(f(Scope.Persistent)))
        .someOrFail(StubSearchError(s"Cant find a stub for [$method] $path"))
    } yield stub.id
  }

  def tryResolveScenario(body: ScenarioResolveRequest): RIO[WLD, SID[Scenario]] = {
    val f = scenarioResolver.findScenarioAndState(body.source, body.message) _

    for {
      (scenario, _) <- f(Scope.Countdown)
        .filterOrElse(_.isDefined)(f(Scope.Ephemeral).filterOrElse(_.isDefined)(f(Scope.Persistent)))
        .someOrFail(ScenarioSearchError(s"Can't find any scenario for the message from ${body.source}"))
    } yield scenario.id
  }

  def fetchServices: RIO[WLD, Vector[Service]] =
    serviceDAO.findChunk(Document(), 0, Int.MaxValue)

  def getService(suffix: String): RIO[WLD, Option[Service]] =
    serviceDAO.findById(suffix)

  def fetchStubs(
      page: Option[Int],
      query: Option[String],
      service: Option[String],
      labels: List[String]
  ): RIO[WLD, Vector[HttpStub]] = {
    var queryDoc =
      prop[HttpStub](_.scope) =/= Scope.Countdown.asInstanceOf[Scope] || prop[HttpStub](_.times) > Option(
        refineMV[NonNegative](0)
      )
    if (query.isDefined) {
      val qs = query.get
      val q = prop[HttpStub](_.id) === SID[HttpStub](qs).asInstanceOf[SID[HttpStub]] ||
        prop[HttpStub](_.name).regex(qs, "i") ||
        prop[HttpStub](_.path).regex(qs, "i") ||
        prop[HttpStub](_.pathPattern).regex(qs, "i")
      queryDoc = queryDoc && q
    }
    if (service.isDefined) {
      queryDoc = queryDoc && (prop[HttpStub](_.serviceSuffix) === service.get)
    }
    if (labels.nonEmpty) {
      queryDoc = queryDoc && (prop[HttpStub](_.labels).containsAll(labels))
    }
    stubDAO.findChunk(queryDoc, page.getOrElse(0) * 20, 20, prop[HttpStub](_.created).sort(Desc))
  }

  def fetchScenarios(
      page: Option[Int],
      query: Option[String],
      service: Option[String],
      labels: List[String]
  ): RIO[WLD, Vector[Scenario]] = {
    var queryDoc =
      prop[Scenario](_.scope) =/= Scope.Countdown.asInstanceOf[Scope] || prop[Scenario](_.times) > Option(
        refineMV[NonNegative](0)
      )
    if (query.isDefined) {
      val qs = query.get
      val q = prop[Scenario](_.id) === SID[Scenario](qs).asInstanceOf[SID[Scenario]] ||
        prop[Scenario](_.name).regex(qs, "i") ||
        prop[Scenario](_.source).regex(qs, "i") ||
        prop[Scenario](_.destination).regex(qs, "i")
      queryDoc = queryDoc && q
    }
    val refService = service.flatMap(refineV[NonEmpty](_).toOption)
    if (refService.isDefined) {
      queryDoc = queryDoc && (prop[Scenario](_.service) === refService.get)
    }
    if (labels.nonEmpty) {
      queryDoc = queryDoc && (prop[Scenario](_.labels).containsAll(labels))
    }
    scenarioDAO.findChunk(queryDoc, page.getOrElse(0) * 20, 20, prop[HttpStub](_.created).sort(Desc))
  }

  def getStub(id: SID[HttpStub]): RIO[WLD, Option[HttpStub]] =
    stubDAO.findById(id)

  def getScenario(id: SID[Scenario]): RIO[WLD, Option[Scenario]] =
    scenarioDAO.findById(id)

  def deleteStub2(id: SID[HttpStub]): RIO[WLD, OperationResult[String]] =
    ZIO.ifZIO(stubDAO.deleteById(id).map(_ > 0))(
      ZIO.succeed(OperationResult("success")),
      ZIO.succeed(OperationResult("nothing deleted"))
    )

  def deleteScenario2(id: SID[Scenario]): RIO[WLD, OperationResult[String]] =
    ZIO.ifZIO(scenarioDAO.deleteById(id).map(_ > 0))(
      ZIO.succeed(OperationResult("success")),
      ZIO.succeed(OperationResult("nothing deleted"))
    )

  def updateStub(id: SID[HttpStub], body: UpdateStubRequest): RIO[WLD, OperationResult[SID[HttpStub]]] =
    for {
      service1 <- ZIO.foreach(body.path.map(_.value))(serviceDAO.getServiceFor).map(_.flatten)
      service2 <- ZIO.foreach(body.pathPattern)(serviceDAO.getServiceFor).map(_.flatten)
      _ <- ZIO.when(service1.isEmpty && service2.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Can't find a service for ${body.path.orElse(body.pathPattern.map(_.regex)).getOrElse("")}")
          )
        )
      )
      service = service1.orElse(service2).get
      candidates0 <- stubDAO.findChunk(
        where(_._id =/= id) &&
          prop[HttpStub](_.method) === body.method &&
          (if (body.path.isDefined) prop[HttpStub](_.path) === body.path
           else prop[HttpStub](_.pathPattern) === body.pathPattern) &&
          prop[HttpStub](_.scope) === body.scope &&
          prop[HttpStub](_.times) > Option(refineMV[NonNegative](0)),
        0,
        Int.MaxValue
      )
      candidates1 = candidates0.filter(_.request == body.request)
      candidates2 = candidates1.filter(_.state == body.state)
      _ <- ZIO.when(candidates2.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a stub or stubs that match completely in terms of conditions and type",
            candidates2.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      stubPatch = body
        .into[StubPatch]
        .withFieldConst(_.id, id)
        .transform
      stub = stubPatch
        .into[HttpStub]
        .withFieldConst(_.created, now)
        .withFieldConst(_.serviceSuffix, service.suffix)
        .transform
      destinations <- fetcher.getDestinations
      destNames = destinations.map(_.name).toSet
      vr        = HttpStub.validationRules(destNames)(stub)
      _   <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- stubDAO.patch(stubPatch)
      _   <- labelDAO.ensureLabels(service.suffix, stubPatch.labels.to(Vector))
    } yield if (res.successful) OperationResult("success", stub.id) else OperationResult("nothing updated")

  def updateScenario(id: SID[Scenario], body: UpdateScenarioRequest): RIO[WLD, OperationResult[SID[Scenario]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      candidates0 <- scenarioDAO.findChunk(
        where(_._id =/= id) &&
          prop[Scenario](_.source) === body.source &&
          prop[Scenario](_.scope) === body.scope &&
          prop[HttpStub](_.times) > Option(refineMV[NonNegative](0)),
        0,
        Int.MaxValue
      )
      candidates1 = candidates0.filter(_.input == body.input)
      candidates2 = candidates1.filter(_.state == body.state)
      _ <- ZIO.when(candidates2.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exist scenario(s) that match completely in terms of source, conditions, and type",
            candidates2.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      scenarioPatch = body
        .into[ScenarioPatch]
        .withFieldConst(_.id, id)
        .transform
      scenario = scenarioPatch
        .into[Scenario]
        .withFieldConst(_.created, now)
        .transform
      sources <- fetcher.getSources
      sourceNames = sources.map(_.name).toSet
      destinations <- fetcher.getDestinations
      destNames = destinations.map(_.name).toSet
      vr        = Scenario.validationRules(sourceNames, destNames)(scenario)
      _   <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- scenarioDAO.patch(scenarioPatch)
      _   <- labelDAO.ensureLabels(body.service.value, scenario.labels.to(Vector))
    } yield if (res.successful) OperationResult("success", scenario.id) else OperationResult("nothing inserted")

  def getLabels(service: String): RIO[WLD, Vector[String]] =
    labelDAO.findChunk(prop[Label](_.serviceSuffix) === service, 0, Int.MaxValue).map(_.map(_.label))

  def fetchGrpcStubs(
      page: Option[Int],
      query: Option[String],
      service: Option[String],
      labels: List[String]
  ): RIO[WLD, Vector[GrpcStubView]] =
    for {
      scopeQuery <- ZIO.succeed(
        prop[GrpcStub](_.scope) =/= Scope.Countdown.asInstanceOf[Scope] ||
          prop[GrpcStub](_.times) > Option(refineMV[NonNegative](0))
      )
      nameDescriptions <- ZIO
        .foreach(query) { query =>
          val queryDoc = prop[GrpcMethodDescription](_.methodName).regex(query, "i")
          grpcMethodDescriptionDAO.findChunk(queryDoc, 0, Integer.MAX_VALUE)
        }
        .map(_.toList.flatten)
      nameQuery =
        if (query.isDefined) {
          val qs = query.get
          val q = prop[GrpcStub](_.id) === SID[GrpcStub](qs).asInstanceOf[SID[GrpcStub]] ||
            prop[GrpcStub](_.name).regex(qs, "i") ||
            prop[GrpcStub](_.methodDescriptionId).in(nameDescriptions.map(_.id))
          scopeQuery && q
        } else scopeQuery
      refService = service.flatMap(refineV[NonEmpty](_).toOption)
      serviceQuery <-
        if (refService.isDefined) {
          grpcMethodDescriptionDAO
            .findChunk(prop[GrpcMethodDescription](_.service) === refService.get, 0, Integer.MAX_VALUE)
            .map(methodDescriptions => nameQuery && prop[GrpcStub](_.methodDescriptionId).in(methodDescriptions.map(_.id)))
        } else ZIO.succeed(nameQuery)
      queryDoc =
        if (labels.nonEmpty) {
          serviceQuery && (prop[GrpcStub](_.labels).containsAll(labels))
        } else serviceQuery
      stubs <- grpcStubDAO.findChunk(queryDoc, page.getOrElse(0) * 20, 20, prop[GrpcStub](_.created).sort(Desc))
      methodDescriptions <- grpcMethodDescriptionDAO
        .findChunk(prop[GrpcMethodDescription](_.id).in(stubs.map(_.methodDescriptionId).toSet), 0, 20)
      res = stubs.flatMap(stub =>
        methodDescriptions
          .find(_.id == stub.methodDescriptionId)
          .map(description => GrpcStubView.makeFrom(stub, description))
          .toList
      )
    } yield res

  def createGrpcStub(body: CreateGrpcStubRequest): RIO[WLD, OperationResult[SID[GrpcStub]]] = {
    val requestSchemaBytes  = body.requestCodecs.asArray
    val responseSchemaBytes = body.responseCodecs.asArray
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      requestSchema <- protobufSchemaResolver.parseDefinitionFrom(requestSchemaBytes)
      requestPkg   = GrpcMethodDescription.PackagePrefix(requestSchema)
      requestTypes = GrpcMethodDescription.makeDictTypes(requestPkg, requestSchema.schemas).toMap
      rootFields <- GrpcMethodDescription.getRootFields(requestPkg.resolve(body.requestClass), requestTypes)
      _ <- ZIO.foreachParDiscard(body.requestPredicates.definition.keys)(
        GrpcStub.validateOptics(_, requestTypes, rootFields)
      )
      responseSchema <- protobufSchemaResolver.parseDefinitionFrom(responseSchemaBytes)
      responsePkg   = GrpcMethodDescription.PackagePrefix(responseSchema)
      responseTypes = GrpcMethodDescription.makeDictTypes(responsePkg, responseSchema.schemas).toMap
      _   <- GrpcMethodDescription.getRootFields(responsePkg.resolve(body.responseClass), responseTypes)
      now <- ZIO.clockWith(_.instant)
      methodDescription <- grpcMethodDescriptionDAO
        .findOne(prop[GrpcMethodDescription](_.methodName) === body.methodName)
        .someOrElseZIO {
          val methodDescription = GrpcMethodDescription.fromCreateRequest(body, requestSchema, responseSchema, now)
          grpcMethodDescriptionDAO.insert(methodDescription).as(methodDescription)
        }
      _ <- GrpcMethodDescription.validate(methodDescription)(
        body.requestClass,
        requestSchema,
        body.responseClass,
        responseSchema
      )
      candidates0 <- grpcStubDAO.findChunk(
        prop[GrpcStub](_.methodDescriptionId) === methodDescription.id,
        0,
        Integer.MAX_VALUE
      )
      candidates = candidates0
        .filter(_.requestPredicates.definition == body.requestPredicates.definition)
        .filter(_.state == body.state)
      _ <- ZIO.when(candidates.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a stub or stubs that match completely in terms of conditions and type",
            candidates.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      stub = body
        .into[GrpcStub]
        .withFieldComputed(_.id, _ => SID.random[GrpcStub])
        .withFieldConst(_.methodDescriptionId, methodDescription.id)
        .withFieldConst(_.created, now)
        .transform
      vr = GrpcStub.validationRules(stub)
      _   <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- grpcStubDAO.insert(stub)
      _   <- labelDAO.ensureLabels(methodDescription.service.value, stub.labels.to(Vector))
    } yield if (res > 0) OperationResult("success", stub.id) else OperationResult("nothing inserted")
  }

  def getGrpcStub(id: SID[GrpcStub]): RIO[WLD, Option[GrpcStubView]] =
    (for {
      stub        <- grpcStubDAO.findById(id).some
      description <- grpcMethodDescriptionDAO.findById(stub.methodDescriptionId).some
    } yield GrpcStubView.makeFrom(stub, description)).unsome

  def deleteGrpcStub(id: SID[GrpcStub]): RIO[WLD, OperationResult[String]] =
    ZIO.ifZIO(grpcStubDAO.deleteById(id).map(_ > 0))(
      ZIO.succeed(OperationResult("success")),
      ZIO.succeed(OperationResult("nothing deleted"))
    )

  def fetchSourceConfigurations(
      service: Option[NonEmptyString]
  ): RIO[WLD, Vector[SourceDTO]] = {
    var queryDoc = BsonDocument()
    if (service.isDefined) {
      queryDoc = prop[Scenario](_.service) === service.get
    }
    sourceDAO
      .findChunkProjection[SourceDTO](queryDoc, 0, Int.MaxValue, prop[SourceConfiguration](_.created).sort(Desc) :: Nil)
  }

  def getSourceConfiguration(name: SID[SourceConfiguration]): RIO[WLD, Option[SourceConfiguration]] =
    sourceDAO.findById(name)

  def createSourceConfiguration(
      body: CreateSourceConfigurationRequest
  ): RIO[WLD, OperationResult[SID[SourceConfiguration]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      candidate <- sourceDAO.findById(body.name)
      _ <- ZIO.when(candidate.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a configuration that matches completely by name",
            candidate.map(_.name).toVector
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      sourceConf = body
        .into[SourceConfiguration]
        .withFieldConst(_.created, now)
        .transform
      res <- sourceDAO.insert(sourceConf)
      _ <- ZIO
        .foreachDiscard(sourceConf.init.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Initialization error", ex)
        }
        .forkDaemon
    } yield if (res > 0) OperationResult("success", sourceConf.name) else OperationResult("nothing inserted")

  def updateSourceConfiguration(
      name: SID[SourceConfiguration],
      body: UpdateSourceConfigurationRequest
  ): RIO[WLD, OperationResult[SID[SourceConfiguration]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      confPatch = body
        .into[SourceConfiguration]
        .withFieldConst(_.name, name)
        .withFieldConst(_.created, now)
        .transform
      res <- sourceDAO.patch(confPatch)
      _ <- (ZIO
        .foreachDiscard(confPatch.shutdown.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Error during deinitialization", ex)
        } *> ZIO
        .foreachDiscard(confPatch.init.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Initialization error", ex)
        }).forkDaemon
    } yield if (res.successful) OperationResult("success", confPatch.name) else OperationResult("nothing changed")

  def deleteSourceConfiguration(name: SID[SourceConfiguration]): RIO[WLD, OperationResult[String]] =
    for {
      scenarios <- scenarioDAO.findChunk(
        prop[Scenario](_.source) === name,
        0,
        Int.MaxValue
      )
      _ <- ZIO.when(scenarios.nonEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Scenarios ${scenarios.mkString(",")} are using source ${name}")
          )
        )
      )
      source <- sourceDAO.findById(name)
      _ <- ZIO.when(source.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Configuration of name $name does not exist")
          )
        )
      )
      _ <- sourceDAO.deleteById(name) // TODO: delete in transaction
      _ <- ZIO
        .foreachDiscard(source.get.shutdown.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Error during deinitialization", ex)
        }
        .forkDaemon
    } yield OperationResult("success", None)

  def fetchDestinationConfigurations(
      service: Option[NonEmptyString]
  ): RIO[WLD, Vector[DestinationDTO]] = {
    var queryDoc = BsonDocument()
    if (service.isDefined) {
      queryDoc = prop[Scenario](_.service) === service.get
    }
    destinationDAO.findChunkProjection[DestinationDTO](
      queryDoc,
      0,
      Int.MaxValue,
      prop[DestinationConfiguration](_.created).sort(Desc) :: Nil
    )
  }

  def getDestinationConfiguration(name: SID[DestinationConfiguration]): RIO[WLD, Option[DestinationConfiguration]] =
    destinationDAO.findById(name)

  def createDestinationConfiguration(
      body: CreateDestinationConfigurationRequest
  ): RIO[WLD, OperationResult[SID[DestinationConfiguration]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      candidate <- destinationDAO.findById(body.name)
      _ <- ZIO.when(candidate.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a configuration that matches completely by name",
            candidate.map(_.name).toVector
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      destinationConf = body
        .into[DestinationConfiguration]
        .withFieldConst(_.created, now)
        .transform
      res <- destinationDAO.insert(destinationConf)
      _ <- ZIO
        .foreachDiscard(destinationConf.init.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Initialization error", ex)
        }
        .forkDaemon
    } yield if (res > 0) OperationResult("success", destinationConf.name) else OperationResult("nothing inserted")

  def updateDestinationConfiguration(
      name: SID[DestinationConfiguration],
      body: UpdateDestinationConfigurationRequest
  ): RIO[WLD, OperationResult[SID[DestinationConfiguration]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      confPatch = body
        .into[DestinationConfiguration]
        .withFieldConst(_.name, name)
        .withFieldConst(_.created, now)
        .transform
      res <- destinationDAO.patch(confPatch)
      _ <- (ZIO
        .foreachDiscard(confPatch.shutdown.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Error during deinitialization", ex)
        } *> ZIO
        .foreachDiscard(confPatch.init.map(_.toVector).getOrElse(Vector.empty))(rm.execute)
        .catchSomeDefect { case NonFatal(ex) =>
          ZIO.fail(ex)
        }
        .catchSome { case NonFatal(ex) =>
          log.errorCause("Initialization error", ex)
        }).forkDaemon
    } yield if (res.successful) OperationResult("success", confPatch.name) else OperationResult("nothing changed")

  def fetchGrpcStubsV4(
      page: Option[Int],
      query: Option[String],
      labels: List[String]
  ): RIO[WLD, Vector[GrpcStub]] = {
    var queryDoc = prop[GrpcStub](_.scope) =/= Scope.Countdown.asInstanceOf[Scope] || prop[GrpcStub](_.times) > Option(
      refineMV[NonNegative](0)
    )
    if (query.isDefined) {
      val qs = query.get
      val q = prop[GrpcStub](_.id) === SID[GrpcStub](qs).asInstanceOf[SID[GrpcStub]] ||
        prop[GrpcStub](_.name).regex(qs, "i") ||
        prop[GrpcStub](_.methodDescriptionId) === SID[GrpcMethodDescription](qs).asInstanceOf[SID[GrpcMethodDescription]]
      queryDoc = queryDoc && q
    }
    if (labels.nonEmpty) {
      queryDoc = queryDoc && (prop[GrpcStub](_.labels).containsAll(labels))
    }
    grpcStubDAO.findChunk(queryDoc, page.getOrElse(0) * 20, 20, prop[GrpcStub](_.created).sort(Desc))
  }

  def createGrpcStubV4(body: CreateGrpcStubRequestV4): RIO[WLD, OperationResult[SID[GrpcStub]]] =
    for {
      methodDescription <- grpcMethodDescriptionDAO
        .findById(body.methodDescriptionId)
        .someOrFail {
          ValidationError(
            Vector(s"Method description for ${body.methodDescriptionId} does not exist")
          )
        }
      requestPkg   = GrpcMethodDescription.PackagePrefix(methodDescription.requestSchema)
      requestTypes = GrpcMethodDescription.makeDictTypes(requestPkg, methodDescription.requestSchema.schemas).toMap
      rootFields <- GrpcMethodDescription.getRootFields(requestPkg.resolve(methodDescription.requestClass), requestTypes)
      _ <- ZIO.foreachParDiscard(body.requestPredicates.definition.keys)(
        GrpcStub.validateOptics(_, requestTypes, rootFields)
      )
      responsePkg   = GrpcMethodDescription.PackagePrefix(methodDescription.responseSchema)
      responseTypes = GrpcMethodDescription.makeDictTypes(responsePkg, methodDescription.responseSchema.schemas).toMap
      _ <- GrpcMethodDescription.getRootFields(responsePkg.resolve(methodDescription.responseClass), responseTypes)
      candidates0 <- grpcStubDAO.findChunk(
        prop[GrpcStub](_.methodDescriptionId) === methodDescription.id,
        0,
        Integer.MAX_VALUE
      )
      candidates = candidates0
        .filter(_.requestPredicates.definition == body.requestPredicates.definition)
        .filter(_.state == body.state)
      _ <- ZIO.when(candidates.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a stub or stubs that match completely in terms of conditions and type",
            candidates.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      stub = body
        .into[GrpcStub]
        .withFieldComputed(_.id, _ => SID.random[GrpcStub])
        .withFieldConst(_.methodDescriptionId, methodDescription.id)
        .withFieldConst(_.created, now)
        .transform
      vr = GrpcStub.validationRules(stub)
      _   <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- grpcStubDAO.insert(stub)
      _   <- labelDAO.ensureLabels(methodDescription.service.value, stub.labels.to(Vector))
    } yield if (res > 0) OperationResult("success", stub.id) else OperationResult("nothing inserted")

  def updateGrpcStubV4(
      id: SID[GrpcStub],
      body: UpdateGrpcStubRequestV4
  ): RIO[WLD, OperationResult[SID[GrpcStub]]] =
    for {
      methodDescription <- grpcMethodDescriptionDAO.findById(body.methodDescriptionId).someOrFail(
        ValidationError(
          Vector(s"Can't find a method description ${body.methodDescriptionId}")
        )
      )
      requestPkg   = GrpcMethodDescription.PackagePrefix(methodDescription.requestSchema)
      requestTypes = GrpcMethodDescription.makeDictTypes(requestPkg, methodDescription.requestSchema.schemas).toMap
      rootFields <- GrpcMethodDescription.getRootFields(requestPkg.resolve(methodDescription.requestClass), requestTypes)
      _ <- ZIO.foreachParDiscard(body.requestPredicates.definition.keys)(
        GrpcStub.validateOptics(_, requestTypes, rootFields)
      )
      candidates0 <- grpcStubDAO.findChunk(
        where(_._id =/= id) && prop[GrpcStub](_.methodDescriptionId) === methodDescription.id,
        0,
        Integer.MAX_VALUE
      )
      candidates = candidates0
        .filter(_.requestPredicates.definition == body.requestPredicates.definition)
        .filter(_.state == body.state)
      _ <- ZIO.when(candidates.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a stub or stubs that match completely in terms of conditions and type",
            candidates.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      stubPatch = body
        .into[GrpcStubPatch]
        .withFieldConst(_.id, id)
        .transform
      stub = stubPatch
        .into[GrpcStub]
        .withFieldConst(_.created, now)
        .transform
      vr = GrpcStub.validationRules(stub)
      _ <- ZIO.when(vr.nonEmpty)(ZIO.fail(ValidationError(vr)))
      res <- grpcStubDAO.patch(stubPatch)
      _ <- labelDAO.ensureLabels(methodDescription.service.value, stub.labels.to(Vector))
    } yield if (res.successful) OperationResult("success", stub.id) else OperationResult("nothing updated")

  def getGrpcStubV4(id: SID[GrpcStub]): RIO[WLD, Option[GrpcStub]] =
    grpcStubDAO.findById(id)

  def deleteGrpcStubV4(id: SID[GrpcStub]): RIO[WLD, OperationResult[String]] =
    ZIO.ifZIO(grpcStubDAO.deleteById(id).map(_ > 0))(
      ZIO.succeed(OperationResult("success")),
      ZIO.succeed(OperationResult("nothing deleted"))
    )

  def fetchGrpcMethodDescriptions(
      page: Option[Int],
      query: Option[String],
      service: Option[String],
  ): RIO[WLD, Vector[GrpcMethodDescription]] = {
    var queryDoc = Expression.empty: Expression[GrpcMethodDescription]
    if (query.isDefined) {
      val qs = query.get
      val q =
        prop[GrpcMethodDescription](_.id) === SID[GrpcMethodDescription](qs).asInstanceOf[SID[GrpcMethodDescription]] ||
          prop[GrpcMethodDescription](_.methodName).regex(qs, "i")
      queryDoc = queryDoc && q
    }
    val refService = service.flatMap(refineV[NonEmpty](_).toOption)
    if (refService.isDefined) {
      queryDoc = queryDoc && (prop[GrpcMethodDescription](_.service) === refService.get)
    }
    grpcMethodDescriptionDAO.findChunk(
      queryDoc,
      page.getOrElse(0) * 20,
      20,
      prop[GrpcMethodDescription](_.created).sort(Desc)
    )
  }

  def createGrpcMethodDescription(
      body: CreateGrpcMethodDescriptionRequest
  ): RIO[WLD, OperationResult[SID[GrpcMethodDescription]]] = {
    val requestSchemaBytes  = body.requestCodecs.asArray
    val responseSchemaBytes = body.responseCodecs.asArray
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Service ${body.service} does not exist")
          )
        )
      )
      requestSchema <- protobufSchemaResolver.parseDefinitionFrom(requestSchemaBytes)
      requestPkg   = GrpcMethodDescription.PackagePrefix(requestSchema)
      requestTypes = GrpcMethodDescription.makeDictTypes(requestPkg, requestSchema.schemas).toMap
      rootFields     <- GrpcMethodDescription.getRootFields(requestPkg.resolve(body.requestClass), requestTypes)
      responseSchema <- protobufSchemaResolver.parseDefinitionFrom(responseSchemaBytes)
      responsePkg   = GrpcMethodDescription.PackagePrefix(responseSchema)
      responseTypes = GrpcMethodDescription.makeDictTypes(responsePkg, responseSchema.schemas).toMap
      _         <- GrpcMethodDescription.getRootFields(responsePkg.resolve(body.responseClass), responseTypes)
      candidates <- grpcMethodDescriptionDAO.findChunk(
        where(_._id =/= body.id) &&
          prop[GrpcMethodDescription](_.methodName) === body.methodName,
        0,
        Int.MaxValue
      )
      _ <- ZIO.when(candidates.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a method description that matches completely by method name",
            candidates.map(_.id)
          )
        )
      )
      now <- ZIO.clockWith(_.instant)
      methodDescription = body
        .into[GrpcMethodDescription]
        .withFieldConst(_.requestSchema, requestSchema)
        .withFieldConst(_.responseSchema, responseSchema)
        .withFieldConst(_.created, now)
        .transform
      res <- grpcMethodDescriptionDAO.insert(methodDescription)
    } yield if (res > 0) OperationResult("success", methodDescription.id) else OperationResult("nothing inserted")
  }

  def updateGrpcMethodDescription(
      id: SID[GrpcMethodDescription],
      body: UpdateGrpcMethodDescriptionRequest
  ): RIO[WLD, OperationResult[SID[GrpcMethodDescription]]] =
    for {
      service <- serviceDAO.findById(body.service)
      _ <- ZIO.when(service.isEmpty)(
        ZIO.fail(
          ValidationError(
            Vector(s"Can't find a service for ${body.methodName}")
          )
        )
      )
      candidates <- grpcMethodDescriptionDAO.findChunk(
        where(_._id =/= id) &&
          prop[GrpcMethodDescription](_.methodName) === body.methodName,
        0,
        Int.MaxValue
      )
      _ <- ZIO.when(candidates.nonEmpty)(
        ZIO.fail(
          DuplicationError(
            "There exists a method description that match completely in terms of method name",
            candidates.map(_.id)
          )
        )
      )
      requestSchema <- protobufSchemaResolver.parseDefinitionFrom(body.requestCodecs.asArray)
      requestPkg   = GrpcMethodDescription.PackagePrefix(requestSchema)
      requestTypes = GrpcMethodDescription.makeDictTypes(requestPkg, requestSchema.schemas).toMap
      rootFields     <- GrpcMethodDescription.getRootFields(requestPkg.resolve(body.requestClass), requestTypes)
      responseSchema <- protobufSchemaResolver.parseDefinitionFrom(body.responseCodecs.asArray)
      responsePkg   = GrpcMethodDescription.PackagePrefix(responseSchema)
      responseTypes = GrpcMethodDescription.makeDictTypes(responsePkg, responseSchema.schemas).toMap
      _ <- GrpcMethodDescription.getRootFields(responsePkg.resolve(body.responseClass), responseTypes)
      methodDescriptionPatch = body
        .into[GrpcMethodDescriptionPatch]
        .withFieldConst(_.id, id)
        .withFieldConst(_.requestSchema, requestSchema)
        .withFieldConst(_.responseSchema, responseSchema)
        .transform
      res <- grpcMethodDescriptionDAO.patch(methodDescriptionPatch)
    } yield if (res.successful) OperationResult("success", id) else OperationResult("nothing updated")

  def getGrpcMethodDescription(id: SID[GrpcMethodDescription]): RIO[WLD, Option[GrpcMethodDescription]] =
    grpcMethodDescriptionDAO.findById(id)

  def deleteGrpcMethodDescription(id: SID[GrpcMethodDescription]): RIO[WLD, OperationResult[String]] =
    for {
      stub <- grpcStubDAO.findOne(prop[GrpcStub](_.methodDescriptionId) === id)
      _ <- ZIO.when(stub.isDefined)(
        ZIO.fail(
          ValidationError(
            Vector("There exists a stub or stubs for method description. First, delete the stubs")
          )
        )
      )
      res <- ZIO.ifZIO(grpcMethodDescriptionDAO.deleteById(id).map(_ > 0))(
        ZIO.succeed(OperationResult[String]("success")),
        ZIO.succeed(OperationResult[String]("nothing deleted"))
      )
    } yield res
}

object AdminApiHandler {
  val live = ZLayer {
    for {
      hsd              <- ZIO.service[HttpStubDAO[Task]]
      sd               <- ZIO.service[ScenarioDAO[Task]]
      std              <- ZIO.service[PersistentStateDAO[Task]]
      srd              <- ZIO.service[ServiceDAO[Task]]
      ld               <- ZIO.service[LabelDAO[Task]]
      gsd              <- ZIO.service[GrpcStubDAO[Task]]
      gmdd             <- ZIO.service[GrpcMethodDescriptionDAO[Task]]
      srcd             <- ZIO.service[SourceConfigurationDAO[Task]]
      dstd             <- ZIO.service[DestinationConfigurationDAO[Task]]
      ftch             <- ZIO.service[SDFetcher]
      stubResolver     <- ZIO.service[StubResolver]
      scenarioResolver <- ZIO.service[ScenarioResolver]
      protoResolver    <- ZIO.service[ProtobufSchemaResolver]
      aes              <- ZIO.service[AES]
      rm               <- ZIO.service[ResourceManager]
    } yield new AdminApiHandler(
      hsd,
      sd,
      std,
      srd,
      ld,
      gsd,
      gmdd,
      srcd,
      dstd,
      ftch,
      stubResolver,
      scenarioResolver,
      protoResolver,
      rm
    )(aes)
  }
}
