package org.typeunsafe

import io.vertx.circuitbreaker.CircuitBreaker
import io.vertx.core.*
import io.vertx.core.http.HttpClient
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler

import io.vertx.kotlin.core.http.HttpServerOptions
import io.vertx.ext.web.handler.StaticHandler

import io.vertx.servicediscovery.types.HttpEndpoint
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.Status

import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.Record

import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.circuitbreaker.CircuitBreakerOptions

import io.vertx.kotlin.core.json.*
import io.vertx.servicediscovery.rest.ServiceDiscoveryRestEndpoint

import me.atrox.haikunator.HaikunatorBuilder
import java.util.*

fun main(args: Array<String>) {
  val vertx = Vertx.vertx()
  vertx.deployVerticle(Raider())

}

class Reverse : AbstractVerticle() {

  private var discovery: ServiceDiscovery? = null
  private var record: Record? = null
  private var healthCheck: HealthCheckHandler? =null
  private var breaker: CircuitBreaker? =null

  override fun stop(stopFuture: Future<Void>) {
    super.stop()
    println("Unregistration process is started (${record?.registration})...")

    discovery?.unpublish(record?.registration, { ar ->
      when {
        ar.failed() -> {
          println("😡 Unable to unpublish the microservice: ${ar.cause().message}")
          stopFuture.fail(ar.cause())
        }
        ar.succeeded() -> {
          println("👋 bye bye ${record?.registration}")
          stopFuture.complete()
        }
      }
    })
  } // end of stop


  override fun start() {

    /* 🔦 === Discovery part === */

    // Redis Backend settings

    val redisPort= System.getenv("REDIS_PORT")?.toInt() ?: 6379
    val redisHost = System.getenv("REDIS_HOST") ?: "127.0.0.1"
    val redisAuth = System.getenv("REDIS_PASSWORD") ?: null
    val redisRecordsKey = System.getenv("REDIS_RECORDS_KEY") ?: "vert.x.ms" // the redis hash

    val serviceDiscoveryOptions = ServiceDiscoveryOptions()

    discovery = ServiceDiscovery.create(vertx,
      serviceDiscoveryOptions.setBackendConfiguration(
        json {
          obj(
            "host" to redisHost,
            "port" to redisPort,
            "auth" to redisAuth,
            "key" to redisRecordsKey
          )
        }
      ))

    // microservice informations
    val haikunator = HaikunatorBuilder().setTokenLength(3).build()
    val niceName = haikunator.haikunate()

    val serviceName = "${System.getenv("SERVICE_NAME") ?: "the-plan"}-$niceName"
    val serviceHost = System.getenv("SERVICE_HOST") ?: "localhost" // domain name
    val servicePort = System.getenv("SERVICE_PORT")?.toInt() ?: 80 // servicePort: this is the visible port from outside
    val serviceRoot = System.getenv("SERVICE_ROOT") ?: "/api"


    // create the microservice record
    record = HttpEndpoint.createRecord(
      serviceName,
      serviceHost,
      servicePort,
      serviceRoot
    )
    // add metadata
    record?.metadata = json {
      obj(
        "message" to "hello 🌍"
      )
    }

    /* 🤖 === health check === */
    healthCheck = HealthCheckHandler.create(vertx)
    healthCheck?.register("iamok",{ future ->
      discovery?.getRecord({ r -> r.registration == record?.registration}, {
        asyncRes ->
        when {
          asyncRes.failed() -> future.fail(asyncRes.cause())
          asyncRes.succeeded() -> future.complete(Status.OK())
        }
      })
    })

    println("🎃  " + record?.toJson()?.encodePrettily())

    /* 🚦 === Define a circuit breaker === */
    breaker = CircuitBreaker.create("bsg-circuit-breaker", vertx, CircuitBreakerOptions(
      maxFailures = 5,
      timeout = 20000,
      fallbackOnFailure = true,
      resetTimeout = 100000))

    /* === Define routes === */

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())

    router.get("/api/yo").handler { context ->

      context
        .response()
        .putHeader("content-type", "application/json;charset=UTF-8")
        .end(json {
        obj(
          "message" to "👍", "from" to record?.name
        )
        }.toString())
    }

    // use me with other microservices
    ServiceDiscoveryRestEndpoint.create(router, discovery) // ⚠️ ne pas oublier

    // link/bind healthCheck to a route
    router.get("/health").handler(healthCheck)

    router.route("/*").handler(StaticHandler.create())

    /* === Start the server === */
    val httpPort = System.getenv("PORT")?.toInt() ?: 8080


    vertx.createHttpServer(
      HttpServerOptions(
        port = httpPort
      ))
      .requestHandler {
        router.accept(it)
      }
      .listen { ar ->
        when {
          ar.failed() -> println("😡 Houston?")
          ar.succeeded() -> {
            println("😃 🌍 Microservice started on $httpPort")

            /* 👋 === publish the microservice record to the discovery backend === */
            discovery?.publish(record, { asyncRes ->
              when {
                asyncRes.failed() ->
                  println("😡 Not able to publish the microservice: ${asyncRes.cause().message}")

                asyncRes.succeeded() -> {
                  println("😃 Microservice is published! ${asyncRes.result().registration}")

                  /* 🤖 === search for a baseStar === */
                  searchAndSelectOneBaseStar()

                } // end of succeed
              } // end of when
            }) // end of publish
          } // end of succeed
        } // end of when
      } // end of listen
  } // end of start()

}
