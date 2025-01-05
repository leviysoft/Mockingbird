package ru.tinkoff.tcb.mockingbird.edsl.interpreter

import cats.arrow.FunctionK
import cats.data.Writer
import io.circe.Json
import mouse.boolean.*
import pl.muninn.scalamdtag.*
import pl.muninn.scalamdtag.tags.Markdown
import sttp.model.Uri

import ru.tinkoff.tcb.mockingbird.edsl.ExampleSet
import ru.tinkoff.tcb.mockingbird.edsl.interpreter.buildRequest
import ru.tinkoff.tcb.mockingbird.edsl.model.*
import ru.tinkoff.tcb.mockingbird.edsl.model.Check.*
import ru.tinkoff.tcb.mockingbird.edsl.model.HttpMethod.Delete
import ru.tinkoff.tcb.mockingbird.edsl.model.HttpMethod.Get
import ru.tinkoff.tcb.mockingbird.edsl.model.HttpMethod.Post

object MarkdownGenerator {
  type HttpResponseR = {}
  private[interpreter] val httpResponseR: HttpResponseR = new {}

  def apply(baseUri: Uri): MarkdownGenerator =
    new MarkdownGenerator(baseUri)

  private object implicits {
    implicit val httpMethodShow: Show[HttpMethod] = new Show[HttpMethod] {
      override def show(m: HttpMethod): String =
        m match {
          case Delete => "DELETE"
          case Get    => "GET"
          case Post   => "POST"
        }
    }

    implicit def valueMatcherShow[T: Show]: Show[ValueMatcher[T]] =
      (vm: ValueMatcher[T]) =>
        vm match {
          case ValueMatcher.AnyValue(example) => example.show
          case ValueMatcher.FixedValue(value) => value.show
        }

    implicit class ValueMatcherOps[T](private val vm: ValueMatcher[T]) extends AnyVal {
      def value: T = vm match {
        case ValueMatcher.AnyValue(example) => example
        case ValueMatcher.FixedValue(value) => value
      }
    }

    implicit val checkShow: Show[Check] = (check: Check) =>
      check match {
        case CheckAny(example)     => example
        case CheckInteger(matcher) => matcher.show
        case CheckString(matcher)  => matcher.show
        case cj: CheckJson         => buildJson(cj).spaces2
      }

    def buildJson(cj: CheckJson): Json =
      cj match {
        case CheckJsonAny(example)    => example
        case CheckJsonArray(items*)   => Json.arr(items.map(buildJson): _*)
        case CheckJsonNull            => Json.Null
        case CheckJsonNumber(matcher) => Json.fromDoubleOrNull(matcher.value)
        case CheckJsonObject(fields*) => Json.obj(fields.map { case (n, v) => n -> buildJson(v) }: _*)
        case CheckJsonString(matcher) => Json.fromString(matcher.value)
      }
  }
}

/**
 * DSL interpreter creating a markdown document with an example description.
 *
 * @param baseUri
 *   URI relative to which paths used in examples will be resolved.
 */
final class MarkdownGenerator(baseUri: Uri) {
  import MarkdownGenerator.HttpResponseR
  import MarkdownGenerator.httpResponseR
  import MarkdownGenerator.implicits.*
  import cats.syntax.writer.*

  private[interpreter] type W[A] = Writer[Vector[Markdown], A]

  /**
   * Generate a markdown document from the provided set of examples.
   *
   * @param set
   *   examples set
   * @return
   *   A string containing a markdown document.
   */
  def generate(set: ExampleSet[HttpResponseR]): String = {
    val tags = for {
      _ <- Vector(h1(set.name)).tell
      _ <- set.examples.traverse(generate)
    } yield ()

    markdown(tags.written).md
  }

  private[interpreter] def generate(desc: ExampleDescription): W[Unit] =
    for {
      _ <- Vector[Markdown](h2(desc.name)).tell
      _ <- desc.steps.foldMap(stepsPrinterW)
    } yield ()

  private[interpreter] def stepsPrinterW: FunctionK[Step, W] = new (Step ~> W) {
    def apply[A](fa: Step[A]): W[A] =
      fa match {
        case Describe(text, pos) => Vector(p(text)).tell

        case SendHttp(request, pos) =>
          val skipCurlStrings = Seq("Content-Length")
          val sreq = buildRequest(baseUri, request)
            .followRedirects(false)
            .toCurl
            .split("\n")
            .filterNot(s => skipCurlStrings.exists(r => s.contains(r)))
            .mkString("", "\n", "\n")
          Writer(Vector(codeBlock(sreq)), httpResponseR.asInstanceOf[A])

        case CheckHttp(_, HttpResponseExpected(None, None, Seq()), _) =>
          Writer value HttpResponse(0, None, Seq.empty)

        case CheckHttp(_, HttpResponseExpected(code, body, headers), _) =>
          val bodyStr = body.map(_.show)
          val cb = Vector[Option[String]](
            code.map(c => s"Response code: ${c.matcher.show}\n"),
            headers.nonEmpty.option {
              headers.map { case (k, v) => s"$k: '${v.matcher.show}'" }.mkString("Response headers:\n", "\n", "\n")
            },
            bodyStr.map(bs => "Response body:\n".concat(bs).concat("\n")),
          ).flatten.mkString("\n")

          Writer(
            Vector(
              p("Response:"),
              codeBlock(cb)
            ),
            HttpResponse(
              code.fold(0L)(_.matcher.value).toInt,
              bodyStr,
              headers.map { case (k, c) => k -> c.matcher.value },
            )
          )
      }
  }
}
