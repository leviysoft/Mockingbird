package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Indexes.descending

import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO
import ru.tinkoff.tcb.utils.crypto.AES
import ru.tinkoff.tcb.utils.id.SID

@implicitNotFound("Could not find an instance of DestinationConfigurationDAO for ${F}")
trait DestinationConfigurationDAO[F[_]] extends MongoDAO[F, DestinationConfiguration] {
  def getAll: F[Vector[DestinationConfiguration]] = findChunk(BsonDocument(), 0, Int.MaxValue)
  def getAllNames: F[Vector[SID[DestinationConfiguration]]]
}

object DestinationConfigurationDAO

class DestinationConfigurationDAOImpl(collection: MongoCollection[BsonDocument])(implicit aes: AES)
    extends DAOBase[DestinationConfiguration](collection)
    with DestinationConfigurationDAO[Task] {
  override def getAllNames: Task[Vector[SID[DestinationConfiguration]]] = getAll.map(_.map(_.name))

  def createIndexes: Task[Unit] = createIndex(
    ascending(nameOf[DestinationConfiguration](_.service))
  ) *>
    createIndex(descending(nameOf[DestinationConfiguration](_.created)))
}

object DestinationConfigurationDAOImpl {
  val live: RLayer[MongoCollection[BsonDocument] & AES, DestinationConfigurationDAO[Task]] =
    ZLayer {
      for {
        coll      <- ZIO.service[MongoCollection[BsonDocument]]
        given AES <- ZIO.service[AES]
        dcd = new DestinationConfigurationDAOImpl(coll)
        _ <- dcd.createIndexes
      } yield dcd
    }
}
