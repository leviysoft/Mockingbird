package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import cats.tagless.autoFunctorK
import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.*
import simulacrum.typeclass

import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO

@implicitNotFound("Could not find an instance of HttpStubDAO for ${F}")
@typeclass @autoFunctorK
trait HttpStubDAO[F[_]] extends MongoDAO[F, HttpStub]

object HttpStubDAO {
  /* ======================================================================== */
  /* THE FOLLOWING CODE IS MANAGED BY SIMULACRUM; PLEASE DO NOT EDIT!!!!      */
  /* ======================================================================== */

  /**
   * Summon an instance of [[HttpStubDAO]] for `F`.
   */
  @inline def apply[F[_]](implicit instance: HttpStubDAO[F]): HttpStubDAO[F] = instance

  object ops {
    implicit def toAllHttpStubDAOOps[F[_], A](target: F[A])(implicit tc: HttpStubDAO[F]): AllOps[F, A] {
      type TypeClassType = HttpStubDAO[F]
    } = new AllOps[F, A] {
      type TypeClassType = HttpStubDAO[F]
      val self: F[A]                       = target
      val typeClassInstance: TypeClassType = tc
    }
  }
  trait Ops[F[_], A] extends Serializable {
    type TypeClassType <: HttpStubDAO[F]
    def self: F[A]
    val typeClassInstance: TypeClassType
  }
  trait AllOps[F[_], A] extends Ops[F, A]
  trait ToHttpStubDAOOps extends Serializable {
    implicit def toHttpStubDAOOps[F[_], A](target: F[A])(implicit tc: HttpStubDAO[F]): Ops[F, A] {
      type TypeClassType = HttpStubDAO[F]
    } = new Ops[F, A] {
      type TypeClassType = HttpStubDAO[F]
      val self: F[A]                       = target
      val typeClassInstance: TypeClassType = tc
    }
  }
  object nonInheritedOps extends ToHttpStubDAOOps

  /* ======================================================================== */
  /* END OF SIMULACRUM-MANAGED CODE                                           */
  /* ======================================================================== */

}

class HttpStubDAOImpl(collection: MongoCollection[BsonDocument])
    extends DAOBase[HttpStub](collection)
    with HttpStubDAO[Task] {
  def createIndexes: Task[Unit] =
    createIndex(
      ascending(
        nameOf[HttpStub](_.method),
        nameOf[HttpStub](_.path),
        nameOf[HttpStub](_.scope),
        nameOf[HttpStub](_.times)
      ),
    ) *> createIndex(
      descending(nameOf[HttpStub](_.created))
    ) *> createIndex(
      ascending(nameOf[HttpStub](_.serviceSuffix))
    ) *> createIndex(
      ascending(nameOf[HttpStub](_.labels))
    )
}

object HttpStubDAOImpl {
  val live = ZLayer {
    for {
      mc <- ZIO.service[MongoCollection[BsonDocument]]
      sd = new HttpStubDAOImpl(mc)
      _ <- sd.createIndexes
    } yield sd.asInstanceOf[HttpStubDAO[Task]]
  }
}
