package com.getjenny.starchat.services

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 23/08/17.
  */

import akka.event.{Logging, LoggingAdapter}
import com.getjenny.starchat.SCActorSystem
import org.elasticsearch.action.get.{GetRequestBuilder, GetResponse}
import org.elasticsearch.action.update.UpdateResponse
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentFactory._

import scala.collection.JavaConverters._
import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalaz.Scalaz._

object SystemService {
  var dtReloadTimestamp : Long = -1
  val elasticClient: SystemElasticClient.type = SystemElasticClient
  val log: LoggingAdapter = Logging(SCActorSystem.system, this.getClass.getCanonicalName)


  def getIndexName(indexName: String, suffix: Option[String] = None): String = {
    indexName + "." + suffix.getOrElse(elasticClient.systemRefreshDtIndexSuffix)
  }

  def setDTReloadTimestamp(indexName: String, refresh: Int = 0):
      Future[Option[Long]] = Future {
    val dtReloadDocId: String = indexName
    val timestamp: Long = System.currentTimeMillis

    val builder : XContentBuilder = jsonBuilder().startObject()
    builder.field(elasticClient.dtReloadTimestampFieldName, timestamp)
    builder.endObject()

    val client: TransportClient = elasticClient.getClient()
    val response: UpdateResponse =
      client.prepareUpdate().setIndex(getIndexName(indexName))
        .setType(elasticClient.systemRefreshDtIndexSuffix)
        .setId(dtReloadDocId)
        .setDocAsUpsert(true)
        .setDoc(builder)
        .get()

    log.debug("dt reload timestamp response status: " + response.status())

    if (refresh =/= 0) {
      val refreshIndex = elasticClient.refreshIndex(getIndexName(indexName))
      if(refreshIndex.failed_shards_n > 0) {
        throw new Exception("System: index refresh failed: (" + indexName + ")")
      }
    }

    Option {timestamp}
  }

  def getDTReloadTimestamp(indexName: String) : Future[Option[Long]] = Future {
    val dtReloadDocId: String = indexName
    val client: TransportClient = elasticClient.getClient()
    val getBuilder: GetRequestBuilder = client.prepareGet()
      .setIndex(getIndexName(indexName))
      .setType(elasticClient.systemRefreshDtIndexSuffix)
      .setId(dtReloadDocId)
    val response: GetResponse = getBuilder.get()

    val timestamp = if(! response.isExists || response.isSourceEmpty) {
      log.info("dt reload timestamp field is empty or does not exists")
      -1 : Long
    } else {
      val source : Map[String, Any] = response.getSource.asScala.toMap
      val loadedTs : Long = source.get(elasticClient.dtReloadTimestampFieldName) match {
        case Some(t) => t.asInstanceOf[Long]
        case None =>
          -1: Long
      }
      log.info("dt reload timestamp is: " + loadedTs)
      loadedTs
    }

    Option {timestamp}
  }
}
