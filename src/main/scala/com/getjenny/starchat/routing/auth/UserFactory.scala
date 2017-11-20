package com.getjenny.starchat.routing.auth

import com.getjenny.starchat.services.UserEsService

case class UserClassNotFoundException(message: String = "", cause: Throwable = null)
  extends Exception(message, cause)

object UserFactory {
  def apply(auth_method: SupportedAuthImpl.Value): UserService = {
    auth_method match {
      case SupportedAuthImpl.basic_http_es =>
        new UserEsService
      case _ =>
        throw UserClassNotFoundException("User service not supported: " + auth_method)
    }
  }
}