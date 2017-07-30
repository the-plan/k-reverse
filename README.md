# Reverse

## Run an instance of the reverse-proxy

First, run Redis

```bash
redis-server
```

Then

```bash
PORT=9090 SERVICE_PORT=9090 ADMIN_PORT=9999 java  -jar target/k-reverse-1.0-SNAPSHOT-fat.jar
```

Now, you can:

- use the proxy: [http://localhost:9090/](http://localhost:9090/)
- use the proxy admin: 
  - get the list of all reverse-proxies: [http://localhost:9999/admin/proxies](http://localhost:9999/admin/proxies)
    ```json
    [ [ {
      "location" : {
        "endpoint" : "http://localhost:9090/api",
        "host" : "localhost",
        "port" : 9090,
        "root" : "/api",
        "ssl" : false
      },
      "metadata" : {
        "message" : "hello 🌍",
        "kind" : "reverse-proxy",
        "admin" : {
          "host" : "localhost",
          "port" : 9999,
          "enpoint" : "http://localhost:9999/admin",
          "services" : [ {
            "webapps" : "/webapps"
          }, {
            "proxies" : "/proxies"
          } ]
        }
      },
      "name" : "my-service-silent-salad-023",
      "status" : "UP",
      "registration" : "6c687aeb-dcb6-47a7-bfbc-48e50df1d825",
      "type" : "http-endpoint"
    } ] ]
    ```
  - get the list of all webapps: [http://localhost:9999/admin/webapps](http://localhost:9999/admin/webapps)
    ```json
    [ [ {
      "id" : "webapp_new1",
      "ip" : "192.168.1.31",
      "port" : 8080,
      "status" : "😍"
    }, {
      "id" : "webapp_new2",
      "ip" : "192.168.1.32",
      "port" : 8080,
      "status" : "😍"
    } ] ]
    ```

## Add a webapp to the reverse-proxy

```bash
curl -H "Content-Type: application/json" -X POST -d '{"id":"webapp_new1","ip":"192.168.1.31","port":8080}' "http://localhost:9999/admin/webapps"
```

## Remove a webapp from the reverse-proxy

```bash
curl -X DELETE "http://localhost:9999/admin/webapps/webapp1"
```

> WIP 🚧 more to come...