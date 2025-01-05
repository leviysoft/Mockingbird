package ru.tinkoff.tcb.mockingbird.api

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.xml.Node

import eu.timepit.refined.*
import io.circe.Json
import io.circe.syntax.*
import mouse.boolean.*
import mouse.option.*
import oolong.bson.given
import org.mongodb.scala.bson.Document
import sttp.client4.{Backend as SttpBackend, *}
import sttp.client4.circe.*
import sttp.model.HeaderNames
import sttp.model.Method
import sttp.model.QueryParams
import zio.interop.catz.core.*

import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.config.ProxyConfig
import ru.tinkoff.tcb.mockingbird.dal.HttpStubDAO
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.error.*
import ru.tinkoff.tcb.mockingbird.misc.Renderable.ops.*
import ru.tinkoff.tcb.mockingbird.model.AbsentRequestBody
import ru.tinkoff.tcb.mockingbird.model.BinaryResponse
import ru.tinkoff.tcb.mockingbird.model.ByteArray
import ru.tinkoff.tcb.mockingbird.model.HttpMethod
import ru.tinkoff.tcb.mockingbird.model.HttpStatusCodeRange
import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mockingbird.model.HttpStubResponse
import ru.tinkoff.tcb.mockingbird.model.JsonProxyResponse
import ru.tinkoff.tcb.mockingbird.model.MultipartRequestBody
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.ProxyResponse
import ru.tinkoff.tcb.mockingbird.model.RawResponse
import ru.tinkoff.tcb.mockingbird.model.RequestBody
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.mockingbird.model.SimpleRequestBody
import ru.tinkoff.tcb.mockingbird.model.XmlProxyResponse
import ru.tinkoff.tcb.mockingbird.scenario.CallbackEngine
import ru.tinkoff.tcb.mockingbird.scenario.ScenarioEngine
import ru.tinkoff.tcb.utils.any.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.regex.*
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox
import ru.tinkoff.tcb.utils.transformation.json.*
import ru.tinkoff.tcb.utils.transformation.string.*
import ru.tinkoff.tcb.utils.transformation.xml.*
import ru.tinkoff.tcb.utils.xml.SafeXML
import ru.tinkoff.tcb.utils.xml.emptyNode
import ru.tinkoff.tcb.utils.xttp.xml.asXML
import ru.tinkoff.tcb.xpath.SXpath

final class PublicApiHandler(
    stubDAO: HttpStubDAO[Task],
    stateDAO: PersistentStateDAO[Task],
    resolver: StubResolver,
    engine: CallbackEngine,
    implicit val jsSandbox: GraalJsSandbox,
    private val httpBackend: SttpBackend[Task],
    proxyConfig: ProxyConfig
) {
  private val log = MDCLogging.`for`[WLD](this)

  def exec(
      method: HttpMethod,
      path: String,
      headers: Map[String, String],
      query: Seq[(String, Seq[String])],
      body: RequestBody
  ): RIO[WLD, HttpStubResponse] = {
    val queryObject = queryParamsToJsonObject(query)
    val f           = resolver.findStubAndState(method, path, headers, queryObject, body)

    for {
      _ <- Tracing.update(_.addToPayload("path" -> path, "method" -> method.entryName))
      (stub, stateOp) <- f(Scope.Countdown)
        .filterOrElse(_.isDefined)(f(Scope.Ephemeral).filterOrElse(_.isDefined)(f(Scope.Persistent)))
        .someOrFail(StubSearchError(s"Can't find any stub for [$method] $path"))
      _ <- Tracing.update(_.addToPayload("name" -> stub.name))
      seed     = stub.seed.map(_.eval.useAsIs)
      srb      = SimpleRequestBody.subset.getOption(body).map(_.value)
      bodyJson = stub.request.extractJson(body)
      bodyXml  = stub.request.extractXML(body)
      groups = for {
        pattern <- stub.pathPattern
        mtch    <- pattern.findFirstMatchIn(path)
      } yield pattern.groups.map(g => g -> mtch.group(g)).to(Map)
      state <- ZIO.fromOption(stateOp).orElse(PersistentState.fresh)
      data = Json.obj(
        "seed" := seed,
        "req" := bodyJson,
        "state" := state.data,
        "query" := queryObject,
        "pathParts" := groups,
        "extracted" := bodyXml.map(stub.request.runXmlExtractors),
        "headers" := headers
      )
      xdata: Node = <wrapper>{bodyXml.getOrElse(emptyNode)}</wrapper>
      persist     = stub.persist
      _ <- persist
        .cata(spec => stateDAO.upsertBySpec(state.id, spec.fill(data).fill(xdata)).map(_.successful), ZIO.succeed(true))
      _ <- persist
        .map(_.keys.map(_.path).filter(_.startsWith("_")).toVector)
        .filter(_.nonEmpty)
        .cata(_.traverse(stateDAO.createIndexForDataField), ZIO.unit)
      response <- stub.response match {
        case ProxyResponse(uri, delay, timeout) =>
          proxyRequest(method, headers, query, body)(uri.substitute(data), delay, timeout)
        case JsonProxyResponse(uri, patch, delay, timeout) =>
          jsonProxyRequest(method, headers, query, body, data)(uri.substitute(data), patch, delay, timeout)
        case XmlProxyResponse(uri, patch, delay, timeout) =>
          xmlProxyRequest(
            method,
            headers,
            query,
            body,
            data,
            srb.map(SafeXML.loadString).getOrElse(scala.xml.Comment("No data"))
          )(
            uri.substitute(data),
            patch,
            delay,
            timeout
          )
        case _ =>
          ZIO.succeed(
            stub.response
              .applyIf(_.isTemplate)(
                HttpStubResponse.jsonBody
                  .updateF(_.substitute(data).map(_.substitute(xdata)).use(_.pure[Id]))
                  .andThen(HttpStubResponse.xmlBody.updateF(_.substitute(data).map(_.substitute(xdata)).useAsIs))
              )
              .applyIf(HttpStubResponse.headers.getOption(_).exists(_.values.exists(_.isTemplate)))(
                HttpStubResponse.headers.updateF(_.view.mapValues(_.substitute(data, xdata)).toMap)
              )
          )
      }
      _ <- ZIO.when(stub.scope == Scope.Countdown)(
        stubDAO.updateById(stub.id, Document("$inc" -> Document("times" -> -1.bson)))
      )
      _ <- ZIO.when(stub.callback.isDefined)(
        engine
          .recurseCallback(state, stub.callback.get, data, xdata)
          .catchSomeDefect { case NonFatal(ex) =>
            ZIO.fail(ex)
          }
          .catchSome { case NonFatal(ex) =>
            log.errorCause("Error during callback execution", ex)
          }
          .forkDaemon
      )
    } yield response
  }

  // The cases when Mockingbird implicitly changes answer:
  // 1. If the answer is compressed, mockingbird decompressed it.
  // 2. When proxy mock contains substitutions, they change the answer and the result have another length.
  // In these cases, these headers they make the modified response is incorrect, so we need to remove them.
  private def headersDependingOnContentLength = Seq(HeaderNames.ContentEncoding, HeaderNames.ContentLength)

  private def proxyRequest(
      method: HttpMethod,
      headers: Map[String, String],
      query: Seq[(String, Seq[String])],
      body: RequestBody
  )(uri: String, delay: Option[FiniteDuration], timeout: Option[FiniteDuration]): RIO[WLD, HttpStubResponse] = {
    val requestUri = uri"$uri".addParams(QueryParams(query))
    for {
      _ <- log.debug(s"Received headers: ${headers.keys.mkString(", ")}")
      // Potentially, we want to pass the request and response as is. If the client wants this
      // response to be encoded, it sets `Accept-Encoding` itself. Also, by default, we don't
      // unpack the response here. It's the reason here we use emptyRequest.
      req = basicRequest
        .headers(headers -- proxyConfig.excludedRequestHeaders)
        .method(Method(method.entryName), requestUri)
        .pipe(rt =>
          body match {
            case AbsentRequestBody        => rt
            case SimpleRequestBody(value) => rt.body(value)
            case MultipartRequestBody(value) =>
              rt.multipartBody(
                value.map(part =>
                  multipart(part.name, part.body)
                    .pipe(newPart =>
                      part.headers.foldLeft(newPart) { case (acc, header) =>
                        acc.header(header.name, header.value, true)
                      }
                    )
                )
              )
          }
        )
        .pipe(r => proxyConfig.disableAutoDecompressForRaw.fold(r.disableAutoDecompression, r))
        .response(asByteArrayAlways)
      _ <- log.debug("Executing request: {}", req.toCurl).when(proxyConfig.logOutgoingRequests)
      response <- req
        .readTimeout(timeout.getOrElse(1.minute.asScala))
        .send(httpBackend)
    } yield BinaryResponse(
      refineV[HttpStatusCodeRange].unsafeFrom(response.code.code),
      response.headers
        .filterNot(h => proxyConfig.excludedResponseHeaders(h.name))
        .pipe { hs =>
          proxyConfig.disableAutoDecompressForRaw.fold(hs, hs.filterNot(h => headersDependingOnContentLength.exists(h.is)))
        }
        .map(h => h.name -> h.value)
        .toMap,
      ByteArray(response.body),
      delay
    )
  }

  private def jsonProxyRequest(
      method: HttpMethod,
      headers: Map[String, String],
      query: Seq[(String, Seq[String])],
      body: RequestBody,
      data: Json
  )(
      uri: String,
      patch: Map[JsonOptic, String],
      delay: Option[FiniteDuration],
      timeout: Option[FiniteDuration]
  ): RIO[WLD, HttpStubResponse] = {
    val requestUri = uri"$uri".addParams(QueryParams(query))
    for {
      _ <- log.debug(s"Received headers: ${headers.keys.mkString(", ")}")
      req = basicRequest
        .headers(
          // The basicRequest adds `Accept-Encoding`, so we should remove the same incoming header
          headers.filterNot(_._1.equalsIgnoreCase(HeaderNames.AcceptEncoding)) -- proxyConfig.excludedRequestHeaders
        )
        .method(Method(method.entryName), requestUri)
        .pipe(rt =>
          body match {
            case AbsentRequestBody        => rt
            case SimpleRequestBody(value) => rt.body(value)
            case MultipartRequestBody(value) =>
              rt.multipartBody(
                value.map(part =>
                  multipart(part.name, part.body)
                    .pipe(newPart =>
                      part.headers.foldLeft(newPart) { case (acc, header) =>
                        acc.header(header.name, header.value, true)
                      }
                    )
                )
              )
          }
        )
        .response(asJsonAlways[Json])
      _ <- log.debug("Executing request: {}", req.toCurl).when(proxyConfig.logOutgoingRequests)
      response <- req
        .readTimeout(timeout.getOrElse(1.minute.asScala))
        .send(httpBackend)
    } yield response.body match {
      case Right(jsonResponse) =>
        RawResponse(
          refineV[HttpStatusCodeRange].unsafeFrom(response.code.code),
          response.headers
            .filterNot(h => proxyConfig.excludedResponseHeaders(h.name))
            .filterNot(h => headersDependingOnContentLength.exists(h.is))
            .map(h => h.name -> h.value)
            .toMap,
          jsonResponse.patch(data, patch).use(_.noSpaces),
          delay
        )
      case Left(error) =>
        RawResponse(refineV[HttpStatusCodeRange].unsafeFrom(500), Map(), error.body, delay)
    }
  }

  private def xmlProxyRequest(
      method: HttpMethod,
      headers: Map[String, String],
      query: Seq[(String, Seq[String])],
      body: RequestBody,
      jData: Json,
      xData: Node
  )(
      uri: String,
      patch: Map[SXpath, String],
      delay: Option[FiniteDuration],
      timeout: Option[FiniteDuration]
  ): RIO[WLD, HttpStubResponse] = {
    val requestUri = uri"$uri".addParams(QueryParams(query))
    for {
      _ <- log.debug(s"Received headers: ${headers.keys.mkString(", ")}")
      req = basicRequest
        .headers(
          // The basicRequest adds `Accept-Encoding`, so we should remove the same incoming header
          headers.filterNot(_._1.equalsIgnoreCase(HeaderNames.AcceptEncoding)) -- proxyConfig.excludedRequestHeaders
        )
        .method(Method(method.entryName), requestUri)
        .pipe(rt =>
          body match {
            case AbsentRequestBody        => rt
            case SimpleRequestBody(value) => rt.body(value)
            case MultipartRequestBody(value) =>
              rt.multipartBody(
                value.map(part =>
                  multipart(part.name, part.body)
                    .pipe(newPart =>
                      part.headers.foldLeft(newPart) { case (acc, header) =>
                        acc.header(header.name, header.value, true)
                      }
                    )
                )
              )
          }
        )
        .response(asXML)
      _ <- log.debug("Executing request: {}", req.toCurl).when(proxyConfig.logOutgoingRequests)
      response <- req
        .readTimeout(timeout.getOrElse(1.minute.asScala))
        .send(httpBackend)
    } yield response.body match {
      case Right(xmlResponse) =>
        RawResponse(
          refineV[HttpStatusCodeRange].unsafeFrom(response.code.code),
          response.headers
            .filterNot(h => proxyConfig.excludedResponseHeaders(h.name))
            .filterNot(h => headersDependingOnContentLength.exists(h.is))
            .map(h => h.name -> h.value)
            .toMap,
          xmlResponse.patchFromValues(jData, xData, patch.map { case (k, v) => k.toZoom -> v }).toString,
          delay
        )
      case Left(error) =>
        RawResponse(refineV[HttpStatusCodeRange].unsafeFrom(500), Map(), error, delay)
    }
  }
}

object PublicApiHandler {
  val live = ZLayer {
    for {
      hsd        <- ZIO.service[HttpStubDAO[Task]]
      ssd        <- ZIO.service[PersistentStateDAO[Task]]
      resolver   <- ZIO.service[StubResolver]
      engine     <- ZIO.service[ScenarioEngine]
      jsSandbox  <- ZIO.service[GraalJsSandbox]
      sttpClient <- ZIO.service[SttpBackend[Task]]
      proxyCfg   <- ZIO.service[ProxyConfig]
    } yield new PublicApiHandler(hsd, ssd, resolver, engine, jsSandbox, sttpClient, proxyCfg)
  }
}
