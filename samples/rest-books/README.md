# Sample: a book catalog over REST

A small Synapse 2 service you can run and call: a catalog of books served over REST. It uses only what Synapse has
today (`synapse-core`, `synapse-api-rest`, `synapse-logging-log4j2` and the test modules). The data lives in memory,
starts with three books, and is lost when the service stops.

## What it shows

Code paths are under `src/main/java/io/americanexpress/synapse/samples/books/`.

| Feature | Where |
|---|---|
| Operations: `QueryOperation` for reads, `CommandOperation` for writes, named after their class (`GetBookOperation` is `get-book`) | `operation/` |
| Business rules in `BusinessOperation` beans that operations call (`AddBook`, `ReviseBook`, `RemoveBook`): one book per ISBN | `business/` |
| Error codes callers can match (`BOOK_NOT_FOUND`, `DUPLICATE_ISBN`) in an `ErrorDetails` enum | `contract/BookErrors.java` |
| Records with Jakarta Validation and JSpecify nullness; the pipeline validates each request before the operation runs | `model/` |
| One `RestBindings` bean for the whole API: path variables, query parameters, JSON body, a value from the request context, `201` + `Location`, `204` on `DELETE`, `.notFound(...)`, an output header, a per-route body limit | `api/rest/BooksRestConfiguration.java` |
| A stand-in data store (thread-safe, in memory) with its own model | `data/` |
| Problem responses (RFC 9457 `application/problem+json`) with `code`, `correlationId` and `violations`; `detail` only outside production | [Errors](#errors) |
| The correlation id: read from `X-Correlation-Id` or generated, echoed on every response, written to the logs of the request | [Correlation id](#correlation-id) |
| ECS JSON logs; a failing operation logs one line, a successful one logs nothing | [Logs](#what-to-look-for-in-the-logs) |
| Tests: the REST slice (`@SynapseRestTest`), the operation slice (`@SynapseOperationTest`), `SynapseOperations.invoke`, an end-to-end test on a real server, and the layering rules | `src/test/java` |

The packages follow Synapse's layering (specs/v2/BUILD.md section 4.3): `api.rest` calls `operation`; operations call
`business` (only through `BusinessOperation.execute`) and `data`; business code calls `data`; nobody calls `api`.
`ArchitectureTest` checks it with `SynapseArchitecture.forApplication(BooksApplication.class)`.

Two choices worth knowing when you read the code:

* The reads (`GetBookOperation`, `SearchBooksOperation`, `GetCatalogStatsOperation`) have no business rule, so they
  read the store directly, which the layering allows. The writes go through business code, where the ISBN rule lives.
* `BookErrors` is in `contract`, not `business`: the REST binding uses it (`.notFound(BookErrors.BOOK_NOT_FOUND)`), and
  the `api` layer may not depend on `business`. Every layer may use `contract`. In a multi-module service this enum
  would be in a `contract-books` module.

Every layer owns its model, and the caller maps: `model.Book` (what the API returns), `business.model.NewBook` and
`BookRevision` (what the business operations take), `business.model.CatalogBook` (what they return), and
`data.StoredBook` (what the store keeps). The business operations map `StoredBook` to `CatalogBook`; the operations
map `CatalogBook` to `Book`. The reads, which call the store directly, map `StoredBook` to `Book` themselves (the
operation to data edge is allowed).

## Run it

You need JDK 25 to build (Synapse's build requires it; the service itself runs on Java 21 or later): point
`JAVA_HOME` at a JDK 25 (`export JAVA_HOME=<path to a JDK 25>` and put `$JAVA_HOME/bin` first on `PATH`), so that
`java -version` reports 25. Then, from the repository root, in Git Bash on Windows or any POSIX shell:

```bash
./mvnw -pl samples/rest-books -am package -DskipTests
java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

The first command builds the sample and the Synapse modules it uses, without running the tests (`./mvnw verify` runs
them). The service listens on port 8080; stop it with Ctrl+C. At startup the REST plugin logs one line per route, for
example:

```text
{"@timestamp":"2026-11-04T12:52:26.155191500Z","log":{"level":"INFO","logger":"io.americanexpress.synapse.api.rest"},"process":{"pid":34056,"thread":{"name":"main"}},"service":{"name":"rest-books","node":{}},"message":"Synapse REST exposes GET /books/{id} -> get-book on port 8080","ecs":{"version":"8.11"}}
```

The `dev` profile makes problem responses carry `detail`. Without a profile the service counts as production and
leaves `detail` out (see [Production mode](#production-mode)).

## Endpoints

| Method | Path | Operation | Input | Answers |
|---|---|---|---|---|
| `GET` | `/books/{id}` | `get-book` | `id` from the path | 200; 404 `BOOK_NOT_FOUND` |
| `GET` | `/books?author=...` | `search-books` | `author` from the query string (optional; part of the name, any case) | 200 with a list, sorted by title |
| `GET`, `HEAD` | `/books/stats` | `get-catalog-stats` | none | 200 with `X-Books-Total` |
| `POST` | `/books` | `create-book` | JSON body; `channel` from the `X-Channel` header | 201 + `Location`; 400; 409 `DUPLICATE_ISBN`; 413 over 16 KB |
| `PUT` | `/books/{id}` | `update-book` | `id` from the path, the rest from the JSON body | 200; 400; 404 `BOOK_NOT_FOUND`; 409 `DUPLICATE_ISBN` |
| `DELETE` | `/books/{id}` | `delete-book` | `id` from the path | 204; 404 `BOOK_NOT_FOUND` |
| `GET` | `/actuator/health` | Spring Boot Actuator | | 200 |

Any other method on these paths is 405 with `Allow`, and `OPTIONS` lists the methods. The seeded books:

| id | Title | Author |
|---|---|---|
| `00000000-0000-0000-0000-000000000001` | Dune | Frank Herbert |
| `00000000-0000-0000-0000-000000000002` | The Left Hand of Darkness | Ursula K. Le Guin |
| `00000000-0000-0000-0000-000000000003` | A Wizard of Earthsea | Ursula K. Le Guin |

## Try it

Run these in a second terminal, in order, against a freshly started service (some change the catalog). The responses
below are real; ids that the service generates (the new book, correlation ids) and dates differ on each run.

### Health

```bash
curl -s http://localhost:8080/actuator/health
```

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

### Read a book

The path variable `{id}` fills `GetBookRequest.id`.

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```json
{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null}
```

### Search

On `GET`, simple components of the input record come from the query string of the same name.

```bash
curl -s "http://localhost:8080/books?author=guin"
```

```json
[{"id":"00000000-0000-0000-0000-000000000003","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","year":1968,"addedVia":null},{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null}]
```

### An output header

`.header("X-Books-Total", CatalogStats::books)` writes that component to a response header. `HEAD` answers the
headers of the `GET` without the body:

```bash
curl -s -I http://localhost:8080/books/stats
```

```text
HTTP/1.1 200 
X-Correlation-Id: 6d5d0d99-76f7-497b-9d3b-a019661be015
X-Books-Total: 3
Content-Type: application/json
Content-Length: 23
Date: Wed, 04 Nov 2026 12:52:28 GMT
```

### Add a book

The JSON body fills the request record. `CreateBookRequest.channel` comes from the request context instead, which
`application.yaml` fills from the `X-Channel` header (`synapse.api.rest.context.headers.channel`). The answer is 201
with the new book's `Location`.

```bash
curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'X-Channel: mobile' \
  -d '{"isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954}'
```

```text
HTTP/1.1 201 
X-Correlation-Id: 81fbb771-98f1-4c1d-836f-55b76cd9359d
Location: /books/ffdeaa6b-27a7-4b19-b285-95211ddf6a44
Content-Type: application/json
Content-Length: 166
Date: Wed, 04 Nov 2026 15:39:57 GMT

{"id":"ffdeaa6b-27a7-4b19-b285-95211ddf6a44","isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954,"addedVia":"mobile"}
```

### Update a book

The `id` comes from the path and the rest from the body. A body `"id"` that differs from the path is a 400.

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000002 \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019}'
```

```json
{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019,"addedVia":null}
```

### Delete a book

`DELETE` answers 204 with no body.

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003
```

```text
HTTP/1.1 204 
X-Correlation-Id: b1ead143-8c46-47a1-8029-6ebab0269365
Date: Wed, 04 Nov 2026 15:39:57 GMT
```

### Errors

Every error is `application/problem+json` with the `code` callers match and the `correlationId`.

Deleting the same book again: the business operation throws `SynapseException(BookErrors.BOOK_NOT_FOUND, id)`, and
the message template `Book {0} was not found` is filled in.

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000003 was not found","code":"BOOK_NOT_FOUND","correlationId":"8dc5472d-5483-43fa-a3b0-fa18464bab51"}
```

Reading a book that doesn't exist: the operation returns an empty `Optional`, and `.notFound(BookErrors.BOOK_NOT_FOUND)`
on the route answers it. The route's path values (here `{id}`) fill the template's placeholders, in template order.

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000099 was not found","code":"BOOK_NOT_FOUND","correlationId":"2eaa8b20-209d-4687-b9d4-4d7cb7470140"}
```

A validation error: the pipeline runs Jakarta Validation on the request record before the operation and reports
every violation.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"","title":" ","author":"Nobody","year":-1}'
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"8751d759-1243-48a1-9b6c-aad35f97b2fd","violations":[{"field":"isbn","message":"must not be blank"},{"field":"title","message":"must not be blank"},{"field":"year","message":"must be greater than 0"}]}
```

A business rule: the ISBN of Dune is taken.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert"}'
```

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"91bc779f-b4c8-4eb2-a91a-e25fded3a3dc"}
```

A method the path doesn't serve is 405 with `Allow`:

```bash
curl -s -i -X PATCH http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 405 
X-Correlation-Id: 74b711d7-536e-4c3c-8b0b-b273493f9adf
Allow: GET, HEAD, PUT, DELETE, OPTIONS
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 12:52:29 GMT

{"type":"about:blank","title":"Method Not Allowed","status":405,"detail":"The method is not allowed for this route","code":"METHOD_NOT_ALLOWED","correlationId":"74b711d7-536e-4c3c-8b0b-b273493f9adf"}
```

A body over the limit is 413 before the body is read. `POST /books` allows 16 KB (`.maxRequestSize(...)`); this sends
20,000 bytes. (`-H 'Expect:'` stops curl from waiting for a `100 Continue` first.)

```bash
head -c 20000 /dev/zero | tr '\0' x | curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'Expect:' --data-binary @-
```

```text
HTTP/1.1 413 
X-Correlation-Id: eecff691-9a90-4a14-b9f8-6ed96d0e28e5
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 12:52:29 GMT
Connection: close

{"type":"about:blank","title":"Content Too Large","status":413,"detail":"The request body is too large","code":"PAYLOAD_TOO_LARGE","correlationId":"eecff691-9a90-4a14-b9f8-6ed96d0e28e5"}
```

### Correlation id

Send your own id, and it comes back in the header, in the problem body and in the log line of the failure:

```bash
curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-123' -H 'X-Channel: web' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert"}'
```

```text
HTTP/1.1 409 
X-Correlation-Id: demo-123
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 12:52:29 GMT

{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"demo-123"}
```

Without the header, the service generates a UUID. An id that isn't 1 to 128 characters of `[A-Za-z0-9._:-]` is
replaced.

## What to look for in the logs

The console shows one JSON object per line, in the Elastic Common Schema (from `synapse-logging-log4j2`).

* Successful calls log nothing.
* A failing operation logs one WARN line on `io.americanexpress.synapse.operation` (ERROR with the stack trace for an
  internal error), with `operation`, `error.code`, `error.category`, `correlationId` and the trace and span ids, plus
  the context attribute `channel` when the request sent `X-Channel`. The correlation id example above logs:

  ```json
  {"@timestamp":"2026-11-04T12:52:29.648428900Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"},"process":{"pid":34056,"thread":{"name":"tomcat-handler-26"}},"service":{"name":"rest-books","node":{}},"message":"Operation create-book failed with DUPLICATE_ISBN (CONFLICT): A book with ISBN 978-0441172719 already exists","channel":"web","correlationId":"demo-123","error":{"category":"CONFLICT","code":"DUPLICATE_ISBN"},"operation":"create-book","spanId":"924c1f21b7c062a1","traceId":"7b49197074f5ed4e316a161e083eab6f","ecs":{"version":"8.11"}}
  ```

* Requests turned away before an operation runs (unknown book on `GET`, 405, 413, a request that doesn't bind) are not
  logged.

## Production mode

Stop the service and start it without the profile:

```bash
java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
```

Problem responses now leave out `detail`:

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"e28f2ff8-3118-47da-a8d2-a64bcb6dfdfb"}
```

Deleting a book that doesn't exist fails in the business operation:

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"fc125601-06cf-441f-acc1-477956242a45"}
```

The log line of the failure still carries the message (`Book 00000000-0000-0000-0000-000000000099 was not found`), so
the `correlationId` a caller reports leads to it.

## Configuration

`src/main/resources/application.yaml` lists the settings worth changing while you try the service, with comments: the
correlation header, error detail, the context header mapping, the deadline (`Request-Timeout`), the body limit and the
concurrency limit. Boot's OTLP metrics export is turned off there, because no OpenTelemetry collector runs with the
sample.

## Tests

`./mvnw -pl samples/rest-books -am verify` builds the modules and runs every test:

| Test | Shows |
|---|---|
| `api/rest/BooksRestTest` | `@SynapseRestTest`: the bindings, edge filters and problem responses on MockMvc, with `@MockitoBean` operations |
| `operation/BookOperationsTest` | `@SynapseOperationTest`: operations and business beans with the real pipeline (validation, error codes) |
| `business/ReviseBookTest` | `SynapseOperations.invoke`: a business operation through its pipeline, without Spring |
| `data/InMemoryBookStoreTest` | The store, as a plain unit test |
| `BooksApplicationIT` | The whole service on a real port, without a profile (production): tagged `smoke` |
| `ArchitectureTest` | The layering rules |

## Not here yet

Synapse's next REST features will extend this sample: paging (`PageQuery`, sort allow-lists, signed cursors),
conditional requests (`ETag`, `If-Match`, 304 and 412), API versioning, CORS and OpenAPI (`/v3/api-docs`). The
`RestConventions` bean (app-wide binding rules by type) isn't used: this API has no component type shared by several
routes.

This module inherits the repository's build parent so it passes the same gates as the library. Your own service
inherits `io.americanexpress.synapse:synapse-parent` and declares the same dependencies as this `pom.xml`.
