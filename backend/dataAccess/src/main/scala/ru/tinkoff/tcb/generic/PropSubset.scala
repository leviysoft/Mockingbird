package ru.tinkoff.tcb.generic

import scala.annotation.implicitNotFound
import scala.deriving.Mirror
import scala.util.NotGiven

import eu.timepit.refined.api.RefType

/*
  Witnesses, that all fields of `Projection` exists in `Source` with the same types and names
 */
@implicitNotFound("${Projection} is not a valid projection of ${Source}")
trait PropSubset[Projection, Source]

type GetByLabel[TL <: String, TPL <: Tuple] =
  TPL match
    case (TL, t) *: _ => t
    case _ *: (t *: ts) => GetByLabel[TL, t *: ts]

object PropSubset {
  private val anySubset = new PropSubset[Any, Any] {}

  given [T]: PropSubset[T, T] = anySubset.asInstanceOf[PropSubset[T, T]]

  given [P, S](using NotGiven[P =:= S], PropSubset[P, S]): PropSubset[Option[P], Option[S]] = anySubset.asInstanceOf[PropSubset[Option[P], Option[S]]]

  given [K, P, S](using NotGiven[P =:= S], PropSubset[P, S]): PropSubset[K Map P, K Map S] = anySubset.asInstanceOf[PropSubset[K Map P, K Map S]]

  given [P, S](using NotGiven[P =:= S], PropSubset[P, S]): PropSubset[Set[P], Set[S]] = anySubset.asInstanceOf[PropSubset[Set[P], Set[S]]]

  given [P, S, R, F[_, _]](using RefType[F], PropSubset[P, S]): PropSubset[F[P, R], S] = anySubset.asInstanceOf[PropSubset[F[P, R], S]]

  given [P, S, R, F[_, _]](using NotGiven[P =:= S], RefType[F], PropSubset[P, S]): PropSubset[F[P, R], F[S, R]] = anySubset.asInstanceOf[PropSubset[F[P, R], F[S, R]]]

  given [T <: Tuple]: PropSubset[EmptyTuple, T] = anySubset.asInstanceOf[PropSubset[EmptyTuple, T]]

  given [PHL <: String, PHT, PT <: Tuple, S <: Tuple](using PropSubset[PHT, GetByLabel[PHL, S]], PropSubset[PT, S]): PropSubset[(PHL, PHT) *: PT, S] = anySubset.asInstanceOf[PropSubset[(PHL, PHT) *: PT, S]]

  given [P <: Product, S <: Product](using mp: Mirror.ProductOf[P], ms: Mirror.ProductOf[S], tps: PropSubset[Tuple.Zip[mp.MirroredElemLabels, mp.MirroredElemTypes], Tuple.Zip[ms.MirroredElemLabels, ms.MirroredElemTypes]]): PropSubset[P, S] =
    anySubset.asInstanceOf[PropSubset[P, S]]
}
