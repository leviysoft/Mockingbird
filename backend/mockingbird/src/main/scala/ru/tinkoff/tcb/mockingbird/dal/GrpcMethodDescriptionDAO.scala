package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.*

import ru.tinkoff.tcb.mockingbird.model.GrpcMethodDescription
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO

@implicitNotFound("Could not find an instance of GrpcMethodDescriptionDAO for ${F}")
trait GrpcMethodDescriptionDAO[F[_]] extends MongoDAO[F, GrpcMethodDescription]

object GrpcMethodDescriptionDAO

class GrpcMethodDescriptionDAOImpl(collection: MongoCollection[BsonDocument])
    extends DAOBase[GrpcMethodDescription](collection)
    with GrpcMethodDescriptionDAO[Task] {
  def createIndexes: Task[Unit] = createIndex(
    ascending(nameOf[GrpcMethodDescription](_.methodName))
  ) *> createIndex(
    ascending(nameOf[GrpcMethodDescription](_.service))
  ) *> createIndex(
    descending(nameOf[GrpcMethodDescription](_.created))
  )
}

object GrpcMethodDescriptionDAOImpl {
  val live = ZLayer {
    for {
      mc <- ZIO.service[MongoCollection[BsonDocument]]
      sd = new GrpcMethodDescriptionDAOImpl(mc)
      _ <- sd.createIndexes
    } yield sd.asInstanceOf[GrpcMethodDescriptionDAO[Task]]
  }
}
