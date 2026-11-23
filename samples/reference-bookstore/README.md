# Sample: reference-bookstore, the reference service

A bookstore served over REST and gRPC from one set of operations, on PostgreSQL. It is laid out as a Synapse service
should be: a contract module with what callers depend on, a data module that owns the `books` store, and a bootable
service module with the operations and their two bindings. Both protocols take the same requests and give the same
answers: the same codes, the same entity tags and the same page tokens, so a client can read a book over REST and
update it over gRPC.

It uses `synapse-api-rest`, `synapse-api-rest-openapi`, `synapse-api-grpc`, `synapse-data-jpa`,
`synapse-data-postgres`, `synapse-logging-log4j2` and the Synapse test modules. For a tour of each protocol on its
own, see [`rest-books`](../rest-books/README.md) and [`grpc-inventory`](../grpc-inventory/README.md); for two stores
in one service, see [`multi-store`](../multi-store/README.md).

## What it shows

Code paths are under `contract-bookstore/src/main/`, `data-books/src/main/` and
`service-bookstore/src/main/java/io/americanexpress/synapse/samples/bookstore/`.

| Feature | Where |
|---|---|
| A contract module: the records the operations take and return, the error codes (`BOOK_NOT_FOUND`) and the `.proto`, from which the gRPC classes are generated | `contract-bookstore/` |
| A data module that owns its store: `@SynapseDataStore("books")` on JPA, the entity on `AuditedEntity`, the repository, the Flyway migration and the store's settings for every environment, shipped in its jar | `data-books/` |
| Five operations (create, get, search, update, delete) that know nothing of either protocol | `operation/` |
| One `RestBindings` bean and one `GrpcBindings` bean over the same operations, with the same declarations: the not-found code, the entity tag on the read and the create, the sort keys | `api/rest/BookstoreRestConfiguration.java`, `api/grpc/BookstoreGrpcConfiguration.java` |
| Optimistic locking with a required version: `If-Match` over REST and the `etag` field over gRPC, the same token, so the `ETag` of a REST read updates over gRPC | [ETags across protocols](#etags-across-protocols) |
| Page tokens shared by both protocols: a REST `nextCursor` is a gRPC `page_token`, and the reverse | [Pages across protocols](#pages-across-protocols) |
| Readiness at `/health` that the store takes part in, and gRPC health | [Health and readiness](#health-and-readiness) |
| The OpenAPI document, on in `local` only; CORS for a local front end | [The OpenAPI document](#the-openapi-document), [CORS](#cors) |
| The audit user: with no principal signed in, writes record `synapse.data.auditing.system-user` (`system`) | [The audit user](#the-audit-user) |
| Environments: `local` on `docker compose`, `test`, `build` (AOT processing and an image's training run) and `dev`, an example of a deployed one | [Environments](#environments) |
| AOT mode, opt-in and experimental with `-Paot`: the jar runs the code that `process-aot` generated at build time, and a run in an environment that turns on a build-time switch fails, naming every one | [AOT mode](#aot-mode) |
| A container image two ways, `spring-boot:build-image` (Paketo) and a `Dockerfile` on a digest-pinned Temurin JRE, each with the JVM's AOT cache from a training run in `build`, and a smoke script that runs it in `dev` against PostgreSQL | [Container image](#container-image) |
| The OpenTelemetry Java agent as an alternative to Boot's own SDK: one configuration class, the agent jar from Maven, and a test that runs the jar under it | [OpenTelemetry Java agent](#opentelemetry-java-agent) |
| One scenario suite that runs every case over both protocols and expects the same outcome, against the test context and, with `-Paot`, against the packaged jar in AOT mode | [Tests](#tests) |

The packages follow Synapse's layering: `api.rest` and `api.grpc` call `operation`, which calls `data.books`; every
layer may use `contract`, and nothing calls `api`. The data module's package is under the service's root package, so
the service's component scan finds the store, and `ArchitectureTest` checks it as the `data` layer. The entity is
`StoredBook`, and the operations return the record `contract.Book`: an operation's input or output is never a
persistence entity, so the operations map one to the other (`operation/BookMapping.java`).

## Layout

```
samples/reference-bookstore/
  pom.xml                                   aggregator, parent synapse-parent; modules below
  compose.yaml                              books-db (5432), postgres:18.6-alpine by digest
  scripts/image-smoke.sh                    runs an image in dev against PostgreSQL: /health, POST /books
  contract-bookstore/
    pom.xml                                 protobuf-java, grpc-protobuf, grpc-stub, jakarta.validation-api, synapse-core
    src/main/proto/bookstore/v1/
      bookstore.proto                       BookService and its messages
    src/main/java/.../bookstore/contract/
      Book                                  the record the operations return
      *Request                              the operations' inputs, with their constraints and versions
      BookErrors                            BOOK_NOT_FOUND
  data-books/
    pom.xml                                 synapse-data-jpa, synapse-data-postgres
    src/main/java/.../bookstore/data/books/
      BooksStore                            @SynapseDataStore("books")
      StoredBook                            the entity, an AuditedEntity
      BookRepository                        JpaRepository<StoredBook, UUID>, with the author search
    src/main/resources/
      META-INF/synapse/config.yaml          every environment: hikari.maximum-pool-size 10, health.readiness true
      META-INF/synapse/config-local.yaml    local: the books-db container (localhost:5432)
      META-INF/synapse/config-dev.yaml      dev: the dev database's url; credentials from the environment
      META-INF/synapse/config-build.yaml    build (AOT processing, training run): a closed port, never connected to
      db/migration/books/V1__books.sql
    src/test/                               BookRepositoryIT, BooksTestApplication, application-test.yaml
  service-bookstore/
    pom.xml                                 the plugins, the data module, logging, actuator, test modules; the
                                            agent copy and its checksum, the otel-agent and aot profiles
    Dockerfile                              the image without buildpacks: Temurin 25.0.5+8 JRE, AOT cache
    .dockerignore                           the build context is the jar only
    src/main/java/.../bookstore/
      BookstoreApplication.java             @SpringBootApplication
      operation/                            CreateBook, GetBook, SearchBooks, UpdateBook, DeleteBook, BookMapping
      api/rest/BookstoreRestConfiguration   RestBindings for /books
      api/grpc/BookstoreGrpcConfiguration   GrpcBindings for BookService
      config/OpenTelemetryAgentConfiguration  the agent's SDK for Boot's bridge, only under the agent
    src/main/resources/
      application.yaml                      the name, ENV, the correlation header, the health groups
      application-local.yaml                local conveniences: detail, reflection, API docs, CORS, health components
      application-dev.yaml                  a deployed environment: nothing of its own (comments only)
      application-build.yaml                the environment of AOT processing and the training run (comments only)
    src/test/java/.../bookstore/            the tests below; scenario/ holds the suite, its clients and AppProcess
    src/test/resources/
      application-test.yaml                 the test environment: detail on, for the slice tests
```

## Environments

The environment is the one active Spring profile: `application.yaml` maps `spring.profiles.active: ${ENV}`, so the
platform sets `ENV`, and the service doesn't start without it. Each environment has a file in the service and may have
one in the data module:

| Environment | Service file | data-books file | What it is |
|---|---|---|---|
| `local` | `application-local.yaml` | `config-local.yaml` | Your machine, with the database of `compose.yaml`. Turns on problem detail, gRPC reflection, the OpenAPI document, CORS for `http://localhost:3000` and the health components |
| `test` | `src/test/resources/application-test.yaml` | none | The tests. Problem detail is on for the slice tests; the integration tests turn it off again and connect the store to a container |
| `build` | `application-build.yaml` | `config-build.yaml` | `process-aot` during `package` with `-Paot` (see [AOT mode](#aot-mode)), and an image's training run. Sets nothing, so the build-time switches keep their defaults (off); the store points at a closed port |
| `dev` | `application-dev.yaml` | `config-dev.yaml` | An example of a deployed environment. Changes nothing but where the store connects; the user name and password come from `SYNAPSE_DATA_STORES_BOOKS_USERNAME` and `SYNAPSE_DATA_STORES_BOOKS_PASSWORD` |

Every safety setting keeps its safe default in every environment, and only an environment's file turns a convenience
on. The user name and password in `config-local.yaml` are those of the throwaway compose container, marked *local
compose only*; no other file holds a credential.

## Run it

You need JDK 25 to build, and Docker for the database (see [Before you start](../README.md#before-you-start)).

From the repository root, in Git Bash on Windows or any POSIX shell, start the database: `books-db` on port 5432,
`postgres:18.6-alpine`, pinned by the digest the tests use (`SynapseContainers.POSTGRES_IMAGE`). `down -v` first
removes a database left from an earlier run, so the calls below start from an empty catalog:

```bash
docker compose -f samples/reference-bookstore/compose.yaml down -v
docker compose -f samples/reference-bookstore/compose.yaml up -d
```

```
 Network reference-bookstore_default Creating 
 Network reference-bookstore_default Creating 
 Network reference-bookstore_default Created 
 Network reference-bookstore_default Created 
 Container reference-bookstore-books-db-1 Creating 
 Container reference-bookstore-books-db-1 Created 
 Container reference-bookstore-books-db-1 Starting 
 Container reference-bookstore-books-db-1 Started 
```

Then build the service and run it:

```bash
./mvnw -pl samples/reference-bookstore/service-bookstore -am package -DskipTests
ENV=local java -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-2.0.0-SNAPSHOT.jar
```

The first command builds the service, its contract and data modules and the Synapse modules they use, without
running the tests (`./mvnw verify` runs them; see [Tests](#tests)). It doesn't run Spring's AOT processing, which is
opt-in (see [AOT mode](#aot-mode)). The second runs the `local` environment.

`spring-boot:run` would run the `local` environment too (the service's pom sets `spring-boot.run.profiles`), but it
runs the module alone, so it needs the contract and data modules in your local Maven repository, and this sample's
modules never install (`maven.install.skip`). Use `java -jar` here; in a service of your own, which installs its
modules, `spring-boot:run` works as in `hello-rest`.

The service listens on port 8080 (REST) and 9090 (gRPC) and logs ECS JSON, one object per line. These are the lines
that matter (the `@timestamp`, `process` and `service` fields are left out here). The store starts its pool and runs
its migration before either server takes requests:

```
"message":"The following 1 profile is active: \"local\""
"message":"Initialized JPA EntityManagerFactory for persistence unit 'books'"
"message":"synapse.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor is rejected after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32)."
"message":"books - Start completed."
"message":"Database: jdbc:postgresql://localhost:5432/bookstore (PostgreSQL 18.6)"
"message":"Migrating schema \"public\" to version \"1 - books\""
"message":"Successfully applied 1 migration to schema \"public\", now at version v1 (execution time 00:00.016s)"
"message":"Tomcat started on port 8080 (http) with context path '/'"
"message":"Synapse REST exposes POST /books -> create-book on port 8080"
"message":"Synapse REST exposes GET /books -> search-books on port 8080"
"message":"Synapse REST exposes GET /books/{id} -> get-book on port 8080"
"message":"Synapse REST exposes PUT /books/{id} -> update-book on port 8080"
"message":"Synapse REST exposes DELETE /books/{id} -> delete-book on port 8080"
"message":"Registered gRPC service: synapse.samples.bookstore.v1.BookService"
"message":"Registered gRPC service: grpc.reflection.v1.ServerReflection"
"message":"Registered gRPC service: grpc.health.v1.Health"
"message":"gRPC Server started, listening on address: [/[0:0:0:0:0:0:0:0]:9090], port: 9090"
"message":"Synapse gRPC exposes synapse.samples.bookstore.v1.BookService/CreateBook -> create-book on port 9090"
"message":"Synapse gRPC exposes synapse.samples.bookstore.v1.BookService/GetBook -> get-book on port 9090"
"message":"Synapse gRPC exposes synapse.samples.bookstore.v1.BookService/SearchBooks -> search-books on port 9090"
"message":"Synapse gRPC exposes synapse.samples.bookstore.v1.BookService/UpdateBook -> update-book on port 9090"
"message":"Synapse gRPC exposes synapse.samples.bookstore.v1.BookService/DeleteBook -> delete-book on port 9090"
"message":"Started BookstoreApplication in 8.714 seconds (process running for 9.919)"
"message":"Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)\nConfiguration, highest priority first:\n  1. server.ports\n  2. Synapse baggage fields\n  3. Java system properties\n  4. environment variables\n  5. application-local.yaml  service-bookstore-2.0.0-SNAPSHOT.jar\n  6. application.yaml        service-bookstore-2.0.0-SNAPSHOT.jar\n  7. config-local.yaml       data-books-2.0.0-SNAPSHOT.jar\n  8. config.yaml             data-books-2.0.0-SNAPSHOT.jar\n  9. logCorrelation\n  10. applicationInfo\n  11. defaults\n  12. Management Server"
"message":"Synapse data stores: books (pool 10); at most 10 connections per instance."
```

Both plugins expose the same five operations. The configuration report lists data-books' two files, each with the jar
it came from, below the service's own files: the service references neither, and wins over both. The last line is the
connection budget: multiply it by the number of instances to size the database's connection limit. The WARN about
`synapse.paging.cursor.key` is explained in [Safe defaults](#safe-defaults). Stop the service with Ctrl+C.

## Try it

Run these in a second terminal, in order, from here to [Delete it](#delete-it), against a freshly started service on
an empty database. The responses below are real. The ids, times, page tokens and correlation ids differ on every run.

### Over REST

Add a book:

```bash
curl -s -i -X POST http://localhost:8080/books -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"isbn": "978-0547773742", "title": "A Wizard of Earthsea", "author": "Ursula K. Le Guin", "publishedYear": 1968}'
```

```
HTTP/1.1 201 
X-Correlation-Id: demo-1
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Location: /books/01a1b9de-9966-7453-b15a-1e0c925a8d46
ETag: "0"
Content-Type: application/json
Content-Length: 162
Date: Sun, 08 Nov 2026 06:35:35 GMT

{"id":"01a1b9de-9966-7453-b15a-1e0c925a8d46","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","publishedYear":1968,"version":0}
```

The id is a UUIDv7 that the store assigned from the service's clock. The version is 0, and it is the `ETag`, ready
for the `If-Match` of an update or a delete. The `Vary` headers are CORS's, which `local` turns on (see
[CORS](#cors)). Keep the id for the next calls (yours differs):

```bash
ID=01a1b9de-9966-7453-b15a-1e0c925a8d46
```

Read it:

```bash
curl -s -i http://localhost:8080/books/$ID
```

```
HTTP/1.1 200 
X-Correlation-Id: e53735cc-7623-419a-a720-38c4bd57b052
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Cache-Control: no-cache
Vary: Accept
ETag: "0"
Content-Type: application/json
Content-Length: 162
Date: Sun, 08 Nov 2026 06:35:52 GMT

{"id":"01a1b9de-9966-7453-b15a-1e0c925a8d46","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","publishedYear":1968,"version":0}
```

A request that breaks `CreateBookRequest`'s constraints is a 400 before the operation runs, and an ISBN another book
has is a 409, from the table's unique constraint:

```bash
curl -s -i -X POST http://localhost:8080/books -H 'Content-Type: application/json' \
  -d '{"isbn": "978-0547773742", "title": " ", "author": "Ursula K. Le Guin"}'
curl -s -i -X POST http://localhost:8080/books -H 'Content-Type: application/json' \
  -d '{"isbn": "978-0547773742", "title": "Another", "author": "Ursula K. Le Guin"}'
```

```
HTTP/1.1 400 
X-Correlation-Id: 678a12e1-6ad4-4fde-9a89-f863651d7f53
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:35:52 GMT
Connection: close

{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"678a12e1-6ad4-4fde-9a89-f863651d7f53","violations":[{"field":"title","message":"must not be blank"}]}
HTTP/1.1 409 
X-Correlation-Id: c8517f67-5060-4119-b348-b28d03296bf4
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:35:52 GMT

{"type":"about:blank","title":"Conflict","status":409,"detail":"The resource already exists","code":"ALREADY_EXISTS","correlationId":"c8517f67-5060-4119-b348-b28d03296bf4"}
```

### Over gRPC

The calls below use [grpcurl](https://github.com/fullstorydev/grpcurl) 1.9.3 from its Docker image, pinned by digest
(read with `docker buildx imagetools inspect fullstorydev/grpcurl:v1.9.3`), so you need Docker and nothing else.
Define it once per shell:

```bash
grpcurl() {
  docker run --rm fullstorydev/grpcurl:v1.9.3@sha256:085e183ca334eb4e81ca81ee12cbb2b2737505d1d77f5e33dabc5d066593d998 "$@"
}
```

The container reaches the service on the host as `host.docker.internal:9090`. Docker Desktop (Windows, macOS) knows
that name; on Linux, add `--add-host=host.docker.internal:host-gateway` after `docker run --rm`. In Git Bash on
Windows, if `docker` isn't found, put Docker Desktop's `resources/bin` directory first on `PATH` (see
[Run it](#run-it)), and `export MSYS_NO_PATHCONV=1` so Git Bash leaves the arguments alone.

Under `local`, server reflection is on, so grpcurl needs no `.proto` file. Every other environment keeps Synapse's
default, reflection off, so a caller there uses the contract module's stubs or its `.proto`. grpcurl prints protobuf
JSON with the proto field names, and leaves out every field at its default (an empty string, `0`, `false`) and every
unset wrapper.

```bash
grpcurl -plaintext host.docker.internal:9090 list
grpcurl -plaintext host.docker.internal:9090 describe synapse.samples.bookstore.v1.BookService
```

```
grpc.health.v1.Health
grpc.reflection.v1.ServerReflection
synapse.samples.bookstore.v1.BookService
synapse.samples.bookstore.v1.BookService is a service:
service BookService {
  rpc CreateBook ( .synapse.samples.bookstore.v1.CreateBookRequest ) returns ( .synapse.samples.bookstore.v1.Book );
  rpc DeleteBook ( .synapse.samples.bookstore.v1.DeleteBookRequest ) returns ( .google.protobuf.Empty );
  rpc GetBook ( .synapse.samples.bookstore.v1.GetBookRequest ) returns ( .synapse.samples.bookstore.v1.Book );
  rpc SearchBooks ( .synapse.samples.bookstore.v1.SearchBooksRequest ) returns ( .synapse.samples.bookstore.v1.SearchBooksResponse );
  rpc UpdateBook ( .synapse.samples.bookstore.v1.UpdateBookRequest ) returns ( .synapse.samples.bookstore.v1.Book );
}
```

The book added over REST, read over gRPC. The `etag` is REST's `ETag` value, with its quotes; the version is 0, its
default, so grpcurl leaves it out:

```bash
grpcurl -plaintext -d "{\"id\": \"$ID\"}" host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/GetBook
```

```
{
  "id": "01a1b9de-9966-7453-b15a-1e0c925a8d46",
  "isbn": "978-0547773742",
  "title": "A Wizard of Earthsea",
  "author": "Ursula K. Le Guin",
  "published_year": 1968,
  "etag": "\"0\""
}
```

Add two more books over gRPC, for the pages below:

```bash
grpcurl -plaintext -d '{"isbn": "978-1481465267", "title": "The Tombs of Atuan", "author": "Ursula K. Le Guin", "published_year": 1970}' \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/CreateBook
grpcurl -plaintext -d '{"isbn": "978-1481465274", "title": "The Farthest Shore", "author": "Ursula K. Le Guin", "published_year": 1972}' \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/CreateBook
```

```
{
  "id": "01a1b9de-e678-7735-a101-1f43635a1944",
  "isbn": "978-1481465267",
  "title": "The Tombs of Atuan",
  "author": "Ursula K. Le Guin",
  "published_year": 1970,
  "etag": "\"0\""
}
{
  "id": "01a1b9de-e8fb-7434-93d3-135f7e82e3b7",
  "isbn": "978-1481465274",
  "title": "The Farthest Shore",
  "author": "Ursula K. Le Guin",
  "published_year": 1972,
  "etag": "\"0\""
}
```

A required field the request leaves out is `must not be null` on both protocols, as REST reports a missing property.
The contract declares the requests' required scalars `optional` (proto3), so the gRPC edge sees that `title` wasn't
sent, instead of reading `""`, and neither call reaches the operation:

```bash
curl -s -i -X POST http://localhost:8080/books -H 'Content-Type: application/json' \
  -d '{"isbn": "978-1481465281", "author": "Ursula K. Le Guin"}'
grpcurl -plaintext -d '{"isbn": "978-1481465281", "author": "Ursula K. Le Guin"}' \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/CreateBook
```

```
HTTP/1.1 400 
X-Correlation-Id: f6d41a10-8996-4b5b-90be-0a2bfd139d3d
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 22 Nov 2026 07:45:16 GMT
Connection: close

{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"f6d41a10-8996-4b5b-90be-0a2bfd139d3d","violations":[{"field":"title","message":"must not be null"}]}
ERROR:
  Code: InvalidArgument
  Message: The request is invalid
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "bookstore",
    	  "metadata": {
    	    "correlationId": "ba0825ae-463c-4618-98ba-2ecef854d54b"
    	  },
    	  "reason": "VALIDATION_FAILED"
    	}
  2)	{
    	  "@type": "type.googleapis.com/google.rpc.BadRequest",
    	  "field_violations": [
    	    {
    	      "field": "title",
    	      "description": "must not be null"
    	    }
    	  ]
    	}
```

### ETags across protocols

An update or a delete must name the version it changes: `UpdateBookRequest.version` and `DeleteBookRequest.version`
aren't `@Nullable`, so REST requires `If-Match` and gRPC the `etag` field. Both take the same token. Update the book
over gRPC with the `ETag` of the REST read, `"0"`, sent as it is:

```bash
grpcurl -plaintext -d "{\"id\": \"$ID\", \"isbn\": \"978-0547773742\", \"title\": \"A Wizard of Earthsea (Earthsea, book 1)\", \"author\": \"Ursula K. Le Guin\", \"published_year\": 1968, \"etag\": \"\\\"0\\\"\"}" \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/UpdateBook
```

```
{
  "id": "01a1b9de-9966-7453-b15a-1e0c925a8d46",
  "isbn": "978-0547773742",
  "title": "A Wizard of Earthsea (Earthsea, book 1)",
  "author": "Ursula K. Le Guin",
  "published_year": 1968,
  "version": "1"
}
```

The version is now 1 (grpcurl writes an `int64` as a string). An update answers without an entity tag, on both
protocols, as REST sends no `ETag` on a `PUT`: a client reads the book again for the new one. The same `"0"` again is
stale, and gRPC answers `ABORTED`, as AIP-154 answers a stale etag (read the book again and retry), with REST's code,
`PRECONDITION_FAILED`, in `ErrorInfo`:

```bash
grpcurl -plaintext -d "{\"id\": \"$ID\", \"isbn\": \"978-0547773742\", \"title\": \"Changed\", \"author\": \"Ursula K. Le Guin\", \"etag\": \"\\\"0\\\"\"}" \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/UpdateBook
```

```
ERROR:
  Code: Aborted
  Message: A precondition failed
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "bookstore",
    	  "metadata": {
    	    "correlationId": "d3b798d5-fa95-44e2-8bfa-435232f097d8"
    	  },
    	  "reason": "PRECONDITION_FAILED"
    	}
```

The domain is `spring.application.name`. Over REST, the book now has the `ETag` `"1"`, and the old one is a 412:

```bash
curl -s -i http://localhost:8080/books/$ID
curl -s -i -X PUT http://localhost:8080/books/$ID -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"isbn": "978-0547773742", "title": "Changed", "author": "Ursula K. Le Guin"}'
```

```
HTTP/1.1 200 
X-Correlation-Id: 4e1861bf-b4f4-4811-a0b6-7a0cafe07e4c
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Cache-Control: no-cache
Vary: Accept
ETag: "1"
Content-Type: application/json
Content-Length: 181
Date: Sun, 08 Nov 2026 06:36:22 GMT

{"id":"01a1b9de-9966-7453-b15a-1e0c925a8d46","isbn":"978-0547773742","title":"A Wizard of Earthsea (Earthsea, book 1)","author":"Ursula K. Le Guin","publishedYear":1968,"version":1}
HTTP/1.1 412 
X-Correlation-Id: f44a7917-a960-4dae-bad9-2ba5cfb12b90
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:36:22 GMT

{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"f44a7917-a960-4dae-bad9-2ba5cfb12b90"}
```

Without a version, the edge answers before the operation runs: REST 428 naming `If-Match`, gRPC
`FAILED_PRECONDITION` with `PRECONDITION_REQUIRED` and a `BadRequest` naming `etag`:

```bash
curl -s -i -X DELETE http://localhost:8080/books/$ID
grpcurl -plaintext -d "{\"id\": \"$ID\"}" host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/DeleteBook
```

```
HTTP/1.1 428 
X-Correlation-Id: 802c19ca-b98a-454e-aeb6-7bd7e6e7c02e
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:36:22 GMT

{"type":"about:blank","title":"Precondition Required","status":428,"detail":"The request must be conditional","code":"PRECONDITION_REQUIRED","correlationId":"802c19ca-b98a-454e-aeb6-7bd7e6e7c02e","violations":[{"field":"If-Match","message":"is required; send the ETag from a GET of this resource"}]}
ERROR:
  Code: FailedPrecondition
  Message: The request must be conditional
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "bookstore",
    	  "metadata": {
    	    "correlationId": "782901ae-8b64-40fa-9483-821e117f6813"
    	  },
    	  "reason": "PRECONDITION_REQUIRED"
    	}
  2)	{
    	  "@type": "type.googleapis.com/google.rpc.BadRequest",
    	  "field_violations": [
    	    {
    	      "field": "etag",
    	      "description": "is required; send the etag from a read of this resource, or * for any current version"
    	    }
    	  ]
    	}
```

`*` names any current version, on both protocols. A weak tag (`W/"1"`) is `PRECONDITION_FAILED`, since a version is
compared strongly; over gRPC its status is `FAILED_PRECONDITION`, not `ABORTED`, because reading again doesn't fix it.

### Pages across protocols

Search the author's books over REST, two per page, by title. The search matches any part of the author's name,
ignoring case:

```bash
curl -s -i 'http://localhost:8080/books?author=le%20guin&size=2&sort=title'
```

```
HTTP/1.1 200 
X-Correlation-Id: b373df64-b90d-41a4-82b9-2012a1c4067b
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Link: </books?author=le%20guin&size=2&sort=title&offset=2>; rel="next"
Content-Type: application/json
Content-Length: 454
Date: Sun, 08 Nov 2026 06:36:23 GMT

{"items":[{"id":"01a1b9de-9966-7453-b15a-1e0c925a8d46","isbn":"978-0547773742","title":"A Wizard of Earthsea (Earthsea, book 1)","author":"Ursula K. Le Guin","publishedYear":1968,"version":1},{"id":"01a1b9de-e8fb-7434-93d3-135f7e82e3b7","isbn":"978-1481465274","title":"The Farthest Shore","author":"Ursula K. Le Guin","publishedYear":1972,"version":0}],"nextCursor":"AmLRkCsdTa27z5dZNSCPZxvnYy-6R77-J-hdKRY0f3nOTVinZGff-pNKqmA","hasNext":true,"total":3}
```

`nextCursor` is the page token for the next page; `Link` gives the same page by offset. Send the token over gRPC as
`page_token`, with the same filter and sort (gRPC's `order_by` names the sort keys in snake case, `title`,
`published_year desc`):

```bash
TOKEN=AmLRkCsdTa27z5dZNSCPZxvnYy-6R77-J-hdKRY0f3nOTVinZGff-pNKqmA
grpcurl -plaintext -d "{\"author\": \"le guin\", \"page_size\": 2, \"order_by\": \"title\", \"page_token\": \"$TOKEN\"}" \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/SearchBooks
```

```
{
  "books": [
    {
      "id": "01a1b9de-e678-7735-a101-1f43635a1944",
      "isbn": "978-1481465267",
      "title": "The Tombs of Atuan",
      "author": "Ursula K. Le Guin",
      "published_year": 1970
    }
  ],
  "total_size": "3"
}
```

The last page: no `next_page_token`, and `has_next` is false, so grpcurl leaves both out. A search answers no entity
tags. The token is bound to the operation, the filter and the sort, so with another sort it is a violation on the
token, never a page of other data:

```bash
grpcurl -plaintext -d "{\"author\": \"le guin\", \"page_size\": 2, \"order_by\": \"published_year desc\", \"page_token\": \"$TOKEN\"}" \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/SearchBooks
```

```
ERROR:
  Code: InvalidArgument
  Message: The request is invalid
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "bookstore",
    	  "metadata": {
    	    "correlationId": "92034854-f0ab-4a55-b6b5-c57c48eb9900"
    	  },
    	  "reason": "VALIDATION_FAILED"
    	}
  2)	{
    	  "@type": "type.googleapis.com/google.rpc.BadRequest",
    	  "field_violations": [
    	    {
    	      "field": "page_token",
    	      "description": "is not valid for this request"
    	    }
    	  ]
    	}
```

The reverse works too: a gRPC `next_page_token` is a REST `cursor`. Over gRPC, by year, newest first:

```bash
grpcurl -plaintext -d '{"author": "le guin", "page_size": 2, "order_by": "published_year desc"}' \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/SearchBooks
```

```
{
  "books": [
    {
      "id": "01a1b9de-e8fb-7434-93d3-135f7e82e3b7",
      "isbn": "978-1481465274",
      "title": "The Farthest Shore",
      "author": "Ursula K. Le Guin",
      "published_year": 1972
    },
    {
      "id": "01a1b9de-e678-7735-a101-1f43635a1944",
      "isbn": "978-1481465267",
      "title": "The Tombs of Atuan",
      "author": "Ursula K. Le Guin",
      "published_year": 1970
    }
  ],
  "next_page_token": "AmLRkCtnTKNoHoMuY8Uw7mhwX0aemd38QBBeGUgMAhJUxSArGVFCdVP7buA",
  "has_next": true,
  "total_size": "3"
}
```

Sent as REST's `cursor` with the same filter and sort (`sort=-publishedYear`), that `next_page_token` gives the
third book; `BookstoreScenarios.grpcPageTokenPagesOverRest` checks it on every build. The search pages by offset
(Spring Data's `Page`), so every token carries the next offset. A page has 20 books by default and at most 100, and
an offset is at most 1000 (`synapse.paging.*`).

### Not found

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000099
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000099"}' \
  host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/GetBook
```

```
HTTP/1.1 404 
X-Correlation-Id: 62e2f759-fb5d-4880-b987-fa61db9f48ca
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:36:42 GMT

{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000099 was not found","code":"BOOK_NOT_FOUND","correlationId":"62e2f759-fb5d-4880-b987-fa61db9f48ca"}
ERROR:
  Code: NotFound
  Message: The resource was not found
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "bookstore",
    	  "metadata": {
    	    "correlationId": "fd078b60-bf1e-4221-90ca-b1be289fc9ab"
    	  },
    	  "reason": "BOOK_NOT_FOUND"
    	}
```

The same code on both, from `.notFound(BookErrors.BOOK_NOT_FOUND)` in each binding. The texts differ only because
`local` turns detail on: REST formats `BOOK_NOT_FOUND`'s message with the path's id, and the gRPC edge, which has no
path, gives `NOT_FOUND`'s own description. With detail off, the default in every other environment, neither carries
a message of the operation's.

## Health and readiness

`/health` is the readiness group, on the server's port, where a load balancer checks whether to send the instance
traffic (`management.endpoint.health.group.readiness.additional-path: server:/health` in `application.yaml`). Its
members are Boot's `readinessState` and the books store, which opts in with `health.readiness: true` in data-books'
`config.yaml`; `application-local.yaml` shows the components:

```bash
curl -s -i http://localhost:8080/health
curl -s http://localhost:8080/actuator/health
```

```
HTTP/1.1 200 
X-Correlation-Id: ed8e1de6-c59c-4729-b1b7-abca31770250
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:36:43 GMT

{"components":{"db":{"components":{"books":{"status":"UP"}},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
{"components":{"db":{"components":{"books":{"status":"UP"}},"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```

While the database is down, `/health` answers 503 DOWN, so the platform stops sending the instance traffic
(`BookstoreOperationalIT` stops its database to check it; [`multi-store`](../multi-store/README.md) shows it with
`docker compose stop`). Configuring the readiness group defines it, and a group includes every indicator unless it
lists its members, so when an application configures the group, as `application.yaml` does with `additional-path`,
Synapse defaults its `include` to `readinessState` (an `include` of yours replaces it; without a key of the group,
Boot's probe group keeps its settings, and the stores that opt in still join it) and adds the stores that opt in;
`application.yaml` sets no `include`.

The gRPC port serves Boot's `grpc.health.v1.Health`:

```bash
grpcurl -plaintext host.docker.internal:9090 grpc.health.v1.Health/Check
```

```
{
  "status": "SERVING"
}
```

## The OpenAPI document

`synapse-api-rest-openapi` documents the routes of `BookstoreRestConfiguration` in an OpenAPI 3.1 document, which
springdoc serves at `/v3/api-docs`. It describes every route to anyone who can reach the service, so it is off by
default in every environment (404 `ROUTE_NOT_FOUND`), and only `application-local.yaml` turns it on
(`springdoc.api-docs.enabled: true`):

```bash
curl -s http://localhost:8080/v3/api-docs | head -c 600
```

```
{"openapi":"3.1.0","info":{"title":"OpenAPI definition","version":"v0"},"servers":[{"url":"http://localhost:8080","description":"Generated server url"}],"paths":{"/books":{"get":{"operationId":"search-books","parameters":[{"name":"author","in":"query","required":false,"schema":{"type":"string","maxLength":255}},{"name":"cursor","in":"query","required":false,"schema":{"type":"string"}},{"name":"offset","in":"query","required":false,"schema":{"type":"integer","format":"int64","maximum":1000,"minimum":0}},{"name":"size","in":"query","required":false,"schema":{"type":"integer","default":20,"maximu
```

`PUT` and `DELETE` of `/books/{id}` document `If-Match` as required, with 412 and 428 among their responses:

```bash
curl -s http://localhost:8080/v3/api-docs | grep -o '"name":"If-Match"[^}]*}[^}]*}' | head -1
```

```
"name":"If-Match","in":"header","description":"An entity tag (\"5\"), or *.","required":true,"schema":{"type":"string"}}
```

The document is a build-time switch under AOT: see [AOT mode](#aot-mode). The gRPC API's description is its
`.proto`, in the contract module, and reflection in `local`.

## CORS

`application-local.yaml` allows one origin, `http://localhost:3000` (`synapse.api.rest.cors.allowed-origins`), so a
front end served there during development can call the REST API from the browser. Without origins, the default in
every environment, CORS is off. Before a `PUT` with `If-Match`, the browser sends a pre-flight, which the edge answers
without running an operation; any other origin is rejected with a problem response:

```bash
curl -s -i -X OPTIONS http://localhost:8080/books/$ID \
  -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: PUT' \
  -H 'Access-Control-Request-Headers: Content-Type, If-Match'
curl -s -i http://localhost:8080/books/$ID -H 'Origin: https://evil.example'
```

```
HTTP/1.1 200 
X-Correlation-Id: 2da96af3-411d-45af-82b2-c45434eebf06
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET,HEAD,POST,PUT,DELETE
Access-Control-Allow-Headers: Content-Type, If-Match
Access-Control-Expose-Headers: X-Correlation-Id, ETag, Link, Location, Retry-After, Deprecation, Sunset
Access-Control-Max-Age: 1800
Content-Length: 0
Date: Sun, 08 Nov 2026 06:36:44 GMT

HTTP/1.1 403 
X-Correlation-Id: 83b156c7-6fa1-4750-97b1-aa3e4fefefa6
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sun, 08 Nov 2026 06:36:44 GMT

{"type":"about:blank","title":"Forbidden","status":403,"detail":"The cross-origin request is not allowed","code":"CORS_REJECTED","correlationId":"83b156c7-6fa1-4750-97b1-aa3e4fefefa6"}
```

CORS is a browser's concern, so gRPC has none. A deployed environment lists the exact origins of its own front ends
in its `application-<env>.yaml` (`application-dev.yaml` has an example in its comments).

## The audit user

`StoredBook` is an `AuditedEntity`: every save stamps when (from the service's `Clock`) and by whom. The user is the
request's principal; nothing signs callers in yet (that is `synapse-security`, later), so every write records
`synapse.data.auditing.system-user`, which is `system` by default, in `created_by` and `modified_by`, whichever
protocol the write came over. Read them with `psql` in the container:

```bash
docker compose -f samples/reference-bookstore/compose.yaml exec books-db psql -U bookstore -d bookstore \
  -c "SELECT title, version, created_by, modified_by FROM book ORDER BY title"
```

```
                  title                  | version | created_by | modified_by 
-----------------------------------------+---------+------------+-------------
 A Wizard of Earthsea (Earthsea, book 1) |       1 | system     | system
 The Farthest Shore                      |       0 | system     | system
 The Tombs of Atuan                      |       0 | system     | system
(3 rows)
```

To record someone else, set the key, or define an `AuditorAware<String>` bean that reads a verified identity, never an
unverified header. The audit columns are not part of `Book`, so neither API returns them.

## Delete it

With its current `ETag`, the book is deleted, for both protocols:

```bash
curl -s -i -X DELETE http://localhost:8080/books/$ID -H 'If-Match: "1"'
grpcurl -plaintext -d "{\"id\": \"$ID\"}" host.docker.internal:9090 synapse.samples.bookstore.v1.BookService/GetBook
```

```
HTTP/1.1 204 
X-Correlation-Id: 11c68d7a-d2e5-4be7-8233-11fc23f629b7
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Date: Sun, 08 Nov 2026 06:36:44 GMT

ERROR:
  Code: NotFound
  Message: The resource was not found
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "bookstore",
    	  "metadata": {
    	    "correlationId": "7a9b9417-c8c6-4287-aba2-24545e204372"
    	  },
    	  "reason": "BOOK_NOT_FOUND"
    	}
```

## Safe defaults

No cursor key is set, so Synapse generates one at startup and warns, at every start, that page tokens won't survive a
restart or work on another instance (the WARN in [Run it](#run-it)). To silence it, give the service a key: stop it,
and start it again with the key in the environment variable `SYNAPSE_PAGING_CURSOR_KEY`, as `application-local.yaml`
says. Boot binds that variable to `synapse.paging.cursor.key` (dots become underscores, letters upper case), so no
file names the key. The variable is set for the `java` command only, so the rest of your shell is unchanged. A real
deployment sets the same key on every instance, from the environment or a secret store, never from a file in the
repository:

```bash
KEY=$(openssl rand -base64 32)
SYNAPSE_PAGING_CURSOR_KEY=$KEY ENV=local java -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-2.0.0-SNAPSHOT.jar
```

The startup log then has no line about `synapse.paging.cursor.key`, and no WARN at all.

The other safe defaults hold in every environment but `local`: problem responses and gRPC statuses carry no detail,
reflection and the OpenAPI document are off, CORS is off, and the health endpoints show only the overall status.

## AOT mode

`-Paot` is experimental until P4: it may change without notice. Spring's AOT processing is opt-in: `synapse-parent`
runs it (`spring-boot:process-aot`) only with its `aot` profile,
`-Paot`. This sample is the one that shows it, so it keeps the build environment's files (`application-build.yaml`,
data-books' `config-build.yaml`); the other samples don't have them. AOT processing evaluates the configuration once,
at build time, in the `build` environment, and puts generated code for the bean definitions in the jar.
`java -Dspring.aot.enabled=true -jar` runs that code instead of the configuration classes, which starts faster; a
native image always runs it (planned for a later phase). See `docs/modules/ROOT/pages/environments.adoc` (*AOT and
native images*) and `parent.adoc` (*Build environment*).

The jar from [Run it](#run-it) has no generated code, so AOT mode can't start it:

```bash
ENV=local java -Dspring.aot.enabled=true -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-2.0.0-SNAPSHOT.jar
```

```
Startup with AOT mode enabled failed: AOT initializer io.americanexpress.synapse.samples.bookstore.BookstoreApplication__ApplicationContextInitializer could not be found
```

Build it with the profile instead:

```bash
./mvnw -Paot -pl samples/reference-bookstore/service-bookstore -am package -DskipTests
```

AOT processing logs `No active profile set, falling back to 1 default profile: "build"`: the build environment reaches
it as the default profile, so it is never active when the jar runs.

The generated code stays in `target/` until `clean`, so a later build without `-Paot` would still put it in the jar.
The later `package` and `verify` commands without `-Paot` start with `clean` for that reason.

What the build decided can't change at run time. The build keeps the OpenAPI document and gRPC reflection off, as
every environment but `local` does, so the AOT-processed jar runs in `dev`, but not in `local`: startup fails, naming
every switch that differs from the build, in one failure:

```bash
ENV=local java -Dspring.aot.enabled=true -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-2.0.0-SNAPSHOT.jar
```

```
These properties are fixed at build time in a native image or AOT run, and differ from the build: spring.grpc.server.reflection.enabled is true but the build used false; springdoc.api-docs.enabled is true but the build used false.

Action:

Set each to the build's value for this run. To change one, set it in the build environment's file (application-<build env>.yaml) and in each deployed environment's file, then build again.
```

(The log line carries it as one JSON message, under `APPLICATION FAILED TO START`; the exit code is 1.) In `dev`,
pointed at the compose database through the variables a platform sets, it starts:

```bash
SYNAPSE_DATA_STORES_BOOKS_URL=jdbc:postgresql://localhost:5432/bookstore \
SYNAPSE_DATA_STORES_BOOKS_USERNAME=bookstore SYNAPSE_DATA_STORES_BOOKS_PASSWORD=bookstore \
ENV=dev java -Dspring.aot.enabled=true -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-2.0.0-SNAPSHOT.jar
```

```
"message":"Starting AOT-processed BookstoreApplication v2.0.0-SNAPSHOT using Java 25.0.5 ..."
"message":"The following 1 profile is active: \"dev\""
"message":"Tomcat started on port 8080 (http) with context path '/'"
"message":"gRPC Server started, listening on address: [/[0:0:0:0:0:0:0:0]:9090], port: 9090"
"message":"Started BookstoreApplication in 5.858 seconds (process running for 6.596)"
"message":"Synapse environment: dev (spring.profiles.active=${ENV}, application.yaml)\nConfiguration, highest priority first:\n  1. server.ports\n  2. Synapse baggage fields\n  3. Java system properties\n  4. environment variables\n  5. application.yaml  service-bookstore-2.0.0-SNAPSHOT.jar\n  6. config-dev.yaml   data-books-2.0.0-SNAPSHOT.jar\n  7. config.yaml       data-books-2.0.0-SNAPSHOT.jar\n  8. logCorrelation\n  9. applicationInfo\n  10. defaults\n  11. Management Server"
```

`dev` is the only active profile, and the build's files are not among the sources (`application-dev.yaml` is
comments only, so it isn't listed either). `/v3/api-docs` is a 404 `ROUTE_NOT_FOUND` here, as in every deployed
environment. `BookstoreAotIT` runs every scenario against this mode, in a build with `-Paot` (see [Tests](#tests)).

A native image of it is planned for a later phase (BUILD.md §15, P4).

## Container image

The image is built two ways, and both start the JVM with an AOT cache (JEP 483): a training run at build time starts
the service in the `build` environment, records the classes it loads, and exits once the context has refreshed, with
no database to reach. Neither needs Spring's AOT processing (`-Paot`). `docs/modules/ROOT/pages/parent.adoc`,
*Images*, has the details: what each way sets, Java 21 images (CDS), the garbage collector and the heap size in small
pods. Both pin the collector to G1, in the training run and in every run, so that a small pod, where the JVM would
pick the Serial collector, still uses the cache. You need Docker.

With Paketo (`spring-boot:build-image` and `synapse-parent`'s settings), through `synapse-parent`'s `image` profile.
It binds the goal in the module that declares `spring-boot-maven-plugin`, this one (the contract and data modules have
no main class), and first checks that the service has `application-build.yaml`, the file of the training run's
environment: without it the build stops in `validate`, before any image is built. (`spring-boot:build-image` on the
command line skips that check, and fails in the modules without a main class.)

```bash
./mvnw -Pimage -pl samples/reference-bookstore/service-bookstore -am clean package
```

The build runs the unit tests, then the buildpacks. These lines of its log are the training run and the cache:

```
[INFO]     [creator]       BellSoft Liberica JRE 25.0.5: Contributing to layer
[INFO]     [creator]         Training run will use this value as JAVA_TOOL_OPTIONS: -XX:+UseG1GC -Dspring.profiles.active=build
[INFO]     [creator]     ..."message":"The following 1 profile is active: \"build\""...
[INFO]     [creator]     AOTCache creation is complete: application.aot 139083776 bytes
[INFO] Successfully built image 'docker.io/library/service-bookstore:2.0.0-SNAPSHOT'
```

The training run also logs JVM warnings about classes it can't put in the cache (`[warning][aot] Skipping ...`, for
example the JDK's proxies); those classes are loaded as usual at run time.

With the [`Dockerfile`](service-bookstore/Dockerfile) instead, on Eclipse Temurin's 25.0.5+8 JRE, pinned by digest,
from the jar that `package` built:

```bash
./mvnw -pl samples/reference-bookstore/service-bookstore -am clean package -DskipTests
docker build --progress=plain -t bookstore:dockerfile samples/reference-bookstore/service-bookstore
```

These lines of its output are the training run and the cache:

```
#14 [stage-1 7/7] RUN java -XX:+UseG1GC -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.profiles.active=build -jar application.jar     && test -s app.aot
#14 18.60 AOTCache creation is complete: app.aot 139063296 bytes
#15 naming to docker.io/library/bookstore:dockerfile done
```

`scripts/image-smoke.sh` runs an image as a platform does: PostgreSQL and the image on one Docker network, the image
in `dev` with the store's connection in `SYNAPSE_DATA_STORES_BOOKS_URL`, `_USERNAME` and `_PASSWORD`. It waits for
`/health`, checks that `dev` is the one active profile (the training run's `build` is gone), creates a book, and
removes what it started:

```bash
samples/reference-bookstore/scripts/image-smoke.sh service-bookstore:2.0.0-SNAPSHOT
```

```
GET /health: {"status":"UP"}
Active profiles: dev only
POST /books: 201 {"id":"01a1bd3f-76ca-7396-98e4-86a06f04ba19","isbn":"978-0599185920","title":"The Dispossessed","author":"Ursula K. Le Guin","publishedYear":1974,"version":0}
image-smoke: service-bookstore:2.0.0-SNAPSHOT passed
```

`samples/reference-bookstore/scripts/image-smoke.sh bookstore:dockerfile` gives the same lines for the Dockerfile's
image. With a build log as its second argument, the script first prints the training lines, and fails without the
line of the cache the JVM wrote (or, for a Paketo build on Java 21, without Paketo's training run line);
with `SMOKE_JAVA_TOOL_OPTIONS=-XX:AOTMode=on`, the JVM stops instead of starting without its cache, and
`SMOKE_DOCKER_OPTIONS` adds `docker run` options, such as `--memory=512m --cpus=1 -e BPL_JVM_THREAD_COUNT=50` for a
small pod. The nightly build runs both images that way. When you are done, remove the images:
`docker rmi service-bookstore:2.0.0-SNAPSHOT bookstore:dockerfile`.

## OpenTelemetry Java agent

The service traces with Boot's OpenTelemetry SDK. To trace with the OpenTelemetry Java agent instead, start the jar
with `-javaagent:`; `config/OpenTelemetryAgentConfiguration` then hands Boot's bridge the agent's SDK, so Boot's
observations, Synapse's baggage and the agent's instrumentation share one SDK and one trace. Without the agent the
class does nothing. That holds on the JVM; in AOT mode (a `-Paot` jar run with `-Dspring.aot.enabled=true`) the
condition was fixed at build time, without the agent, so `config/OpenTelemetryAgentAotConfiguration` fails such a
start under the agent and says what to do. The recipe is in `docs/modules/ROOT/pages/opentelemetry-agent.adoc`.

The build copies the agent jar (2.33.1) to `target/otel` before the tests are compiled, and fails unless it has the
SHA-256 pinned in the pom (`opentelemetry-javaagent.sha256`), so `package` gets it too, even with `-DskipTests`. With the database of [Run it](#run-it) up, start the service under the agent, with its spans printed to the
console (`otel.traces.exporter=logging`) and the other exporters off:

```bash
./mvnw -pl samples/reference-bookstore/service-bookstore -am clean package -DskipTests
ENV=local java -javaagent:samples/reference-bookstore/service-bookstore/target/otel/opentelemetry-javaagent.jar \
  -Dotel.service.name=bookstore -Dotel.traces.exporter=logging -Dotel.metrics.exporter=none -Dotel.logs.exporter=none \
  -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-2.0.0-SNAPSHOT.jar
```

```
[otel.javaagent 2026-11-08 23:04:22:383 -0700] [main] INFO io.opentelemetry.javaagent.tooling.VersionLogger - opentelemetry-javaagent - version: 2.33.1
"message":"The following 1 profile is active: \"local\""
"message":"Started BookstoreApplication in 9.909 seconds (process running for 13.557)"
```

Delete a book that doesn't exist, as a caller in a trace of its own would:

```bash
curl -s -i -X DELETE -H 'If-Match: "0"' -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' http://localhost:8080/books/2b0c3c7e-1111-4222-8333-444455556666
```

```
HTTP/1.1 404 
X-Correlation-Id: 4459cbb4-0aa5-4ee3-b5ea-c3e6ead0e12c
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Mon, 09 Nov 2026 06:04:44 GMT

{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 2b0c3c7e-1111-4222-8333-444455556666 was not found","code":"BOOK_NOT_FOUND","correlationId":"4459cbb4-0aa5-4ee3-b5ea-c3e6ead0e12c"}
```

Synapse's failure line carries the caller's trace id twice: `traceId` from Boot's tracing, and `trace_id` from the
agent's Log4j instrumentation:

```
"message":"Operation delete-book failed with BOOK_NOT_FOUND (NOT_FOUND): Book 2b0c3c7e-1111-4222-8333-444455556666 was not found","correlationId":"4459cbb4-0aa5-4ee3-b5ea-c3e6ead0e12c","errorCategory":"NOT_FOUND","errorCode":"BOOK_NOT_FOUND","operation":"delete-book","spanId":"2c00011d05194408","span_id":"2c00011d05194408","traceId":"0af7651916cd43dd8448eb211c80319c","trace_flags":"01","trace_id":"0af7651916cd43dd8448eb211c80319c","ecs":{"version":"8.11"}
```

The console has the request's spans, all in that trace and all exported by the agent, Boot's (tracer
`org.springframework.boot`) among them, because the bridge uses the agent's SDK:

```
'bookstore' : 0af7651916cd43dd8448eb211c80319c 90ba821bfe10481e CLIENT [tracer: io.opentelemetry.jdbc:2.33.1-alpha]
'SELECT bookstore.book' : 0af7651916cd43dd8448eb211c80319c b408dc6564005350 CLIENT [tracer: io.opentelemetry.jdbc:2.33.1-alpha]
'Session.find io.americanexpress.synapse.samples.bookstore.data.books.StoredBook' : 0af7651916cd43dd8448eb211c80319c c4561c26b3d63cd4 INTERNAL [tracer: io.opentelemetry.hibernate-6.0:2.33.1-alpha]
'BookRepository.findById' : 0af7651916cd43dd8448eb211c80319c a3f883a6ab4aa116 INTERNAL [tracer: io.opentelemetry.spring-data-1.8:2.33.1-alpha]
'delete-book' : 0af7651916cd43dd8448eb211c80319c 2c00011d05194408 INTERNAL [tracer: org.springframework.boot:4.1.2]
'http delete /books/{id}' : 0af7651916cd43dd8448eb211c80319c a6f523ac8eeaa36c SERVER [tracer: org.springframework.boot:4.1.2]
'DELETE /books/{id}' : 0af7651916cd43dd8448eb211c80319c 743f339fae36b975 SERVER [tracer: io.opentelemetry.tomcat-10.0:2.33.1-alpha]
```

(Each line starts with `[otel.javaagent <time>] [tomcat-handler-<n>] INFO io.opentelemetry.exporter.logging.LoggingSpanExporter - `
and ends with the span's attributes, left out here.) The first span, named after the database, is the agent's JDBC
instrumentation with an empty `db.statement`. There are two server spans, the agent's and Boot's: the agent
page says how to turn one off, and why a public-facing service (`synapse.context.inbound.trusted=false`) turns off the
agent's. Stop the service with Ctrl+C. `BookstoreAgentIT` runs this recipe in every build, with every exporter off.

## Tests

| Test | What it checks | Kind |
|---|---|---|
| `data-books/.../BookRepositoryIT` | `@SynapseDataTest("books")` on a PostgreSQL container: the store migrated and validated, the ISBN unique, the version from 0, the author search ignoring case | integration |
| `operation/BookOperationsTest` | The operations with the real pipeline and a mocked repository (`@SynapseOperationTest`): mapping, versions, `STALE_VERSION` without a write, `BOOK_NOT_FOUND`, validation before the operation, the search's page and sort | unit |
| `api/rest/BookstoreRestTest` | The REST bindings on MockMvc with every operation mocked (`@SynapseRestTest`): 201 with `Location` and `ETag`, `If-Match` to the version, 304, 428, the sort keys, the query string; no store starts | unit |
| `api/grpc/BookstoreGrpcTest` | The gRPC bindings on the in-process transport with every operation mocked (`@SynapseGrpcTest`): the fields, the `etag`, `PRECONDITION_REQUIRED`, a stale tag (`ABORTED`) and a weak one (`FAILED_PRECONDITION`), `ALREADY_EXISTS`, the page fields | unit |
| `ArchitectureTest` | The layering rules, the data module included | unit |
| `BookstoreJvmIT` | `BookstoreScenarios` against the whole service on free ports and a PostgreSQL container: every scenario over REST and over gRPC with the same outcome, an `ETag` and a page token taken from one protocol to the other, a change between an update's read and its write (412 / `ABORTED`, with the code `PRECONDITION_FAILED` on both), and the generated-key WARN logged once for both plugins | integration |
| `BookstoreAotIT` | Only with `-Paot`. The same scenarios against the packaged jar in AOT mode, in `dev`, with the store's connection from environment variables: `dev` is the only active profile, and the generated-key WARN is logged once; a run in `local` fails naming both build-time switches; a run under the OpenTelemetry Java agent fails at startup with `OpenTelemetryAgentAotConfiguration`'s message | integration |
| `BookstoreExitOnRefreshIT` | The jar in `build` with `-Dspring.context.exit=onRefresh`, as an image's training run starts it, exits 0 with no database reachable | integration |
| `config/OpenTelemetryAgentConfigurationTest` | `OpenTelemetryAgentConfiguration` with Boot's SDK auto-configuration: without the agent's class, Boot's SDK is the `OpenTelemetry` bean; with it (a class loader that defines it), the bean is `GlobalOpenTelemetry.get()` and Boot's SDK backs off. The AOT check: in AOT mode with the agent's class and no bean, startup fails; with the bean, without the agent or on the JVM, it starts | unit |
| `BookstoreAgentIT` | The packaged jar under the OpenTelemetry Java agent (`target/otel`), in `dev`, exporters off: the agent's SDK is the bridge's (Boot's backs off), `/health` UP, a caller's `traceparent` is the `traceId` (and the agent's `trace_id`) of Synapse's failure line, and `X-Correlation-Id` its `correlationId` | integration |
| `BookstoreOperationalIT` | `/health` with `db/books` and `readinessState`, and 503 with the database stopped; CORS for the local front end and 403 `CORS_REJECTED` for another origin; the OpenAPI document off by default, and on: five operations, `If-Match` required on `PUT` and `DELETE`, 428 documented (saved to `target/api-docs-jvm.json`); `created_by` and `modified_by` are `system`; the startup report lists data-books' `config.yaml` with its jar | integration |

The scenarios are in `scenario/BookstoreScenarios`: each runs over both protocols, with `RestBookstoreClient` (the
JDK's HTTP client) and `GrpcBookstoreClient` (the generated stub), and compares one `Outcome` (kind, error code and
category, book, entity tag). Each client also checks its own protocol's statuses. A subclass says where the bookstore
runs; `scenario/AppProcess` starts the packaged jar as its own process, on free ports, waits for `/health`, and stops
it.

The integration tests need Docker, for the PostgreSQL containers (`SynapseContainers`); the AOT, agent and
exit-on-refresh tests start the jar that `package` built (the agent tests with the agent jar that the build copies), so they run in `verify`, never with `test`.

```bash
./mvnw -pl samples/reference-bookstore/service-bookstore -am clean verify
./mvnw -Paot -pl samples/reference-bookstore/service-bookstore -am verify
```

run them with everything the sample depends on (`*Test` in the `test` phase, `*IT` in `verify`). The first leaves out
`BookstoreAotIT`, which needs the AOT-generated code; the second runs AOT processing and every test, `BookstoreAotIT`
included. Pull requests run the first; a nightly job runs the second, then `BookstoreAotIT` again on JDK 21
(`-Djvm=<path to a JDK 21>/bin/java`). To run one unit test class, or one integration
test alone:

```bash
./mvnw -pl samples/reference-bookstore/service-bookstore -am test -Dtest=BookstoreGrpcTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -Paot -pl samples/reference-bookstore/service-bookstore -am verify -Dit.test=BookstoreAotIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

`-Djacoco.skip=true` turns off the coverage gate, which needs the tests of every module the build includes. The data
module's tests run on their own with `-pl samples/reference-bookstore/data-books -am verify`.

## Make it yours

Copy the directory and change these:

* The four poms: the `groupId`, `artifactId`s and `description`s; the parent's `relativePath` (drop it when the parent
  comes from a repository); and remove the four `maven.*.skip` properties in `pom.xml`, which only keep this sample
  from being published. Install and deploy the contract module, so that callers can depend on it, and the data module,
  if other services share its store.
* The packages `io.americanexpress.synapse.samples.bookstore...`, `BookstoreApplication`, and the `.proto`'s
  `package` and `java_package`.
* `spring.application.name` in `application.yaml`: it is also the `ErrorInfo` domain.
* data-books' `config-<env>.yaml`, one per environment you deploy to, with the `url` only; the credentials come from
  `SYNAPSE_DATA_STORES_<STORE>_USERNAME` and `_PASSWORD`.
* `application-<env>.yaml` for each deployed environment, never turning on a build-time switch that the build leaves
  off.
* The build environment's files, `application-build.yaml` and data-books' `config-build.yaml`, are needed only if you
  build with `-Paot` or build an image (its training run starts in `build`). Without either, delete them, with
  `BookstoreAotIT` and `BookstoreExitOnRefreshIT` (which start the jar in AOT mode and in `build`), and remove the
  `aot` profile from `service-bookstore/pom.xml`.
* Keep one way to build the image: `-Pimage` (Paketo, `synapse-parent`'s profile) or the `Dockerfile` with its
  `.dockerignore`.
  `scripts/image-smoke.sh` tests either.
* If you don't run the OpenTelemetry Java agent, delete `config/OpenTelemetryAgentConfiguration`,
  `config/OpenTelemetryAgentAotConfiguration` and their test, `BookstoreAgentIT` and `BookstoreAotIT`'s
  `aotRunUnderTheAgentFailsFast`, `AppProcess.agentJar()`, the `otel-agent` and `otel-agent-checksum` executions, the
  `otel-agent` profile, failsafe's `bookstore.otel-agent` property, and the three `opentelemetry-javaagent`/`otel-agent`
  properties in `service-bookstore/pom.xml`. Boot's own SDK then traces, as without the agent.

When you are done, stop the service with Ctrl+C, then the database (`down -v` also deletes its data):

```bash
docker compose -f samples/reference-bookstore/compose.yaml down
```

```
 Container reference-bookstore-books-db-1 Stopping 
 Container reference-bookstore-books-db-1 Stopped 
 Container reference-bookstore-books-db-1 Removing 
 Container reference-bookstore-books-db-1 Removed 
 Network reference-bookstore_default Removing 
 Network reference-bookstore_default Removed 
```
