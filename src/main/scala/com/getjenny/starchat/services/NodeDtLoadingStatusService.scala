package com.getjenny.starchat.services

/**
 * Created by Angelo Leto <angelo@getjenny.com> on 23/08/17.
 */

import akka.event.{Logging, LoggingAdapter}
import com.getjenny.starchat.SCActorSystem
import com.getjenny.starchat.entities.io._
import com.getjenny.starchat.services.InstanceRegistryService.allEnabledInstanceTimestamp
import com.getjenny.starchat.utils.Index
import org.elasticsearch.action.search.{SearchRequest, SearchResponse, SearchType}
import com.getjenny.starchat.services.esclient.NodeDtLoadingStatusElasticClient
import org.elasticsearch.action.update.{UpdateRequest, UpdateResponse}
import org.elasticsearch.client.{RequestOptions, RestHighLevelClient}
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentFactory._
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilders}
import org.elasticsearch.index.reindex.DeleteByQueryRequest
import org.elasticsearch.search.builder.SearchSourceBuilder
import scalaz.Scalaz._

import scala.collection.JavaConverters._
import scala.collection.immutable.Map

case class NodeDtLoadingStatusServiceException(message: String = "", cause: Throwable = None.orNull)
  extends Exception(message, cause)

case class NodeDtLoadingHealthCheckException(message: String = "", cause: Throwable = None.orNull)
  extends Exception(message, cause)

object NodeDtLoadingStatusService extends AbstractDataService {
  val DT_NODES_STATUS_TIMESTAMP_DEFAULT : Long = -1
  override val elasticClient: NodeDtLoadingStatusElasticClient.type = NodeDtLoadingStatusElasticClient
  val clusterNodesService: ClusterNodesService.type = ClusterNodesService
  private[this] val instanceRegistryService: InstanceRegistryService.type = InstanceRegistryService
  private[this] val analyzerService: AnalyzerService.type = AnalyzerService
  private[this] val log: LoggingAdapter = Logging(SCActorSystem.system, this.getClass.getCanonicalName)
  val indexName: String =
    Index.indexName(elasticClient.indexName, elasticClient.indexSuffix)

  private[this] def calcUuid(uuid: String = ""): String = if (uuid === "") clusterNodesService.uuid else uuid
  private[this] def calcId(dtIndexName: String, uuid: String): String = {
    dtIndexName + "." + calcUuid(uuid)
  }

  def update(dtNodeStatus: NodeDtLoadingStatus, refreshPolicy: RefreshPolicy.Value): Unit = {
    val client: RestHighLevelClient = elasticClient.httpClient
    val uuid = calcUuid(dtNodeStatus.uuid.getOrElse(""))
    val id = calcId(dtNodeStatus.index, uuid)
    val timestamp = if (dtNodeStatus.timestamp.getOrElse(0: Long) <= 0)
      System.currentTimeMillis else dtNodeStatus.timestamp.getOrElse(0: Long)
    val builder : XContentBuilder = jsonBuilder().startObject()

    List(
      ("uuid", uuid),
      ("index", dtNodeStatus.index),
      ("timestamp", timestamp),
    ).foreach{case (k,v) => builder.field(k, v)}

    builder.endObject()

    val updateReq = new UpdateRequest()
      .index(indexName)
      .doc(builder)
      .id(id)
      .docAsUpsert(true)

    val response: UpdateResponse = client.update(updateReq, RequestOptions.DEFAULT)
    refresh(refreshPolicy)

    log.debug("set update dt ({}) on node({}) timestamp({}) ", dtNodeStatus.index, uuid, timestamp, response.status())
  }

  def dtUpdateStatusByIndex(dtIndexName: String = "", minTs: Long = 0): List[NodeDtLoadingStatus] = {
    val client: RestHighLevelClient = elasticClient.httpClient
    val boolQueryBuilder : BoolQueryBuilder = QueryBuilders.boolQuery()
    if(dtIndexName =/= "") boolQueryBuilder.filter(QueryBuilders.termQuery("index", dtIndexName))
    if(minTs > 0) boolQueryBuilder.filter(QueryBuilders.rangeQuery("timestamp").gte(minTs))

    val sourceReq: SearchSourceBuilder = new SearchSourceBuilder()
      .query(boolQueryBuilder)
      .from(0)
      .size(10000)
      .version(true)

    val searchReq = new SearchRequest(indexName)
      .source(sourceReq)
      .searchType(SearchType.DFS_QUERY_THEN_FETCH)

    val searchResp: SearchResponse = client.search(searchReq, RequestOptions.DEFAULT)


    searchResp.getHits.getHits.toList.map { item =>
      val source : Map[String, Any] = item.getSourceAsMap.asScala.toMap

      val uuid: String = source.get("uuid") match {
        case Some(t) => t.asInstanceOf[String]
        case _ => throw NodeDtLoadingStatusServiceException("Failed to get uuid for the index: " + source)
      }

      val index : String = source.get("index") match {
        case Some(t) => t.asInstanceOf[String]
        case _ => throw NodeDtLoadingStatusServiceException("Failed to get index name: " + source)
      }

      val timestamp : Long = source.get("timestamp") match {
        case Some(t) => t.asInstanceOf[Long]
        case _ => throw NodeDtLoadingStatusServiceException("Failed to get timestamp for the index: " + source)
      }

      NodeDtLoadingStatus(uuid = Some{uuid}, index = index, timestamp = Some{timestamp})
    }
  }

  def nodeLoadingStatusAll(verbose: Boolean = false, strict: Boolean = false) : NodeLoadingAllDtStatus = {
    val instanceUpdateStatus = allEnabledInstanceTimestamp(minTimestamp = Some(0)).map{ dtReloadTs =>
      val upToDate = analyzerService.analyzersMap.get(dtReloadTs.indexName) match {
        case Some(t) => t.lastReloadingTimestamp >= dtReloadTs.timestamp
        case _ => false
      }
      (dtReloadTs.indexName, upToDate)
    }.toMap

    val totalIndexes = instanceUpdateStatus.length.toLong
    val updatedIndexes = instanceUpdateStatus.filter{case(_, status) => status}.length.toLong

    val nodeReady = totalIndexes === updatedIndexes
    if(strict && ! nodeReady) {
      throw NodeDtLoadingHealthCheckException("node not ready")
    }

    NodeLoadingAllDtStatus(
      totalIndexes = totalIndexes,
      updatedIndexes = updatedIndexes,
      indexes = if(verbose) instanceUpdateStatus else Map.empty[String, Boolean]
    )
  }

  def loadingStatus(index: String, strict: Boolean = false) : ClusterLoadingDtStatusIndex = {
    val aliveNodes = clusterNodesService.aliveNodes.nodes.map(_.uuid).toSet // all alive nodes
    val indexPushTimestamp = instanceRegistryService.instanceTimestamp(index) // push timestamp for the index
    val nodeDtLoadingStatus = // update operations for the index
      dtUpdateStatusByIndex(dtIndexName = index, minTs = indexPushTimestamp.timestamp)
        .map(_.uuid.getOrElse("")).toSet
    val updatedSet = aliveNodes & nodeDtLoadingStatus
    val updateCompleted = updatedSet === aliveNodes

    if(strict && ! updateCompleted) {
      throw NodeDtLoadingHealthCheckException("index not ready")
    }

    ClusterLoadingDtStatusIndex(index = index,
      totalAliveNodes = aliveNodes.length,
      upToDateNodes = updatedSet.length,
      updateCompleted = updateCompleted,
      timestamp = indexPushTimestamp.timestamp
    )
  }

  def cleanDeadNodesRecords: DeleteDocumentsSummaryResult = {
    val client: RestHighLevelClient = elasticClient.httpClient
    val aliveNodes = clusterNodesService.aliveNodes.nodes.map(_.uuid) // all alive nodes
    val boolQueryBuilder : BoolQueryBuilder = QueryBuilders.boolQuery()
    boolQueryBuilder.mustNot(QueryBuilders.termsQuery("uuid", aliveNodes:_*))

    val request: DeleteByQueryRequest =
      new DeleteByQueryRequest(indexName)
    request.setConflicts("proceed")
    request.setQuery(boolQueryBuilder)
    val bulkResponse = client.deleteByQuery(request, RequestOptions.DEFAULT)

    DeleteDocumentsSummaryResult(message = "delete death nodes on dt update register", deleted = bulkResponse.getTotal)
  }

}
