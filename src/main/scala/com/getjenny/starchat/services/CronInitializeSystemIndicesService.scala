package com.getjenny.starchat.services

import akka.actor.{Actor, Props}
import com.getjenny.starchat.SCActorSystem
import com.getjenny.starchat.services.esclient.{ClusterNodesElasticClient, InstanceRegistryElasticClient, NodeDtLoadingStatusElasticClient, UserElasticClient}

import scala.concurrent.duration._
import scala.language.postfixOps
import com.getjenny.starchat.utils.Index

/** Initialize the System Indices if they does not exists
 */
object CronInitializeSystemIndicesService extends CronService {

  class InitializeSystemIndicesActor extends Actor {
    val client: ClusterNodesElasticClient.type = clusterNodesService.elasticClient
    val indices: List[String] = List(UserElasticClient, InstanceRegistryElasticClient,
      ClusterNodesElasticClient, NodeDtLoadingStatusElasticClient).map(serv =>
      Index.indexName(serv.indexName, serv.indexSuffix)
    )

    def receive: PartialFunction[Any, Unit] = {
      case `tickMessage` =>
        if(client.existsIndices(indices))
          log.debug("System indices exist")
        else {
          log.info("System indices are missing, initializing system indices")
          systemIndexManagementService.create()
        }
      case _ =>
        log.error("Unknown error initializing system indices.")
    }
  }

  def scheduleAction(): Unit = {
    val actorRef =
      SCActorSystem.system.actorOf(Props(new InitializeSystemIndicesActor))
    SCActorSystem.system.scheduler.scheduleOnce(
      0 seconds,
      actorRef,
      tickMessage)
  }
}
