package ru.tinkoff.tcb.utils.sandboxing

import scala.util.Try
import scala.util.chaining.*

import neotype.*
import org.graalvm.polyglot.*

import ru.tinkoff.tcb.mockingbird.config.JsSandboxConfig
import ru.tinkoff.tcb.utils.resource.Resource
import ru.tinkoff.tcb.utils.sandboxing.conversion.*

class GraalJsSandbox(
    jsSandboxConfig: JsSandboxConfig,
    prelude: Option[String] = None
) {
  private val allowedClasses = GraalJsSandbox.DefaultAccess ++ jsSandboxConfig.allowedClasses
  private val preludeSource  = prelude.map(Source.create("js", _))

  def makeRunner(environment: Map[String, GValue.Type] = Map.empty): Resource[CodeRunner] =
    Resource
      .lean(
        Context
          .newBuilder("js")
          .allowHostAccess(HostAccess.ALL)
          .allowHostClassLookup((t: String) => allowedClasses(t))
          .option("engine.WarnInterpreterOnly", "false")
          .build()
          .tap { context =>
            context.getBindings("js").pipe { bindings =>
              for ((key, value) <- environment.view.mapValues(_.unwrap))
                bindings.putMember(key, value)
            }
            preludeSource.foreach(context.eval)
          }
      )(_.close())
      .map(ctx => (code: String) => Try(ctx.value.eval("js", code)).flatMap(_.toJson))
}

object GraalJsSandbox {
  val live: URLayer[Option[String] & JsSandboxConfig, GraalJsSandbox] = ZLayer {
    for {
      sandboxConfig <- ZIO.service[JsSandboxConfig]
      prelude       <- ZIO.service[Option[String]]
    } yield new GraalJsSandbox(sandboxConfig, prelude)
  }

  val DefaultAccess: Set[String] = Set(
    "java.lang.Byte",
    "java.lang.Boolean",
    "java.lang.Double",
    "java.lang.Float",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Math",
    "java.lang.Short",
    "java.lang.String",
    "java.math.BigDecimal",
    "java.math.BigInteger",
    "java.time.LocalDate",
    "java.time.LocalDateTime",
    "java.time.format.DateTimeFormatter",
    "java.util.List",
    "java.util.Map",
    "java.util.Random",
    "java.util.Set"
  )
}
