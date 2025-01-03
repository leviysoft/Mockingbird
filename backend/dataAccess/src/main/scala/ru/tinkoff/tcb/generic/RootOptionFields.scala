package ru.tinkoff.tcb.generic

import java.time.Instant
import java.time.Year
import scala.annotation.implicitNotFound

import eu.timepit.refined.api.RefType

import magnolia1.*

@implicitNotFound("Could not find an instance of RootOptionFields for ${T}")
trait RootOptionFields[T] extends Serializable {
  def fields: Set[String]
  def isOptionItself: Boolean
  override def toString: String = fields.mkString(", ")
}

object RootOptionFields extends AutoDerivation[RootOptionFields] {
  @inline def apply[T](implicit instance: RootOptionFields[T]): RootOptionFields[T] = instance

  def mk[T](fs: Set[String], isOption: Boolean = false): RootOptionFields[T] =
    new RootOptionFields[T] {
      override def fields: Set[String]     = fs
      override def isOptionItself: Boolean = isOption
    }

  implicit val string: RootOptionFields[String]         = mk(Set.empty)
  implicit val instant: RootOptionFields[Instant]       = mk(Set.empty)
  implicit val year: RootOptionFields[Year]             = mk(Set.empty)
  implicit def anyVal[T <: AnyVal]: RootOptionFields[T] = mk(Set.empty)
  implicit def opt[T]: RootOptionFields[Option[T]]      = mk(Set.empty, isOption = true)
  implicit def seq[T]: RootOptionFields[Seq[T]]         = mk(Set.empty)
  implicit def map[K, V]: RootOptionFields[K Map V]     = mk(Set.empty)
  implicit def vector[T]: RootOptionFields[Vector[T]]   = mk(Set.empty)
  implicit def list[T]: RootOptionFields[List[T]]       = mk(Set.empty)
  implicit def refn[T, R, F[_, _]](implicit rt: RefType[F], rof: RootOptionFields[T]): RootOptionFields[F[T, R]] =
    mk(rof.fields)

  def join[T](caseClass: CaseClass[RootOptionFields, T]): RootOptionFields[T] =
    mk(
      caseClass.parameters
        .foldLeft(Set.newBuilder[String])((acc, fld) =>
          if (fld.typeclass.isOptionItself) acc += fld.label
          else acc
        )
        .result()
    )

  def split[T](sealedTrait: SealedTrait[RootOptionFields, T]): RootOptionFields[T] = mk(Set.empty)
}
