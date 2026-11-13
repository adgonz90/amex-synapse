# Sample: multi-store, two named data stores

An orders service with two data stores, each in its own PostgreSQL database: `orders` on JPA (Hibernate and Spring
Data JPA) and `ledger` on Spring Data JDBC. Placing an order writes the order to one store and a ledger entry to the
other. It is laid out as a real Synapse service: each store is declared, mapped, migrated and configured in a data
module of its own, and the service module holds the operations and the REST API.

It uses `synapse-data-jpa`, `synapse-data-jdbc`, `synapse-data-postgres`, `synapse-api-rest`,
`synapse-logging-log4j2` and the Synapse test modules, including `synapse-test-data`. For REST itself, see
[`rest-books`](../rest-books/README.md); for one store served over REST and gRPC, see the reference application
([`reference-bookstore`](../reference-bookstore/README.md)).

## What it shows

Code paths are under `data-orders/src/main/`, `data-ledger/src/main/` and
`service-orders/src/main/java/io/americanexpress/synapse/samples/multistore/`.

| Feature | Where |
|---|---|
| A store per data module: `@SynapseDataStore(value = "orders", access = DataAccess.JPA)` and `@SynapseDataStore(value = "ledger", access = DataAccess.JDBC)`. Both access modules are on the service's class path, so each store names its access | `data-orders/.../OrdersStore.java`, `data-ledger/.../LedgerStore.java` |
| A JPA entity on Synapse's `AuditedEntity`: a UUIDv7 id from the clock, a `version` for optimistic locking, and the audit columns | `data-orders/.../StoredOrder.java` |
| A Spring Data JDBC aggregate that implements `Auditable` itself, with its own column names (`entry_created`, `entry_created_by`, ...) and an id the database generates | `data-ledger/.../LedgerEntry.java` |
| Repositories in the store's package, registered on that store; no `@EnableJpaRepositories` or `@EntityScan` | `OrderRepository.java`, `LedgerRepository.java` |
| Flyway per store, each from its own `db/migration/<store>`, each into its own database | `db/migration/orders/V1__orders.sql`, `db/migration/ledger/V1__ledger.sql` |
| Module-owned settings: each data module ships `META-INF/synapse/config.yaml` (pool size, readiness) and `config-<env>.yaml` (where to connect) in its jar; the service configures no store | [Configuration](#configuration) |
| Two stores and neither primary: every `@Transactional` names its store (`"orders"`, `"ledger"`), and an unqualified one fails startup | `operation/PlaceOrderOperation.java`, `data-ledger/.../Ledger.java`, [Transactions](#transactions) |
| A write to two stores, which is not atomic, with what the sample does about it | [Transactions](#transactions) |
| The audit user: with no principal signed in, both stores record `synapse.data.auditing.system-user` (`system`) | [The audit user](#the-audit-user) |
| The version as an `ETag` on `POST` and `GET`, and a required `If-Match` on `DELETE` (412 when stale, 428 when missing) | `api/rest/OrdersRestConfiguration.java`, `model/DeleteOrderRequest.java` |
| Readiness that only the `orders` store takes part in, served at `/health` | [Health and readiness](#health-and-readiness) |
| Tests: a `@SynapseDataTest` per store on a PostgreSQL container, the REST slice, the whole service on two containers, readiness with a database stopped, and the layering rules | [Tests](#tests) |

The packages follow Synapse's layering: `api.rest` calls `operation`, which calls `data`; every layer may use `model`
and `contract`, and nothing calls `api`. The data modules' packages, `data.orders` and `data.ledger`, are under the
service's root package, so the service's component scan finds the store classes, and `ArchitectureTest`
(`SynapseArchitecture.forApplication(OrdersApplication.class)`) checks them as the `data` layer.

The entity is `StoredOrder`, and the API returns the record `model.Order`: an operation's input or output is never a
persistence entity (checked at startup and by `ArchitectureTest`), so the operations map one to the other
(`operation/OrderMapping.java`).

## Run it

You need JDK 25 to build, and Docker for the two databases (see [Before you start](../README.md#before-you-start)).

From the repository root, in Git Bash on Windows or any POSIX shell, start the databases: `orders-db` on port 5433
and `ledger-db` on port 5434, both `postgres:18.6-alpine`, pinned by the digest the tests use
(`SynapseContainers.POSTGRES_IMAGE`):

```bash
docker compose -f samples/multi-store/compose.yaml up -d
```

```
 Network multi-store_default Creating 
 Network multi-store_default Creating 
 Network multi-store_default Created 
 Network multi-store_default Created 
 Container multi-store-orders-db-1 Creating 
 Container multi-store-ledger-db-1 Creating 
 Container multi-store-ledger-db-1 Created 
 Container multi-store-orders-db-1 Created 
 Container multi-store-ledger-db-1 Starting 
 Container multi-store-orders-db-1 Starting 
 Container multi-store-ledger-db-1 Started 
 Container multi-store-orders-db-1 Started 
```

Then build the service and run it:

```bash
./mvnw -pl samples/multi-store/service-orders -am package -DskipTests
ENV=local java -jar samples/multi-store/service-orders/target/service-orders-2.0.0-SNAPSHOT.jar
```

The first command builds the service, its two data modules and the Synapse modules they use, without running the
tests (`./mvnw verify` runs them; see [Tests](#tests)). The second runs the `local` environment: `application.yaml`
maps `spring.profiles.active: ${ENV}`, and each data module's `config-local.yaml` points its store at its container.
Without `ENV` the service doesn't start: it needs exactly one environment.

`spring-boot:run` would run the `local` environment too (the service's pom sets `spring-boot.run.profiles`), but it
runs the module alone, so it needs the data modules in your local Maven repository, and this sample's modules never
install (`maven.install.skip`). Use `java -jar` here; in a service of your own, which installs its modules,
`spring-boot:run` works as in `hello-rest`.

The service listens on port 8080 and logs ECS JSON, one object per line. These are the lines that matter (the
`@timestamp`, `process` and `service` fields are left out here). Each store starts its pool and runs its own
migrations, in its own database, before Tomcat takes requests:

```
"message":"The following 1 profile is active: \"local\""
"message":"Initialized JPA EntityManagerFactory for persistence unit 'orders'"
"message":"orders - Start completed."
"message":"Database: jdbc:postgresql://localhost:5433/orders (PostgreSQL 18.6)"
"message":"Migrating schema \"public\" to version \"1 - orders\""
"message":"Successfully applied 1 migration to schema \"public\", now at version v1 (execution time 00:00.013s)"
"message":"ledger - Start completed."
"message":"Database: jdbc:postgresql://localhost:5434/ledger (PostgreSQL 18.6)"
"message":"Migrating schema \"public\" to version \"1 - ledger\""
"message":"Successfully applied 1 migration to schema \"public\", now at version v1 (execution time 00:00.013s)"
"message":"Tomcat started on port 8080 (http) with context path '/'"
"message":"Synapse REST exposes POST /orders -> place-order on port 8080"
"message":"Synapse REST exposes GET /orders/{id} -> get-order on port 8080"
"message":"Synapse REST exposes DELETE /orders/{id} -> cancel-order on port 8080"
"message":"Started OrdersApplication in 5.912 seconds (process running for 6.502)"
"message":"Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)\nConfiguration, highest priority first:\n  1. server.ports\n  2. Synapse baggage fields\n  3. Java system properties\n  4. environment variables\n  5. application-local.yaml  service-orders-2.0.0-SNAPSHOT.jar\n  6. application.yaml        service-orders-2.0.0-SNAPSHOT.jar\n  7. config-local.yaml       data-orders-2.0.0-SNAPSHOT.jar\n  8. config-local.yaml       data-ledger-2.0.0-SNAPSHOT.jar\n  9. config.yaml             data-orders-2.0.0-SNAPSHOT.jar\n  10. config.yaml             data-ledger-2.0.0-SNAPSHOT.jar\n  11. logCorrelation\n  12. applicationInfo\n  13. defaults\n  14. Management Server"
"message":"Synapse data stores: orders (pool 5), ledger (pool 5); at most 10 connections per instance."
```

The configuration report lists the four files of the two data modules, each with the jar it came from, below the
service's own files: the service references neither module's files, and wins over both. The last line is the
connection budget: multiply it by the number of instances to size the databases' connection limits. Stop the service
with Ctrl+C.

## Try it

Run these in a second terminal, in order, against a freshly started service. The responses below are real. The ids,
times and correlation ids differ on every run.

### Place an order

```bash
curl -s -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"customer": "Ada", "amount": 12.50}'
```

```
HTTP/1.1 201 
X-Correlation-Id: demo-1
Location: /orders/01a1b82e-734c-74c2-b7fc-c6e32510308b
ETag: "0"
Content-Type: application/json
Content-Length: 152
Date: Sat, 07 Nov 2026 22:43:34 GMT

{"id":"01a1b82e-734c-74c2-b7fc-c6e32510308b","customer":"Ada","amount":12.50,"createdAt":"2026-11-07T22:43:34.349221Z","createdBy":"system","version":0}
```

The id is a UUIDv7 that the orders store assigned from the service's clock: it starts with the time, so new rows land
at the end of the primary key's index. The version is 0, and it is the `ETag`. `createdBy` is `system`: see
[The audit user](#the-audit-user). Keep the id for the next calls (yours differs):

```bash
ID=01a1b82e-734c-74c2-b7fc-c6e32510308b
```

The order is in the orders database, and its entry in the ledger database, each read here with `psql` in its
container:

```bash
docker compose -f samples/multi-store/compose.yaml exec orders-db psql -U orders -d orders \
  -c "SELECT id, customer, amount, version, created_by FROM orders"
docker compose -f samples/multi-store/compose.yaml exec ledger-db psql -U ledger -d ledger \
  -c "SELECT id, order_id, amount, entry_created_by FROM ledger_entry"
```

```
                  id                  | customer | amount | version | created_by 
--------------------------------------+----------+--------+---------+------------
 01a1b82e-734c-74c2-b7fc-c6e32510308b | Ada      |  12.50 |       0 | system
(1 row)

 id |               order_id               | amount | entry_created_by 
----+--------------------------------------+--------+------------------
  1 | 01a1b82e-734c-74c2-b7fc-c6e32510308b |  12.50 | system
(1 row)
```

Each database has only its own store's table, and its own Flyway history:

```bash
docker compose -f samples/multi-store/compose.yaml exec orders-db psql -U orders -d orders \
  -c "SELECT script FROM flyway_schema_history"
```

```
     script     
----------------
 V1__orders.sql
(1 row)
```

A request that breaks `PlaceOrderRequest`'s constraints (`@Positive` on the amount) is a 400 before the operation
runs, so neither store is written:

```bash
curl -s -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"customer": "Ada", "amount": -1}'
```

```
HTTP/1.1 400 
X-Correlation-Id: 005df873-04fb-4557-b1e6-1e9eea38af0b
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:35 GMT
Connection: close

{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"005df873-04fb-4557-b1e6-1e9eea38af0b","violations":[{"field":"amount","message":"must be greater than 0"}]}
```

### Read it

```bash
curl -s -i http://localhost:8080/orders/$ID
```

```
HTTP/1.1 200 
X-Correlation-Id: 4d5ed574-1a4f-4fb4-a34b-0daa379f2128
Cache-Control: no-cache
Vary: Accept
ETag: "0"
Content-Type: application/json
Content-Length: 152
Date: Sat, 07 Nov 2026 22:43:35 GMT

{"id":"01a1b82e-734c-74c2-b7fc-c6e32510308b","customer":"Ada","amount":12.50,"createdAt":"2026-11-07T22:43:34.349221Z","createdBy":"system","version":0}
```

### Cancel it

A cancel deletes the order, and only at the version the caller names in `If-Match`: `DeleteOrderRequest.version`
isn't `@Nullable`, so the condition is required. A version the order doesn't have is 412:

```bash
curl -s -i -X DELETE http://localhost:8080/orders/$ID -H 'If-Match: "5"'
```

```
HTTP/1.1 412 
X-Correlation-Id: 22fd83be-16f7-41ec-bed1-930a6e70cceb
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:35 GMT

{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"22fd83be-16f7-41ec-bed1-930a6e70cceb"}
```

`CancelOrderOperation` throws `STALE_VERSION`, and the REST edge answers it with 412 `PRECONDITION_FAILED` because the
request carried `If-Match`. The operation's log line keeps the code it threw:

```
"log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"} ... "message":"Operation cancel-order failed with STALE_VERSION (CONFLICT): The resource was changed by another request","correlationId":"22fd83be-16f7-41ec-bed1-930a6e70cceb","errorCategory":"CONFLICT","errorCode":"STALE_VERSION","operation":"cancel-order"
```

Without `If-Match`, the edge answers 428 before the operation runs:

```bash
curl -s -i -X DELETE http://localhost:8080/orders/$ID
```

```
HTTP/1.1 428 
X-Correlation-Id: 87e7532f-cb89-47a5-a9b1-319eb39d48cb
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:35 GMT

{"type":"about:blank","title":"Precondition Required","status":428,"detail":"The request must be conditional","code":"PRECONDITION_REQUIRED","correlationId":"87e7532f-cb89-47a5-a9b1-319eb39d48cb","violations":[{"field":"If-Match","message":"is required; send the ETag from a GET of this resource"}]}
```

With the order's `ETag`, it is cancelled:

```bash
curl -s -i -X DELETE http://localhost:8080/orders/$ID -H 'If-Match: "0"'
```

```
HTTP/1.1 204 
X-Correlation-Id: dbf4b96c-987f-444f-9285-1abdf6aaa118
Date: Sat, 07 Nov 2026 22:43:35 GMT

```

```bash
curl -s -i http://localhost:8080/orders/$ID
```

```
HTTP/1.1 404 
X-Correlation-Id: 1c3239b7-a806-4e3f-8833-ff42ecb6b80d
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:35 GMT

{"type":"about:blank","title":"Not Found","status":404,"detail":"Order 01a1b82e-734c-74c2-b7fc-c6e32510308b was not found","code":"ORDER_NOT_FOUND","correlationId":"1c3239b7-a806-4e3f-8833-ff42ecb6b80d"}
```

The ledger keeps the entry of the placement: a cancel changes the orders store only. A version that changes between
the operation's read and its delete (another request won) fails the delete itself, through JPA's `@Version`, and is
412 too (`OrdersApplicationIT` makes such a change while a cancel runs).

### Health and readiness

`/actuator/health` shows one indicator per store, `db/orders` and `db/ledger` (`application-local.yaml` turns
`show-components` on):

```bash
curl -s http://localhost:8080/actuator/health
```

```json
{"components":{"db":{"components":{"ledger":{"status":"UP"},"orders":{"status":"UP"}},"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```

`/health` is the readiness group, on the server's port, where a load balancer checks whether to send the instance
traffic (`management.endpoint.health.group.readiness.additional-path: server:/health` in `application.yaml`). Its
members are Boot's `readinessState` and the stores that opt in with `health.readiness: true`: data-orders'
`config.yaml` does, data-ledger's doesn't.

```bash
curl -s -i http://localhost:8080/health
```

```
HTTP/1.1 200 
X-Correlation-Id: d993eb89-e7c9-4bde-be08-e344f1b209df
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:35 GMT

{"components":{"db":{"components":{"orders":{"status":"UP"}},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
```

Stop the ledger's database. The service stays ready, and the health endpoint shows the ledger down:

```bash
docker compose -f samples/multi-store/compose.yaml stop ledger-db
curl -s -i http://localhost:8080/health
curl -s -i http://localhost:8080/actuator/health
```

```
 Container multi-store-ledger-db-1 Stopping 
 Container multi-store-ledger-db-1 Stopped 
HTTP/1.1 200 
X-Correlation-Id: 44e54cb1-68c0-4950-993b-444c58926a44
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:36 GMT

{"components":{"db":{"components":{"orders":{"status":"UP"}},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
HTTP/1.1 503 
X-Correlation-Id: ebc5365c-9f81-484e-b317-976aa9336505
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:37 GMT
Connection: close

{"components":{"db":{"components":{"ledger":{"status":"DOWN"},"orders":{"status":"UP"}},"status":"DOWN"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"DOWN"}
```

Placing an order now fails with 503 `UNAVAILABLE`, and the order is rolled back: the orders table stays empty. Here
it fails after the ledger pool's connection timeout, 2 seconds (`hikari.connection-timeout` in data-ledger's
`config.yaml`), which is how long a placement waits for a ledger connection. A placement that is already writing to
the ledger when the database stops fails as soon as its connection breaks, also with 503. See
[Transactions](#transactions) for why the wait is short.

```bash
curl -s -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"customer": "Grace", "amount": 20}'
```

```
HTTP/1.1 503 
X-Correlation-Id: 489e5714-2ec9-4ea9-9077-3e8fcd80201d
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:39 GMT
Connection: close

{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"The service is unavailable","code":"UNAVAILABLE","correlationId":"489e5714-2ec9-4ea9-9077-3e8fcd80201d"}
```

Start the ledger again, and stop the orders database instead. The orders store opted in, so the service reports
itself not ready:

```bash
docker compose -f samples/multi-store/compose.yaml start ledger-db
docker compose -f samples/multi-store/compose.yaml stop orders-db
curl -s -i http://localhost:8080/health
```

```
 Container multi-store-ledger-db-1 Starting 
 Container multi-store-ledger-db-1 Started 
 Container multi-store-orders-db-1 Stopping 
 Container multi-store-orders-db-1 Stopped 
HTTP/1.1 503 
X-Correlation-Id: 103d8948-c626-431f-b080-54ace0c71536
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:42 GMT
Connection: close

{"components":{"db":{"components":{"orders":{"status":"DOWN"}},"status":"DOWN"},"readinessState":{"status":"UP"}},"status":"DOWN"}
```

Start it again. A few seconds later, once the pool reconnects, `/health` is UP:

```bash
docker compose -f samples/multi-store/compose.yaml start orders-db
curl -s -i http://localhost:8080/health
```

```
 Container multi-store-orders-db-1 Starting 
 Container multi-store-orders-db-1 Started 
HTTP/1.1 200 
X-Correlation-Id: 832629ae-992c-47ef-b7b1-494bc997c636
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 22:43:45 GMT

{"components":{"db":{"components":{"orders":{"status":"UP"}},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
```

So is the whole service, the ledger included:

```bash
curl -s http://localhost:8080/actuator/health
```

```json
{"components":{"db":{"components":{"ledger":{"status":"UP"},"orders":{"status":"UP"}},"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```

While a database is down, Hikari logs a WARN for each connection it finds broken and for each one it fails to open.
Health checks never queue on a down database: each store's check waits at most `health.timeout` (1 second).

When you are done, stop the service with Ctrl+C, then the databases (`down -v` also deletes their data):

```bash
docker compose -f samples/multi-store/compose.yaml down
```

## Transactions

There are two stores and neither sets `primary: true`, so there is no default transaction manager. Every
`@Transactional` names its store: `@Transactional("orders")` on `PlaceOrderOperation.execute` and
`CancelOrderOperation.execute`, `@Transactional("ledger")` on `Ledger.record`. A `@Transactional` without a name fails
startup, naming the method (`UnqualifiedTransactionalIT`):

```
There are 2 stores and none is primary, so an unqualified @Transactional can't choose a transaction manager: io.americanexpress.synapse.samples.multistore.UnqualifiedTransactionalIT$UnqualifiedService#save.
```

A repository call needs no `@Transactional`: each repository runs its methods on its own store's transaction manager
(`GetOrderOperation`).

`PlaceOrderOperation` writes to both stores, and that is **not atomic**: each store commits on its own, and there is
no distributed transaction. The order is written in the orders transaction, and flushed, so its own failures happen
first. Then `Ledger.record` writes the entry in a ledger transaction, which commits when it returns. Then the orders
transaction commits.

* When the ledger write fails (the ledger is down, as above), the exception rolls the orders transaction back: neither
  store has the order.
* When the orders commit fails after the ledger committed, the ledger keeps an entry for an order that doesn't exist.

A service that can't accept the second case reconciles the stores, or writes the second record from an outbox table
in the first store. The comment in `PlaceOrderOperation` says so where it matters.

A call to one store inside another store's transaction also holds the outer store's connection while it waits for
the inner one. Here a placement holds an orders connection while `Ledger.record` waits for a ledger connection. With
the ledger down and Hikari's default wait (`connection-timeout`, 30 seconds), five placements at once would hold all
five orders connections for 30 seconds, and every orders read would queue behind them, although the ledger is not part
of readiness. So data-ledger's `config.yaml` sets `hikari.connection-timeout: 2000` (milliseconds): a placement that
waits for a ledger connection gives up after 2 seconds with 503, its order is rolled back, and its orders connection
returns to the pool, so a read waits at most about as long (`ReadinessIT` places six orders at once with the ledger
down, and reads an order while they wait). The timeout bounds only that wait for a connection. A placement whose
ledger connection breaks in the middle of its write (the database stops under it) fails as soon as the driver sees the
break, also with 503 (`ReadinessIT` stops the ledger during three placements). If it breaks while the ledger
transaction commits, nobody knows whether the entry was written, and the placement fails with 500
`COMMIT_OUTCOME_UNKNOWN`, which a client must not retry blindly (see the data module docs, *A connection lost while
committing*). A database that stops answering without closing its connections is not covered by the timeout. Keep the
inner store's wait short, and the pool of the outer store sized for it.

## The audit user

`StoredOrder` (an `AuditedEntity`) and `LedgerEntry` (an `Auditable`) are stamped on every save: when (from the
service's `Clock`) and by whom. The user is the request's principal; nothing signs callers in yet (that is
`synapse-security`, later), so every write records `synapse.data.auditing.system-user`, which is `system` by default:
`created_by` and `modified_by` in `orders`, `entry_created_by` and `entry_modified_by` in `ledger_entry`, as the `psql`
output above shows. To record someone else, set the key (one value for every store), or define an
`AuditorAware<String>` bean that reads a verified identity, never an unverified header.

## Configuration

The service configures no store. Each data module ships its store's settings in its own jar:

```
data-orders/src/main/resources/
  META-INF/synapse/config.yaml          every environment: hikari.maximum-pool-size 5, health.readiness true
  META-INF/synapse/config-local.yaml    local: the orders-db container (localhost:5433)
  db/migration/orders/V1__orders.sql
data-ledger/src/main/resources/
  META-INF/synapse/config.yaml          every environment: hikari.maximum-pool-size 5, hikari.connection-timeout 2000
  META-INF/synapse/config-local.yaml    local: the ledger-db container (localhost:5434)
  db/migration/ledger/V1__ledger.sql
```

`config.yaml` holds what is the same in every environment, `config-<env>.yaml` what differs: the `url`. The service's
`application*.yaml` files win over a module's without a warning; two modules that set the same key fail startup.

The user names and passwords in `config-local.yaml` are those of the throwaway compose containers, and are marked
*local compose only*. In every other environment, credentials never go in a file: the platform sets
`SYNAPSE_DATA_STORES_ORDERS_USERNAME`, `SYNAPSE_DATA_STORES_ORDERS_PASSWORD`, `SYNAPSE_DATA_STORES_LEDGER_USERNAME`
and `SYNAPSE_DATA_STORES_LEDGER_PASSWORD`, and that environment's `config-<env>.yaml` sets only the `url`.

The sample builds without Spring AOT processing, which `synapse-parent` runs only with `-Paot`, so it has no build
environment files. A service that opts in also gives each store a `config-build.yaml` with a `url` on a closed port:
AOT processing creates some store beans, such as the JDBC dialect, which is chosen from the `url`, but never connects.
The [reference bookstore](../reference-bookstore/README.md#aot-mode) shows it.

`service-orders/src/main/resources/application.yaml` sets the application name, the correlation header, and the
health groups: the readiness group with Boot's `readinessState` as its member and the extra path
`/health`. Configuring the readiness group defines it, and a group includes every indicator unless it lists its
members, hence `include: readinessState`; Synapse adds the stores that opt in. `application-local.yaml` turns on
`detail` in problem responses and the health components.

## Tests

| Test | What it checks | Kind |
|---|---|---|
| `data-orders/.../OrderRepositoryIT` | `@SynapseDataTest("orders")` on a PostgreSQL container: the store migrated and validated; a UUIDv7 id and audit stamps from the slice's `MutableClock`, `created_by` `system`; a stale copy saved through `SynapseOperations` with the store's translators is `STALE_VERSION`; the module's `config.yaml` sets the pool | integration |
| `data-ledger/.../LedgerRepositoryIT` | `@SynapseDataTest("ledger")`: the custom audit columns are filled, from the clock, with `system` | integration |
| `api/rest/OrdersRestTest` | The bindings on MockMvc with every operation mocked (`@SynapseRestTest`): `201` with `Location` and `ETag`, a missing amount, `GET` with `ETag`, `ORDER_NOT_FOUND`, `DELETE` with and without `If-Match`; no store starts | unit |
| `ArchitectureTest` | The layering rules, the data modules included | unit |
| `OrdersStoreSliceIT` | `@SynapseDataTest("orders")` in the service, with both data modules on the class path: the orders store starts, the ledger store and the operations don't | integration |
| `OrdersApplicationIT` | The whole service on a free port, each store on its own container: a placement writes one row in each database, both record `system`, an invalid order writes nothing, a stale `If-Match` is 412 and the current one 204, a change between the cancel's read and its delete is 412, each store ran its own migrations, and both modules' `config.yaml` are configuration sources | integration |
| `ReadinessIT` | Each case on containers of its own: the ledger stopped leaves `/health` UP and `/actuator/health` shows `db/ledger` DOWN, and six placements at once fail within seconds with 503 `UNAVAILABLE` (no detail), roll back and leave the orders store serving; the orders database stopped makes `/health` 503 | integration |
| `UnqualifiedTransactionalIT` | An extra bean with an unqualified `@Transactional` fails startup, before any store connects | integration |

The integration tests need Docker: `@StoreConnection("orders")` on a `static` field connects the store to the
Testcontainers database the field holds, instead of its `url`, and `SynapseContainers.postgres("orders")` gives one
container per key, shared by the test classes of the JVM. `ReadinessIT` stops its databases, so it creates its own.

```bash
./mvnw -pl samples/multi-store/service-orders -am verify
```

runs them with everything the sample depends on (`*Test` in the `test` phase, `*IT` in `verify`). To run one unit test
class, or one integration test alone:

```bash
./mvnw -pl samples/multi-store/service-orders -am test -Dtest=OrdersRestTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl samples/multi-store/service-orders -am verify -Dit.test=OrdersApplicationIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

`-Djacoco.skip=true` turns off the coverage gate, which needs the tests of every module the build includes. A data
module's tests run on their own with `-pl samples/multi-store/data-orders -am verify`.

Each data module's tests start from a `@SpringBootConfiguration` in its test sources (`OrdersTestApplication`,
`LedgerTestApplication`), since a data module has no application class, and use `src/test/resources/application-test.yaml`,
which sets nothing. The store's own `config.yaml` applies to them as it is. `service-orders`'
`application-test.yaml` turns on `detail` in problem responses.

## Layout

```
samples/multi-store/
  pom.xml                                 aggregator, parent synapse-parent; modules below
  compose.yaml                            orders-db (5433) and ledger-db (5434), postgres:18.6-alpine by digest
  data-orders/
    pom.xml                               synapse-data-jpa, synapse-data-postgres
    src/main/java/.../multistore/data/orders/
      OrdersStore                         @SynapseDataStore(value = "orders", access = DataAccess.JPA)
      StoredOrder                         the entity, an AuditedEntity
      OrderRepository                     JpaRepository<StoredOrder, UUID>
    src/main/resources/                   META-INF/synapse/config*.yaml, db/migration/orders/
    src/test/                             OrderRepositoryIT, OrdersTestApplication, application-test.yaml
  data-ledger/
    pom.xml                               synapse-data-jdbc, synapse-data-postgres
    src/main/java/.../multistore/data/ledger/
      LedgerStore                         @SynapseDataStore(value = "ledger", access = DataAccess.JDBC)
      LedgerEntry                         the aggregate, Auditable with its own columns
      LedgerRepository                    ListCrudRepository<LedgerEntry, Long>
      Ledger                              writes an entry, @Transactional("ledger")
    src/main/resources/                   META-INF/synapse/config*.yaml, db/migration/ledger/
    src/test/                             LedgerRepositoryIT, LedgerTestApplication, application-test.yaml
  service-orders/
    pom.xml                               the data modules, the REST plugin, logging, actuator, test modules
    src/main/java/.../multistore/
      OrdersApplication.java              @SpringBootApplication
      operation/                          PlaceOrder, GetOrder, CancelOrder, OrderMapping
      model/                              Order, PlaceOrderRequest, GetOrderRequest, DeleteOrderRequest
      contract/OrderErrors                ORDER_NOT_FOUND
      api/rest/OrdersRestConfiguration    RestBindings for /orders
    src/main/resources/
      application.yaml                    the name, ENV, the health groups
      application-local.yaml              local conveniences: problem detail, health components
    src/test/java/.../multistore/         the tests above
    src/test/resources/
      application-test.yaml               the test environment: problem detail on
```

## Make it yours

Copy the directory and change these:

* The four poms: the `groupId`, `artifactId`s and `description`s; the parent's `relativePath` (drop it when the parent
  comes from a repository); and remove the four `maven.*.skip` properties, which only keep this sample from being
  published. Install and deploy the data modules, so that services can depend on them.
* The packages `io.americanexpress.synapse.samples.multistore...` and `OrdersApplication`. Keep each data module's
  package under the service's root package, or `@Import` its store class.
* `spring.application.name` in `application.yaml`.
* Each data module's `config-<env>.yaml`, one per environment you deploy to, with the `url` only; the credentials
  come from the environment variables above.
