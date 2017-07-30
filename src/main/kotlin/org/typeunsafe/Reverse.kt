package org.typeunsafe

import io.vertx.circuitbreaker.CircuitBreaker
import io.vertx.core.*
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler

import io.vertx.kotlin.core.http.HttpServerOptions
import io.vertx.ext.web.handler.StaticHandler

import io.vertx.servicediscovery.types.HttpEndpoint
import io.vertx.servicediscovery.ServiceDiscovery
//import io.vertx.servicediscovery.Status

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
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

fun main(args: Array<String>) {
  val vertx = Vertx.vertx()
  vertx.deployVerticle(Reverse())

}

data class WebApp(val id: String, val ip: String, val port: Int, var status: String) {

}


class Reverse : AbstractVerticle() {

  private var discovery: ServiceDiscovery? = null
  private var record: Record? = null
  private var healthCheck: HealthCheckHandler? =null
  private var breaker: CircuitBreaker? =null
  private val webapps: MutableList<WebApp>? = mutableListOf<WebApp>()
  
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
    
    fun errorPage(message: String): String { return """
      <!doctype html>
      <html>
        <head>
          <meta charset="utf-8">
      
          <style>
          .title
          {
            font-family: "Source Sans Pro", "Helvetica Neue", Arial, sans-serif;
            display: block;
            font-weight: 300;
            font-size: 60px;
            color: #35495e;
            letter-spacing: 1px;
          }
          </style>
        </head>
        <body>
          <h1 class="title">
            $message
          </h1>
        </body>
      </html>
    """}
    
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

    val serviceName = "${System.getenv("SERVICE_NAME") ?: "my-service"}-$niceName"
    val serviceHost = System.getenv("SERVICE_HOST") ?: "localhost" // domain name
    val servicePort = System.getenv("SERVICE_PORT")?.toInt() ?: 80 // servicePort: this is the visible port from outside
    val serviceRoot = System.getenv("SERVICE_ROOT") ?: "/api"
  
    val adminHttpPort = System.getenv("ADMIN_PORT")?.toInt() ?: 8888
    val httpPort = System.getenv("PORT")?.toInt() ?: 8080
  
    
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
        "message" to "hello 🌍",
        "kind" to "reverse-proxy",
        "admin" to obj(
          "host" to serviceHost,
          "port" to adminHttpPort,
          "enpoint" to "http://${serviceHost}:${adminHttpPort}/admin", // TODO: deal with ssl/https
          "services" to mutableListOf(
            obj("webapps" to "/webapps"),
            obj("proxies" to "/proxies")
          )
        )
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
    breaker = CircuitBreaker.create("proxy-circuit-breaker", vertx, CircuitBreakerOptions(
      maxFailures = 5,
      timeout = 20000,
      fallbackOnFailure = true,
      resetTimeout = 100000))

    /* === 🌍 webapps === */

    // sample ...
    webapps?.add(WebApp(id="webapp1", ip="192.168.1.21", port=8080, status="😍"))
    webapps?.add(WebApp(id="webapp2", ip="192.168.1.22", port=8080, status="😍"))
    webapps?.add(WebApp(id="webapp2", ip="192.168.1.23", port=8080, status="😍"))
  
  
    // vagrant ssh webapp1 -c "cd hello-earth; npm start"
    // vagrant ssh webapp2 -c "cd hello-earth; npm start"
    // vagrant ssh webapp2 -c "cd hello-earth; npm start"
  
  
    // === ADMIN ROUTER ===
  
    val adminRouter = Router.router(vertx)
    adminRouter.route().handler(BodyHandler.create())
    adminRouter.get("/health").handler(healthCheck)
    
    // fetch all webapps
    adminRouter.get("/admin/webapps").handler { context ->
      context
        .response()
        .putHeader("content-type", "application/json;charset=UTF-8")
        .end(JsonArray(webapps).encodePrettily())
    }
    
    // add a webapp
    // curl -H "Content-Type: application/json" -X POST -d '{"id":"webapp_new1","ip":"192.168.1.31","port":8080}' "http://localhost:9999/admin/webapps"
  
    adminRouter.post("/admin/webapps").handler { context ->
      
      val data = context.bodyAsJson.let {
        when(it) {
          null -> {
            context
              .response()
              .putHeader("content-type", "application/json;charset=UTF-8")
              .end(json{
                "error" to "unable to register ${it?.getString("id")}"
              }.toString())
          }
          else -> {
            val webapp = WebApp(
              id= it.getString("id"),
              ip= it.getString("ip"),
              port= it.getInteger("port"),
              status="😍"
            )
            webapps?.add(webapp)
            
            context
              .response()
              .putHeader("content-type", "application/json;charset=UTF-8")
              .end(json{
                webapp
              }.toString())
          }
        }
      }
      
    }
    
    // remove a webapps
    // curl -X DELETE "http://localhost:9999/admin/webapps/webapp1"
  
    adminRouter.delete("/admin/webapps/:id").handler { context ->
  
      val webappId = context.request().getParam("id")
  
      webapps?.remove(webapps.first { w -> w.id == webappId})
  
  
      context
        .response()
        .putHeader("content-type", "application/json;charset=UTF-8")
        .end(json {
          obj(
            "message" to "$webappId deleted"
          )
        }.toString())
    }
    
    
    // fetch all proxies
    adminRouter.get("/admin/proxies").handler { context ->
      discovery?.getRecords({r -> r.metadata.getString("kind") == "reverse-proxy"},{ asyncResult ->
        when {
          // --- 😡 ---
          asyncResult.failed() -> {
            //TODO
          }
          // --- 😃 ---
          asyncResult.succeeded() -> {
            context
              .response()
              .putHeader("content-type", "application/json;charset=UTF-8")
              .end(JsonArray(asyncResult.result()).encodePrettily())
          }
        }
      })
    }
    
    
  
    adminRouter.route("/*").handler(StaticHandler.create())
    

    //TODO: list of webapp, add, remove, ...

    /* === REVERSE PROXY ROUTER === */

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())


    // TODO: add post, update, delete and other ...
    
    router.route("/*").handler { context ->

      val client = WebClient.create(vertx)

      val selectedWebApp = webapps?.get(Random().nextInt(webapps.size)) // 😜 yest I know

      println("uri: ${context.request().uri()}")

      context.request().method().let {
        when(it) {
          HttpMethod.GET -> { // Send a GET request
            
            println("👋 this is a GET request")

            client.get(selectedWebApp!!.port, selectedWebApp.ip, context.request().uri()).send { ar ->
              when {
                // --- 😡 ---
                ar.failed() -> {
                  println("😡 Something went wrong with ${selectedWebApp.ip}: ${ar.cause().message}")
                  // TODO healthcheck (before?) + circuitbreaker
                  
                  when {
                    ar.cause().localizedMessage.startsWith("Connection refused") -> {
                      // remove the webapp from the list
                      // TODO change the status?
                      webapps?.remove(selectedWebApp)
                      // redirection - probably something better to do
                      client.get(httpPort,"localhost", context.request().uri()).send({ ar ->
                        when {
                          // --- 😡 ---
                          ar.failed() -> {
                          }
                          // --- 😃 ---
                          ar.succeeded() -> {
                          }
                        }
                      })
      
                    }
                    else -> {
                      context
                        .response()
                        .putHeader("content-type", "text/html")
                        .setStatusCode(666)
                        .setStatusMessage("I left alone, my mind was blank, I needed time to think")
                        .end(errorPage("""
                          😢 Ouch...
                          <p>${ar.cause().message}</p>
                        """))
                    }
                  }
                  
                } // end of fail
                // --- 😃 ---
                ar.succeeded() -> {
                  val response = ar.result()
                  println("😀 Received response with status code${response.statusCode()}")
  
                  //https://en.wikipedia.org/wiki/List_of_HTTP_status_codes
                  response.statusCode().let {
                    when(it) {
                      in 400..499 -> {
                        context
                          .response()
                          .putHeader("content-type", "text/html")
                          .setStatusCode(it)
                          .setStatusMessage(response.statusMessage())
                          .end(errorPage("""
                            😢 Huston? 👋
                            <p>${it} ${response.statusMessage()}</p>
                          """))
                      }
                      in 500..599 -> {
                        context
                          .response()
                          .putHeader("content-type", "text/html")
                          .setStatusCode(it)
                          .setStatusMessage(response.statusMessage())
                          .end(errorPage("""
                            😡 this is the end of 🌍
                            <p>${it} ${response.statusMessage()}</p>
                          """))
                      }
                      else -> {
                        context
                          .response()
                          .putHeader("content-type", response.getHeader("content-type"))
                          .setStatusCode(response.statusCode())
                          .setStatusMessage(response.statusMessage())
                          .end(
                            response.bodyAsString()
                          )
                      }
                    }
                  }
                  
                }
              }
            } // end of send
          } // end of get
          HttpMethod.POST -> { /*TODO*/ }
          HttpMethod.PUT -> { /*TODO*/ }
          HttpMethod.DELETE -> { /*TODO*/ }
          else -> { /*TODO*/ }
        }
      }

    } // end of handler

    
    /**
     * use me with other microservices
     * https://github.com/vert-x3/vertx-service-discovery/blob/master/vertx-service-discovery/src/main/java/io/vertx/servicediscovery/rest/ServiceDiscoveryRestEndpoint.java
     * ```java
     *   ServiceDiscoveryRestEndpoint.create(router, discovery);
     * ```
     *
     * -> then you can call `/discovery` on each Vert.x microservice
     * eg: http://localhost:8081/discovery -> get the list of the microservices
     */
    ServiceDiscoveryRestEndpoint.create(router, discovery)

    
    /* === Start the reverse-proxy === */
    
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
            println("😃 🌍 the reverse-proxy is started on $httpPort")

            /* 👋 === publish the microservice record to the discovery backend === */
            discovery?.publish(record, { asyncRes ->
              when {
                // --- 😡 ---
                asyncRes.failed() ->
                  println("😡 Not able to publish the reverse-proxy: ${asyncRes.cause().message}")
                // --- 😃 ---
                asyncRes.succeeded() -> {
                  println("😃 the reverse-proxy is published! ${asyncRes.result().registration}")
  
                  /* === Start the admin of the reverse-proxy === */
  
                  vertx.createHttpServer(
                    HttpServerOptions(
                      port = adminHttpPort
                    ))
                    .requestHandler {
                      adminRouter.accept(it)
                    }
                    .listen { ar ->
                      when {
                        ar.failed() -> println("😡 Houston?")
                        ar.succeeded() -> println("😃 🌍 the reverse-proxy admin is started on $adminHttpPort")
                      } // end of when
                    } // end of listen
                  

                } // end of succeed
              } // end of when
            }) // end of publish
          } // end of succeed
        } // end of when
      } // end of listen
    
  } // end of start()

}
