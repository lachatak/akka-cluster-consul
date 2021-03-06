package com.sap1ens.api

import spray.routing._
import akka.actor.{ActorLogging, ActorRef, Props}
import akka.io.IO

import scala.concurrent.ExecutionContext.Implicits.global
import spray.can.Http
import spray.json.DefaultJsonProtocol
import spray.util.LoggingContext
import spray.httpx.SprayJsonSupport
import com.sap1ens.utils.ConfigHolder
import com.sap1ens.{ClusteredBootedCore, Core, CoreActors, Services}
import spray.http.HttpHeaders.{`Access-Control-Allow-Credentials`, `Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Origin`}
import spray.http.HttpMethods._
import spray.http.{HttpOrigin, SomeOrigins, StatusCodes}

trait CORSSupport extends Directives {
  private val CORSHeaders = List(
    `Access-Control-Allow-Methods`(GET, POST, PUT, DELETE, OPTIONS),
    `Access-Control-Allow-Headers`("Origin, X-Requested-With, Content-Type, Accept, Accept-Encoding, Accept-Language, Host, Referer, User-Agent"),
    `Access-Control-Allow-Credentials`(true)
  )

  def respondWithCORS(origin: String)(routes: => Route) = {
    val originHeader = `Access-Control-Allow-Origin`(SomeOrigins(Seq(HttpOrigin(origin))))

    respondWithHeaders(originHeader :: CORSHeaders) {
      routes ~ options { complete(StatusCodes.OK) }
    }
  }
}

trait Api extends Directives with RouteConcatenation with CORSSupport with ConfigHolder {
  this: ClusteredBootedCore =>

  val routes =
    respondWithCORS(config.getString("origin.domain")) {
      pathPrefix("api") {
        new HealthCheckRoutes().route ~
        new KVRoutes(getKVService).route
      }
    }

  val rootService = system.actorOf(ApiService.props(config.getString("hostname"), config.getInt("port"), routes))
}

object ApiService {
  def props(hostname: String, port: Int, routes: Route) = Props(classOf[ApiService], hostname, port, routes)
}

class ApiService(hostname: String, port: Int, route: Route) extends HttpServiceActor with ActorLogging {
  IO(Http)(context.system) ! Http.Bind(self, hostname, port)

  def receive: Receive = runRoute(route)
}

object ApiRoute {
  case class Message(message: String)

  object ApiRouteProtocol extends DefaultJsonProtocol {
    implicit val messageFormat = jsonFormat1(Message)
  }

  object ApiMessages {
    val UnknownException = "Unknown exception"
  }
}

abstract class ApiRoute extends Directives with SprayJsonSupport
