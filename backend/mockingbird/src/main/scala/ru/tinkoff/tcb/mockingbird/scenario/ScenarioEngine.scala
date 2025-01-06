package ru.tinkoff.tcb.mockingbird.scenario

import java.nio.charset.Charset
import java.util.Base64
import scala.util.Try
import scala.xml.Node

import io.circe.Json
import io.circe.syntax.*
import mouse.boolean.*
import mouse.option.*
import neotype.*
import oolong.bson.given
import org.mongodb.scala.bson.Document
import sttp.client4.{Backend as SttpBackend, *}
import sttp.client4.circe.*
import sttp.model.Method
import zio.interop.catz.core.*

import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.api.Tracing
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.dal.ScenarioDAO
import ru.tinkoff.tcb.mockingbird.error.CallbackError
import ru.tinkoff.tcb.mockingbird.error.ScenarioExecError
import ru.tinkoff.tcb.mockingbird.error.ScenarioSearchError
import ru.tinkoff.tcb.mockingbird.misc.Renderable.ops.*
import ru.tinkoff.tcb.mockingbird.model.Callback
import ru.tinkoff.tcb.mockingbird.model.CallbackResponseMode
import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.mockingbird.model.HttpCallback
import ru.tinkoff.tcb.mockingbird.model.JsonCallbackRequest
import ru.tinkoff.tcb.mockingbird.model.JsonOutput
import ru.tinkoff.tcb.mockingbird.model.MessageCallback
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.RawOutput
import ru.tinkoff.tcb.mockingbird.model.ScenarioOutput
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.mockingbird.model.XMLCallbackRequest
import ru.tinkoff.tcb.mockingbird.model.XmlOutput
import ru.tinkoff.tcb.mockingbird.stream.SDFetcher
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox
import ru.tinkoff.tcb.utils.transformation.json.*
import ru.tinkoff.tcb.utils.transformation.string.*
import ru.tinkoff.tcb.utils.transformation.xml.*
import ru.tinkoff.tcb.utils.xml.SafeXML
import ru.tinkoff.tcb.utils.xml.XMLStringSyntax
import ru.tinkoff.tcb.utils.xml.emptyNode
import ru.tinkoff.tcb.utils.xttp.*

trait CallbackEngine {
  def recurseCallback(
      state: PersistentState,
      callback: Callback,
      data: Json,
      xdata: Node
  ): RIO[WLD, Unit]
}

final class ScenarioEngine(
    scenarioDAO: ScenarioDAO[Task],
    stateDAO: PersistentStateDAO[Task],
    resolver: ScenarioResolver,
    fetcher: SDFetcher,
    implicit val jsSandbox: GraalJsSandbox,
    private val httpBackend: SttpBackend[Task]
) extends CallbackEngine {
  private val log = MDCLogging.`for`[WLD](this)

  def perform(source: SID[SourceConfiguration], message: String): RIO[WLD, Unit] = {
    val f = resolver.findScenarioAndState(source, message)

    for {
      _ <- Tracing.update(_.addToPayload(("source" -> source)))
      _ <- log.info("Got message from {}", source)
      (scenario, stateOp) <- f(Scope.Countdown)
        .filterOrElse(_.isDefined)(f(Scope.Ephemeral).filterOrElse(_.isDefined)(f(Scope.Persistent)))
        .someOrFail(ScenarioSearchError(s"Failed to match a scenario for the message from $source"))
      _ <- log.info("Executing scenario '{}'", scenario.name)
      seed     = scenario.seed.map(_.eval.useAsIs)
      bodyJson = scenario.input.extractJson(message)
      bodyXml  = scenario.input.extractXML(message)
      state <- ZIO.fromOption(stateOp).orElse(PersistentState.fresh)
      data        = Json.obj("message" := bodyJson, "state" := state.data, "seed" := seed)
      xdata: Node = <wrapper>{bodyXml.getOrElse(emptyNode)}</wrapper>
      _ <-
        scenario.persist
          .cata(
            spec => stateDAO.upsertBySpec(state.id, spec.fill(data).fill(xdata)).map(_.successful),
            ZIO.succeed(true)
          )
      _ <- scenario.persist
        .map(_.keys.map(_.path).filter(_.startsWith("_")).toVector)
        .filter(_.nonEmpty)
        .fold(ZIO.attempt(()))(_.traverse_(stateDAO.createIndexForDataField))
      dests <- fetcher.getDestinations
      _ <- ZIO.when(scenario.destination.isDefined && !dests.exists(_.name == scenario.destination.get))(
        ZIO.fail(ScenarioExecError(s"Destination with the name ${scenario.destination.get} is not configured"))
      )
      destOut = scenario.destination.flatMap(dn => dests.find(_.name == dn)) zip scenario.output
      _ <- ZIO.when(destOut.isDefined) {
        val (dest, out) = destOut.get
        sendTo(dest, out, data, xdata)
      }
      _ <- ZIO.when(scenario.scope == Scope.Countdown)(
        scenarioDAO.updateById(scenario.id, Document("$inc" -> Document("times" -> -1.bson)))
      )
      _ <- ZIO.when(scenario.callback.isDefined)(recurseCallback(state, scenario.callback.get, data, xdata))
    } yield ()
  }

  def recurseCallback(
      state: PersistentState,
      callback: Callback,
      data: Json,
      xdata: Node
  ): RIO[WLD, Unit] =
    callback match {
      case MessageCallback(destinationId, output, callback, delay) =>
        for {
          _     <- ZIO.when(delay.isDefined)(ZIO.sleep(Duration.fromScala(delay.get)))
          _     <- log.info("Executing MessageCallback with destinationId={}", destinationId)
          dests <- fetcher.getDestinations
          _ <- ZIO.when(!dests.exists(_.name == destinationId))(
            ZIO.fail(CallbackError(s"Destination with the name $destinationId is not configured"))
          )
          destination = dests.find(_.name == destinationId).get
          _ <- sendTo(destination, output, data, xdata)
          _ <- ZIO.when(callback.isDefined)(recurseCallback(state, callback.get, data, xdata))
        } yield ()
      case HttpCallback(request, responseMode, persist, callback, delay) =>
        for {
          _ <- ZIO.when(delay.isDefined)(ZIO.sleep(Duration.fromScala(delay.get)))
          requestUrl = request.url.value.substitute(data, xdata)
          _ <- log.info("Executing HttpCallback to {}", requestUrl)
          res <-
            basicRequest
              .headers(request.headers)
              .method(Method(request.method.entryName), uri"$requestUrl")
              .pipe(r =>
                request match {
                  case JsonCallbackRequest(_, _, _, body) =>
                    r.body(body.substitute(data).map(_.substitute(xdata)).use(_.noSpaces))
                  case XMLCallbackRequest(_, _, _, body) =>
                    r.body(body.unwrap.substitute(data).map(_.substitute(xdata)).use(_.mkString))
                  case _ => r
                }
              )
              .response(asString)
              .send(httpBackend)
              .filterOrElseWith(_.isSuccess)(r => ZIO.fail(CallbackError(s"$requestUrl responded with error: $r")))
          bodyStr = res.body.getOrElse(throw new UnsupportedOperationException("Can't happen"))
          jsonBody =
            responseMode
              .contains(CallbackResponseMode.Json)
              .option(io.circe.parser.parse(bodyStr).toOption)
              .flatten
          xmlBody =
            responseMode
              .contains(CallbackResponseMode.Xml)
              .option(Try(SafeXML.loadString(bodyStr)).toOption)
              .flatten
          data1  = jsonBody.cata(j => data.mapObject(("req" -> j) +: _), data)
          xdata1 = xmlBody.getOrElse(xdata)
          _ <- ZIO.when(persist.isDefined) {
            stateDAO.upsertBySpec(state.id, persist.get.fill(data1).fill(xdata1))
          }
          _ <- ZIO.when(callback.isDefined)(recurseCallback(state, callback.get, data1, xdata1))
        } yield ()
    }

  private def sendTo(dest: DestinationConfiguration, out: ScenarioOutput, data: Json, xdata: Node): RIO[WLD, Unit] =
    ZIO.when(out.delay.isDefined)(ZIO.sleep(Duration.fromScala(out.delay.get))) *> basicRequest
      .pipe(rt =>
        dest.request.body.fold {
          rt.body(
            out match {
              case RawOutput(payload, _) => payload
              case JsonOutput(payload, _, isT) =>
                if (isT) payload.substitute(data).map(_.substitute(xdata)).use(_.noSpaces) else payload.noSpaces
              case XmlOutput(payload, _, isT) =>
                if (isT) payload.unwrap.substitute(data).map(_.substitute(xdata)).use(_.mkString) else payload.asString
            }
          )
        } { drb =>
          val bodyJson = out match {
            case RawOutput(payload, _) => Json.fromString(payload)
            case JsonOutput(payload, _, isT) =>
              if (isT) payload.substitute(data).map(_.substitute(xdata)).useAsIs else payload
            case XmlOutput(payload, _, isT) =>
              if (isT)
                Json.fromString(payload.unwrap.substitute(data).map(_.substitute(xdata)).use(_.mkString))
              else Json.fromString(payload.asString)
          }

          rt.body(
            drb
              .substitute(
                if (dest.request.stringifybody.contains(true)) Json.obj("_message" := bodyJson.noSpaces)
                else if (dest.request.encodeBase64.contains(true))
                  Json.obj(
                    "_message" := bodyJson.asString.map(b64Enc).getOrElse(b64Enc(bodyJson.noSpaces))
                  )
                else Json.obj("_message" := bodyJson)
              )
              .useAsIs
          )
        }
      )
      .headersReplacing(dest.request.headers.view.mapValues(_.unwrap).toMap)
      .method(Method(dest.request.method.entryName), uri"${dest.request.url}")
      .response(asString)
      .send(httpBackend)
      .filterOrElseWith(_.isSuccess)(r =>
        ZIO.fail(ScenarioExecError(s"Destination ${dest.name} responded with error: $r"))
      ) *>
      log.info("Response sent to {}", dest.name)

  private def b64Enc(s: String): String =
    new String(Base64.getEncoder.encode(s.getBytes(Charset.defaultCharset())), Charset.defaultCharset())
}

object ScenarioEngine {
  val live = ZLayer {
    for {
      sd         <- ZIO.service[ScenarioDAO[Task]]
      psd        <- ZIO.service[PersistentStateDAO[Task]]
      resolver   <- ZIO.service[ScenarioResolver]
      fetcher    <- ZIO.service[SDFetcher]
      jsSandbox  <- ZIO.service[GraalJsSandbox]
      sttpClient <- ZIO.service[SttpBackend[Task]]
    } yield new ScenarioEngine(sd, psd, resolver, fetcher, jsSandbox, sttpClient)
  }
}
