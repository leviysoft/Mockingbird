package ru.tinkoff.tcb.mockingbird.misc

import scala.xml.Node

import io.circe.Json

import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox
import ru.tinkoff.tcb.utils.transformation.json.*
import ru.tinkoff.tcb.utils.transformation.xml.*

/**
 * "Proof that B can be substituted into A
 */
trait Substitute[A, B] {
  def substitute(a: A, b: B): A
}

object Substitute {
  def apply[A, B](implicit subst: Substitute[A, B]): Substitute[A, B] = subst

  implicit def jsonSJson(implicit sandbox: GraalJsSandbox): Substitute[Json, Json] = (a: Json, b: Json) =>
    a.substitute(b).useAsIs
  implicit val jsonSNode: Substitute[Json, Node] = (a: Json, b: Node) => a.substitute(b)
  implicit def nodeSJson(implicit sandbox: GraalJsSandbox): Substitute[Node, Json] = (a: Node, b: Json) =>
    a.substitute(b).useAsIs
  implicit val nodeSNode: Substitute[Node, Node] = (a: Node, b: Node) => a.substitute(b)
}
