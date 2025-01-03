package ru.tinkoff.tcb.utils.circe.optics

import io.circe.ACursor
import io.circe.Json

final case class JsonOptic private[optics] (private val jsonPath: Seq[PathPart]) {
  def \(field: String): JsonOptic = new JsonOptic(jsonPath :+ Field(field))
  def \(index: Int): JsonOptic    = new JsonOptic(jsonPath :+ Index(index))
  def traverse: JsonOptic         = new JsonOptic(jsonPath :+ Traverse)

  /**
   * Compose Optics
   */
  def \\(other: JsonOptic): JsonOptic = new JsonOptic(jsonPath ++ other.jsonPath)

  def set(v: Json): Json => Json = jsonPath.foldRight[Json => Json](_ => v)((part, f) => modifyPart(v)(part)(f))

  /**
   * Updates subtress if arg is Some(..), removes subtree otherwise
   */
  def setOpt(vo: Option[Json]): Json => Json = vo match {
    case Some(j) => set(j)
    case None    => prune
  }

  def getOpt: Json => Option[Json] = getAll.andThen {
    case Vector()  => None
    case Vector(j) => Option(j)
    case v         => Option(Json.fromValues(v))
  }

  def get: Json => Json = getOpt.andThen(_.getOrElse(Json.Null))

  def getAll: Json => Vector[Json] = { json =>
    jsonPath
      .foldLeft(Vector[ACursor](json.hcursor))((c, p) =>
        p.fold(
          f => c.map(_.downField(f)),
          i => c.map(_.downN(i)),
          c.flatMap(cx =>
            cx.values match {
              case Some(value) => Vector.tabulate(value.size)(cx.downN)
              case None        => Vector.empty
            }
          )
        )
      )
      .flatMap(_.focus)
  }

  def prune: Json => Json = { json =>
    if (validate(json))
      jsonPath.init.foldRight[Json => Json] { json =>
        jsonPath.last.fold(
          f => json.withObject(jo => Json.fromJsonObject(jo.remove(f))),
          i => json.withArray(ja => Json.fromValues(ja.take(i) ++ ja.drop(i + 1))),
          if (json.isArray) Json.Null else json
        )
      }((part, f) => modifyPart(Json.Null)(part)(f))(json)
    else json
  }

  def validate: Json => Boolean = { json =>
    jsonPath match {
      case Seq(Traverse) => json.isArray
      case _ =>
        jsonPath
          .foldLeft(Vector(json)) { (op, part) =>
            op.filter(verifyPart(part)).flatMap { j =>
              part.fold(
                f => j.hcursor.downField(f).focus.toVector,
                i => j.hcursor.downN(i).focus.toVector,
                j.asArray.getOrElse(Vector.empty)
              )
            }
          }
          .nonEmpty
    }
  }

  def modify(op: Json => Json): Json => Json =
    jsonPath.foldRight[Json => Json](op)((part, f) => modifyPart(Json.Null)(part)(f))

  def modifyOpt(op: Option[Json] => Json): Json => Json =
    jsonPath.init.foldRight[Json => Json](modifyPart(jsonPath.last)(op))((part, f) => modifyPart(Json.Null)(part)(f))

  def modifyObjectValues(op: Json => Json): Json => Json = { json =>
    getOpt(json)
      .map(jVal => set(jVal.withObject(jo => Json.fromJsonObject(jo.mapValues(op))))(json))
      .getOrElse(json)
  }

  def modifyFields(op: (String, Json) => (String, Json)): Json => Json = { json =>
    getOpt(json)
      .map(jVal => set(jVal.withObject(jo => Json.fromFields(jo.toMap.map(op.tupled))))(json))
      .getOrElse(json)
  }

  def foldPath[T](index: Int => T, field: String => T, traverse: => T): Seq[T] =
    jsonPath.map(_.fold(field, index, traverse))

  lazy val path: String = foldPath(i => s"[$i]", identity, "$").mkString(".")

  override def toString: String = s"@->$path"
}

object JsonOptic {
  private val IndexPattern = """\[(\d+)\]""".r

  def forPath(path: String*): JsonOptic = new JsonOptic(path.map(Field(_)))
  def forIndex(index: Int): JsonOptic   = new JsonOptic(Seq(Index(index)))
  def fromPathString(path: String): JsonOptic =
    new JsonOptic(path.split('.').toSeq.map {
      case "$"             => Traverse
      case IndexPattern(i) => Index(i.toInt)
      case s               => Field(s)
    })
}
