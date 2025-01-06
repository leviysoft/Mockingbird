package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.*

import ru.tinkoff.tcb.mockingbird.model.Scenario
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO

@implicitNotFound("Could not find an instance of ScenarioDAO for ${F}")
trait ScenarioDAO[F[_]] extends MongoDAO[F, Scenario]

object ScenarioDAO

class ScenarioDAOImpl(collection: MongoCollection[BsonDocument])
    extends DAOBase[Scenario](collection)
    with ScenarioDAO[Task] {
  def createIndexes: Task[Unit] =
    createIndex(
      ascending(nameOf[Scenario](_.source), nameOf[Scenario](_.scope))
    ) *> createIndex(
      descending(nameOf[Scenario](_.created))
    ) *> createIndex(
      ascending(nameOf[Scenario](_.service))
    ) *> createIndex(
      ascending(nameOf[Scenario](_.labels))
    )
}

object ScenarioDAOImpl {
  val live = ZLayer {
    for {
      mc <- ZIO.service[MongoCollection[BsonDocument]]
      sd = new ScenarioDAOImpl(mc)
      _ <- sd.createIndexes
    } yield sd.asInstanceOf[ScenarioDAO[Task]]
  }
}
