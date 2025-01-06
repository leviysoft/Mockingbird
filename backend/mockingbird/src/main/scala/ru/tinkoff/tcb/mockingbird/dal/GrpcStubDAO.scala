package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.*

import ru.tinkoff.tcb.mockingbird.model.GrpcStub
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO

@implicitNotFound("Could not find an instance of GrpcStubDAO for ${F}")
trait GrpcStubDAO[F[_]] extends MongoDAO[F, GrpcStub]

object GrpcStubDAO

class GrpcStubDAOImpl(collection: MongoCollection[BsonDocument])
    extends DAOBase[GrpcStub](collection)
    with GrpcStubDAO[Task] {
  def createIndexes: Task[Unit] = createIndex(
    ascending(nameOf[GrpcStub](_.methodDescriptionId), nameOf[GrpcStub](_.scope))
  ) *> createIndex(
    descending(nameOf[GrpcStub](_.created))
  ) *> createIndex(
    ascending(nameOf[GrpcStub](_.labels))
  )
}

object GrpcStubDAOImpl {
  val live = ZLayer {
    for {
      mc <- ZIO.service[MongoCollection[BsonDocument]]
      sd = new GrpcStubDAOImpl(mc)
      _ <- sd.createIndexes
    } yield sd.asInstanceOf[GrpcStubDAO[Task]]
  }
}
