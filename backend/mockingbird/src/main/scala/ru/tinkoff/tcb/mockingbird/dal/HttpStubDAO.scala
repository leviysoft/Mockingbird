package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.*

import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO

@implicitNotFound("Could not find an instance of HttpStubDAO for ${F}")
trait HttpStubDAO[F[_]] extends MongoDAO[F, HttpStub]

object HttpStubDAO

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
