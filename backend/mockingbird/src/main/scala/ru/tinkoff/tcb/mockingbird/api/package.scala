package ru.tinkoff.tcb.mockingbird

import io.circe.Json
import io.circe.literal.*
import io.circe.parser.parse

package object api {
  /*
    "World" for this application
   */
  type WLD = Tracing

  def mkErrorResponse(message: String): String =
    json"""{"error": $message}""".noSpaces

  def queryParamsToJsonObject(query: Seq[(String, Seq[String])]): Json =
    Json.fromFields(
      query.map { case (k, vs) =>
        val js = vs.map(s => parse(s).getOrElse(Json.fromString(s))) match {
          case Seq(x) => x
          case xs     => Json.arr(xs*)
        }
        k -> js
      }
    )
}
