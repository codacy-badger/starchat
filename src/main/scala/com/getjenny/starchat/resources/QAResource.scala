package com.getjenny.starchat.resources

/**
 * Created by Angelo Leto <angelo@getjenny.com> on 27/06/16.
 */

import akka.NotUsed
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.pattern.CircuitBreaker
import akka.stream.scaladsl.Source
import com.getjenny.starchat.SCActorSystem
import com.getjenny.starchat.entities.io._
import com.getjenny.starchat.entities.persistents.{QADocument, TermCountFields}
import com.getjenny.starchat.routing._
import com.getjenny.starchat.services.QuestionAnswerService

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


class QAResource(questionAnswerService: QuestionAnswerService, routeName: String) extends StarChatResource {
  implicit def executionContext: ExecutionContext = SCActorSystem.system.dispatchers.lookup("starchat.dispatcher")

  def termsCountRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """term_count""" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        get {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.read)) {
              extractRequest { request =>
                parameters("field".as[TermCountFields.Value] ?
                  TermCountFields.question, "term".as[String], "stale".as[Long] ? 0) { (field, term, stale) =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.termCount(indexName, field, term, stale)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                        t
                      })
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 101, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def dictSizeRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """dict_size""" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        get {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.read)) {
              extractRequest { request =>
                parameters("stale".as[Long] ? 0) { stale =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.dictSize(indexName, stale)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                        t
                      })
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 102, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def totalTermsRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """total_terms""" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        get {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.read)) {
              extractRequest { request =>
                parameters("stale".as[Long] ? 0) { stale =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.totalTerms(indexName, stale)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                        t
                      })
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 103, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def questionAnswerStreamRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """stream""" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        get {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.stream)) {
              extractRequest { _ =>
                val entryIterator = questionAnswerService.allDocuments(indexName)
                val entries: Source[QADocument, NotUsed] = Source.fromIterator(() => entryIterator)
                complete(entries)
              }
            }
          }
        }
      }
    }
  }

  def annotationsRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """conv""" ~ Slash ~ """annotations""" ~ Slash ~ routeName) { indexName =>
      path(Segment) { operation: String =>
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.write)) {
              extractRequest { request =>
                operation match {
                  case "convIdxCounter" =>
                    entity(as[String]) { docId =>
                      val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                      onCompleteWithBreakerFuture(breaker)(
                        questionAnswerService.updateConvAnnotations(
                          indexName = indexName, conversation = docId, refreshPolicy = RefreshPolicy.`false`)) {
                        case Success(t) => completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                        case Failure(e) =>
                          val message = logTemplate(user.id, indexName, routeName, request.method, request.uri)
                          log.error(message, e)
                          completeResponse(StatusCodes.BadRequest,
                            Option {
                              ReturnMessageData(code = 104, message = "Error bad operation on QA Documents: " + operation)
                            })
                      }
                    }
                  case _ =>
                    log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri))
                    completeResponse(StatusCodes.BadRequest,
                      Option {
                        ReturnMessageData(code = 105, message = "Error bad operation on QA Documents: " + operation)
                      })
                }
              }
            }
          }
        }
      }
    }
  }


  def questionAnswerRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ routeName) { indexName =>
      pathEnd {
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.write)) {
              extractRequest { request =>
                parameters("updateAnnotations".as[Boolean] ? true,
                  "refresh".as[RefreshPolicy.Value] ? RefreshPolicy.`wait_for`) {
                  (updateAnnotations, refreshPolicy) =>
                    entity(as[QADocument]) { document =>
                      val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                      onCompleteWithBreakerFuture(breaker)(
                        questionAnswerService.create(indexName, document, updateAnnotations, refreshPolicy)) {
                        case Success(t) =>
                          t match {
                            case Some(v) =>
                              completeResponse(StatusCodes.Created, StatusCodes.BadRequest, Some(v))
                            case None =>
                              log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri))
                              completeResponse(StatusCodes.BadRequest,
                                Option {
                                  ReturnMessageData(code = 106, message = "Error indexing new document, empty response")
                                })
                          }
                        case Failure(e) =>
                          val message = logTemplate(user.id, indexName, routeName, request.method, request.uri)
                          log.error(message, e)
                          completeResponse(StatusCodes.BadRequest,
                            Some(ReturnMessageData(code = 107, message = message)))
                      }
                    }
                }
              }
            }

          }
        } ~
          get {
            authenticateBasicAsync(realm = authRealm,
              authenticator = authenticator.authenticator) { user =>
              extractRequest { request =>
                authorizeAsync(_ =>
                  authenticator.hasPermissions(user, indexName, Permissions.read)) {
                  extractRequest { request =>
                    parameters("id".as[String].*) { ids =>
                      val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                      onCompleteWithBreakerFuture(breaker)(questionAnswerService.read(indexName, ids.toList)) {
                        case Success(t) =>
                          completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                        case Failure(e) =>
                          log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                          completeResponse(StatusCodes.BadRequest,
                            Option {
                              ReturnMessageData(code = 108, message = e.getMessage)
                            })
                      }
                    }
                  }
                }
              }
            }
          } ~
          delete {
            authenticateBasicAsync(realm = authRealm,
              authenticator = authenticator.authenticator) { user =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.write)) {
                extractRequest { request =>
                  parameters("refresh".as[RefreshPolicy.Value] ? RefreshPolicy.`false`) { refreshPolicy =>
                    entity(as[DocsIds]) { request_data =>
                      if (request_data.ids.nonEmpty) {
                        val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                        onCompleteWithBreakerFuture(breaker)(questionAnswerService.delete(indexName, request_data.ids, refreshPolicy)) {
                          case Success(t) =>
                            completeResponse(StatusCodes.OK, StatusCodes.BadRequest, t)
                          case Failure(e) =>
                            log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                            completeResponse(StatusCodes.BadRequest,
                              Option {
                                ReturnMessageData(code = 109, message = e.getMessage)
                              })
                        }
                      } else {
                        val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                        onCompleteWithBreakerFuture(breaker)(
                          questionAnswerService.deleteAll(indexName, RefreshPolicy.`false`)) {
                          case Success(t) =>
                            completeResponse(StatusCodes.OK, StatusCodes.BadRequest, t)
                          case Failure(e) =>
                            log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                            completeResponse(StatusCodes.BadRequest,
                              Option {
                                ReturnMessageData(code = 110, message = e.getMessage)
                              })
                        }
                      }
                    }
                  }
                }
              }
            }
          }
      } ~
        put {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            extractRequest { request =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.write)) {
                extractRequest { request =>
                  parameters("refresh".as[RefreshPolicy.Value] ? RefreshPolicy.`false`) { refreshPolicy =>
                    entity(as[QADocumentUpdate]) { update =>
                      val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                      onCompleteWithBreakerFuture(breaker)(
                        questionAnswerService.update(indexName, update, refreshPolicy)) {
                        case Success(t) =>
                          completeResponse(StatusCodes.Created, StatusCodes.BadRequest, t)
                        case Failure(e) =>
                          log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                          completeResponse(StatusCodes.BadRequest,
                            Option {
                              ReturnMessageData(code = 111, message = e.getMessage)
                            })
                      }
                    }
                  }
                }
              }
            }
          }
        }
    }
  }

  def questionAnswerConversationsRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ routeName ~ Slash ~ "conversations") { indexName =>
      pathEnd {
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            extractRequest { request =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.read)) {
                entity(as[DocsIds]) { docsIds =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.conversations(indexName, docsIds)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 112, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        } ~ put {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            extractRequest { request =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.read)) {
                parameters("refresh".as[RefreshPolicy.Value] ? RefreshPolicy.`false`) { refreshPolicy =>
                  entity(as[UpdateQAByQueryReq]) { updateReq =>
                    val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                    onCompleteWithBreakerFuture(breaker)(
                      questionAnswerService.updateByQueryFullResults(
                        indexName = indexName, updateReq = updateReq, refreshPolicy = refreshPolicy)
                    ) {
                      case Success(t) =>
                        completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                      case Failure(e) =>
                        log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                        completeResponse(StatusCodes.BadRequest,
                          Option {
                            ReturnMessageData(code = 113, message = e.getMessage)
                          })
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def questionAnswerAnalyticsRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ "analytics" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            extractRequest { request =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.read)) {
                entity(as[QAAggregatedAnalyticsRequest]) { analytics =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.analytics(indexName, analytics)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 114, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def questionAnswerSearchRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ routeName ~ Slash ~ "search") { indexName =>
      pathEnd {
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            extractRequest { request =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.read)) {
                entity(as[QADocumentSearch]) { docsearch =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.search(indexName, docsearch)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 115, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def updateTermsRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """updateTerms""" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        put {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.write)) {
              extractRequest { request =>
                entity(as[UpdateQATermsRequest]) { extractionRequest =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.updateTextTerms(indexName = indexName,
                    extractionRequest = extractionRequest)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                        t
                      })
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 116, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        } ~ post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.stream)) {
              entity(as[UpdateQATermsRequest]) { extractionRequest =>
                extractRequest { _ =>
                  val entryIterator = questionAnswerService.updateAllTextTerms(indexName = indexName,
                    extractionRequest = extractionRequest)
                  val entries: Source[UpdateDocumentResult, NotUsed] =
                    Source.fromIterator(() => entryIterator)
                  complete(entries)
                }
              }
            }
          }
        }
      }
    }
  }

  def countersCacheSizeRoutes: Route = handleExceptions(routesExceptionHandler) {
    pathPrefix(indexRegex ~ Slash ~ """cache""" ~ Slash ~ routeName) { indexName =>
      pathEnd {
        delete {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, "admin", Permissions.admin)) {
              extractRequest { request =>
                val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                onCompleteWithBreakerFuture(breaker)(questionAnswerService.countersCacheReset) {
                  case Success(t) =>
                    completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                  case Failure(e) =>
                    log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                    completeResponse(StatusCodes.BadRequest,
                      Option {
                        ReturnMessageData(code = 117, message = e.getMessage)
                      })
                }
              }
            }
          }
        } ~ post {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, "admin", Permissions.admin)) {
              extractRequest { request =>
                entity(as[CountersCacheParameters]) { cacheSize =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreakerFuture(breaker)(questionAnswerService.countersCacheParameters(cacheSize)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                    case Failure(e) =>
                      log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 118, message = e.getMessage)
                        })
                  }
                }
              }
            }
          }
        } ~ get {
          authenticateBasicAsync(realm = authRealm, authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, "admin", Permissions.admin)) {
              extractRequest { request =>
                val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                onCompleteWithBreakerFuture(breaker)(questionAnswerService.countersCacheParameters) {
                  case Success(t) =>
                    completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Some(t))
                  case Failure(e) =>
                    log.error(logTemplate(user.id, indexName, routeName, request.method, request.uri), e)
                    completeResponse(StatusCodes.BadRequest,
                      Option {
                        ReturnMessageData(code = 119, message = e.getMessage)
                      })
                }
              }
            }
          }
        }
      }
    }
  }
}
