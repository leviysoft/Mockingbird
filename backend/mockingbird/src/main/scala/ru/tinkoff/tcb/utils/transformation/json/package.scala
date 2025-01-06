package ru.tinkoff.tcb.utils.transformation

import scala.util.Failure
import scala.util.Success
import scala.util.control.TailCalls
import scala.util.control.TailCalls.TailRec
import scala.xml.Node

import io.circe.Json
import io.circe.JsonNumber as JNumber

import ru.tinkoff.tcb.utils.circe.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.json.json2StringFolder
import ru.tinkoff.tcb.utils.regex.OneOrMore
import ru.tinkoff.tcb.utils.resource.Resource
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox
import ru.tinkoff.tcb.utils.sandboxing.conversion.circe2js
import ru.tinkoff.tcb.utils.transformation.xml.nodeTemplater

package object json {
  private val JORx = """\$([\:~])?\{([\p{L}\d\.\[\]\-_]+)\}""".r

  private object JORxs extends OneOrMore(JORx)

  private object JOptic {
    def unapply(defn: String): Option[(Option[String], JsonOptic)] =
      defn match {
        case JORx(null, path) => Some(None -> JsonOptic.fromPathString(path))
        case JORx(sign, path) => Some(Some(sign) -> JsonOptic.fromPathString(path))
        case _                => None
      }
  }

  def jsonTemplater(values: Json)(implicit sandbox: GraalJsSandbox): Resource[PartialFunction[String, Json]] = {
    lazy val jsData = values.asObject.map(_.toMap.view.mapValues(_.foldWith(circe2js)).toMap).getOrElse(Map())

    sandbox.makeRunner(jsData).map[PartialFunction[String, Json]] { runner =>
      {
        case JOptic(None, optic) if optic.validate(values) =>
          optic.get(values)
        case JOptic(Some(":"), optic) if optic.validate(values) =>
          optic.get(values).pipe(j => castToString.applyOrElse(j, (_: Json) => j))
        case JOptic(Some("~"), optic) if optic.validate(values) =>
          optic.get(values).pipe(j => castFromString.applyOrElse(j, (_: Json) => j))
        case str @ JORxs() =>
          Json.fromString(
            JORx.replaceSomeIn(
              str,
              m =>
                JsonOptic
                  .fromPathString(m.group(2))
                  .getOpt(values)
                  .map(_.foldWith(json2StringFolder))
            )
          )
        case CodeRx(code) =>
          runner.eval(code) match {
            case Success(value)     => value
            case Failure(exception) => throw exception
          }
      }
    }
  }

  implicit final class JsonTransformations(private val j: Json) extends AnyVal {
    def isTemplate: Boolean =
      j.fold(
        false,
        _ => false,
        _ => false,
        str =>
          JORx.findFirstIn(str).isDefined || CodeRx.findFirstIn(str).isDefined || SubstRx.findFirstIn(str).isDefined,
        _.exists(_.isTemplate),
        _.values.exists(_.isTemplate)
      )

    def transformValues(
        f: Json => Json
    ): TailRec[Json] =
      j.arrayOrObject(
        TailCalls.done(f(j)),
        _.traverse(aj => TailCalls.tailcall(aj.transformValues(f))).map(Json.fromValues),
        _.traverse(obj => TailCalls.tailcall(obj.transformValues(f)))
          .map(Json.fromJsonObject)
      )

    def transformValues(f: PartialFunction[Json, Json]): TailRec[Json] =
      transformValues(j => f.applyOrElse(j, (_: Json) => j))

    def substitute(values: Json)(implicit sandbox: GraalJsSandbox): Resource[Json] =
      jsonTemplater(values).map { templater =>
        transformValues { case js @ JsonString(str) =>
          templater.applyOrElse(str, (_: String) => js)
        }.result
      }

    def substitute(values: Node): Json =
      nodeTemplater(values).pipe { templater =>
        transformValues { case js @ JsonString(str) =>
          templater.andThen(Json.fromString).applyOrElse(str, (_: String) => js)
        }.result
      }

    def eval(implicit sandbox: GraalJsSandbox): Resource[Json] =
      sandbox.makeRunner().map { runner =>
        transformValues { case JsonString(CodeRx(code)) =>
          runner.eval(code) match {
            case Success(value)     => value
            case Failure(exception) => throw exception
          }
        }.result
      }

    def patch(values: Json, schema: Map[JsonOptic, String])(implicit sandbox: GraalJsSandbox): Resource[Json] =
      jsonTemplater(values).map { templater =>
        schema.foldLeft(j) { case (acc, (optic, defn)) =>
          templater.lift.apply(defn).fold(acc)(optic.set(_)(acc))
        }
      }
  }

  private val castToString: PartialFunction[Json, Json] = {
    case JsonBoolean(b)                            => Json.fromString(b.toString)
    case JsonNumber(n) if n.toBigDecimal.isDefined => Json.fromString(n.toBigDecimal.get.bigDecimal.toPlainString)
  }

  private object JNum {
    def unapply(jnumstr: String): Option[JNumber] =
      JNumber.fromString(jnumstr)
  }

  private val castFromString: PartialFunction[Json, Json] = {
    case JsonString("true")   => Json.fromBoolean(true)
    case JsonString("false")  => Json.fromBoolean(false)
    case JsonString(JNum(jn)) => Json.fromJsonNumber(jn)
  }
}
