package ru.tinkoff.tcb.mockingbird.edsl.interpreter

import scala.concurrent.Future

import cats.arrow.FunctionK
import cats.data.*
import cats.data.Validated.Invalid
import cats.data.Validated.Valid
import io.circe.Json
import mouse.boolean.*
import org.scalactic.source
import org.scalatest.Assertion
import org.scalatest.funsuite.AsyncFunSuiteLike
import sttp.client4.Backend as SttpBackend
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.model.Uri

import ru.tinkoff.tcb.mockingbird.edsl.ExampleSet
import ru.tinkoff.tcb.mockingbird.edsl.model.*
import ru.tinkoff.tcb.mockingbird.edsl.model.Check.*
import ru.tinkoff.tcb.mockingbird.edsl.model.ValueMatcher.*

/**
 * Base trait for generating a set of tests from an [[ru.tinkoff.tcb.mockingbird.edsl.ExampleSet ExampleSet]].
 *
 * This trait inherits from AsyncFunSuiteLike in the [[https://www.scalatest.org/ ScalaTest]] framework, so you can add
 * additional tests inside it or use
 * [[https://www.scalatest.org/user_guide/sharing_fixtures#beforeAndAfter BeforeAndAfter]] and/or
 * [[https://www.scalatest.org/user_guide/sharing_fixtures#composingFixtures BeforeAndAfterEach]] to manage the setup of
 * the necessary environment for executing tests, including using
 * [[https://github.com/testcontainers/testcontainers-scala testcontainers-scala]].
 */
trait AsyncScalaTestSuite extends AsyncFunSuiteLike {

  type HttpResponseR = sttp.client4.Response[String]

  private val sttpbackend_ = HttpClientFutureBackend()

  private[interpreter] def sttpbackend: SttpBackend[Future] = sttpbackend_

  /**
   * URI relative to which paths used in examples will be resolved
   */
  def baseUri: Uri

  /**
   * Generate tests from the set of examples.
   */
  protected def generateTests(es: ExampleSet[HttpResponseR]): Unit =
    es.examples.foreach { desc =>
      test(desc.name)(desc.steps.foldMap(stepsBuilder).as(succeed))
    }

  private[interpreter] def stepsBuilder: FunctionK[Step, Future] = new (Step ~> Future) {
    override def apply[A](fa: Step[A]): Future[A] =
      fa match {
        case Describe(text, pos) => Future(info(text)(pos))
        case SendHttp(request, pos) =>
          buildRequest(baseUri, request).send(sttpbackend).map(_.asInstanceOf[A])
        case CheckHttp(response, expects, pos) =>
          Future {
            val resp = response.asInstanceOf[HttpResponseR]

            expects.code.foreach(c =>
              check(resp.code.code, Checker(c), "response HTTP code", "Response body:", resp.body)(pos)
            )
            expects.body.map(Checker(_)).foreach(c => check(resp.body, c, "response body")(pos))
            check(
              resp.headers.map(h => h.name.toLowerCase() -> h.value).toMap,
              Checker(expects.headers.map(kv => kv._1.toLowerCase() -> kv._2).toMap),
              "response headers",
            )(pos)

            val res = HttpResponse(
              resp.code.code,
              resp.body.nonEmpty.option(resp.body),
              resp.headers.map(h => h.name -> h.value)
            )
            res.asInstanceOf[A]
          }
      }
  }

  private def check[T](
      value: T,
      validation: T => ValidatedNel[String, Unit],
      what: String,
      clue: String*
  )(pos: source.Position): Assertion =
    validation(value) match {
      case Invalid(errs) =>
        fail(s"""Checking $what failed with errors:
                  |${errs.toList.mkString(" - ", "\n - ", "")}
                  |Value:
                  |${value}
                  |${clue.mkString("\n")}
                  |""".stripMargin)
      case Valid(_) => succeed
    }

  object Checker {
    def apply(checks: Map[String, Check])(vs: Map[String, Any]): ValidatedNel[String, Unit] =
      checks.toSeq
        .traverse { case (k, c) =>
          vs.get(k).map(v => Checker(c)(v)).getOrElse(s"key '$k' wasn't found".invalidNel)
        }
        .as(())

    def apply(check: Check)(value: Any): ValidatedNel[String, Unit] =
      check match {
        case CheckAny(_)  => ().validNel
        case c: CheckJson => checkJson(value, c)
        case CheckString(matcher) =>
          value match {
            case s: String => checkValue(matcher, s).leftMap(NonEmptyList.one)
            case _         => s"expect string type, but got ${value.getClass().getTypeName()}".invalidNel
          }
        case CheckInteger(matcher) =>
          value match {
            case v: Int  => checkValue(matcher, v.toLong).leftMap(NonEmptyList.one)
            case v: Long => checkValue(matcher, v).leftMap(NonEmptyList.one)
            case _       => s"expect integer type, but got ${value.getClass().getTypeName()}".invalidNel
          }
      }

    private def checkJson(value: Any, check: CheckJson): ValidatedNel[String, Unit] =
      value match {
        case j: Json =>
          checkJson(j, check, Seq.empty)
        case s: String =>
          io.circe.parser.parse(s) match {
            case Left(err) => s"JSON parsing failed: $err".invalidNel
            case Right(v) =>
              checkJson(v, check, Seq.empty)
          }
        case _ => fail(s"CheckJson: got ${value.getClass().getTypeName()}:\nWhat:\n${value}")
      }

    private def checkJson(value: Json, check: CheckJson, path: Seq[String]): ValidatedNel[String, Unit] =
      check match {
        case CheckJsonAny(_)               => ().validNel
        case CheckJsonNull if value.isNull => ().validNel
        case CheckJsonNull                 => s"field ${path.mkString(".")} should be Null".invalidNel
        case CheckJsonArray(cs*) =>
          value.asArray match {
            case Some(arr) if arr.isEmpty && cs.nonEmpty =>
              s"field ${path.mkString(".")} should be non empty".invalidNel
            case Some(arr) =>
              (arr zip cs).zipWithIndex.traverse { case ((j, c), n) => checkJson(j, c, path :+ s"[$n]") }.as(())
            case None => s"field ${path.mkString(".")} should be an array".invalidNel
          }
        case CheckJsonString(matcher) =>
          value.asString
            .map(checkValue(matcher, _))
            .getOrElse(s"field ${path.mkString(".")} should be a string".invalid)
            .leftMap(NonEmptyList.one)
        case CheckJsonNumber(matcher) =>
          value.asNumber
            .map(_.toDouble)
            .map(checkValue(matcher, _))
            .getOrElse(s"field ${path.mkString(".")} should be a number".invalid)
            .leftMap(NonEmptyList.one)
        case CheckJsonObject(fields*) =>
          value.asObject
            .map { o =>
              fields
                .traverse { case (n, c) =>
                  o(n)
                    .map(j => checkJson(j, c, path :+ n))
                    .getOrElse(s"field ${(path :+ n).mkString(".")} doesn't exist".invalidNel)
                }
                .as(())
            }
            .getOrElse(s"field ${path.mkString(".")} should be an object".invalidNel)
      }

    private def checkValue[T](matcher: ValueMatcher[T], value: T): Validated[String, Unit] =
      matcher match {
        case AnyValue(_)          => ().valid
        case FixedValue(`value`)  => ().valid
        case FixedValue(expected) => s"'$value' didn't equal '$expected'".invalid
      }
  }
}
