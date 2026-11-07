# Sample: a book catalog over REST

A small Synapse 2 service you can run and call: a catalog of books served over REST, and over gRPC with the same
operations (see [gRPC](#grpc)). It uses only what Synapse has today (`synapse-core`, `synapse-api-rest`,
`synapse-api-rest-openapi`, `synapse-api-grpc`, `synapse-logging-log4j2` and the test modules). The data lives in
memory, starts with three books, and is lost when the service stops.

## What it shows

Code paths are under `src/main/java/io/americanexpress/synapse/samples/books/`.

| Feature | Where |
|---|---|
| Operations: `QueryOperation` for reads, `CommandOperation` for writes, named after their class (`GetBookOperation` is `get-book`) | `operation/` |
| Business rules in `BusinessOperation` beans that operations call (`AddBook`, `ReviseBook`, `RemoveBook`): one book per ISBN, and no lost updates | `business/` |
| Error codes callers can match (`BOOK_NOT_FOUND`, `DUPLICATE_ISBN`) in an `ErrorDetails` enum; `STALE_VERSION` comes from core's `SynapseErrors` | `contract/BookErrors.java` |
| Records with Jakarta Validation and JSpecify nullness; the pipeline validates each request before the operation runs | `model/` |
| One `RestBindings` bean for the whole API: path variables, query parameters, JSON body, a value from the request context, `201` + `Location`, `204` on `DELETE`, `.notFound(...)`, an output header, a per-route body limit, paging, ETags and `If-Match` | `api/rest/BooksRestConfiguration.java` |
| Paging: a `PageQuery` in the input, a `.sortable(...)` allow-list, a `PageResult` output written as a page object with `Link: rel="next"`, and cursors the edge encrypts | `model/SearchBooksRequest.java`, `operation/SearchBooksOperation.java`, [Pages and sorting](#pages-and-sorting) |
| Book versions and conditional requests: `.etag(...)` (`ETag`, 304), `If-Match` bound to a `VersionCondition` component (412), a version the delete's input requires (a non-null `VersionCondition`, 428 without it), and `STALE_VERSION` from a compare-and-set in the store (409 without `If-Match`) | `data/InMemoryBookStore.java`, `business/ReviseBook.java`, `business/RemoveBook.java`, `model/DeleteBookRequest.java`, `operation/BookMapping.java`, [Conditional requests](#conditional-requests) |
| API versions: `.version("2")` serves `GET /v2/books/{id}`, which returns `BookV2` (the authors as a list), beside version 1 at `/books/{id}` | `api/rest/BooksRestConfiguration.java`, `model/BookV2.java`, `operation/GetBookV2Operation.java`, [Versions](#versions) |
| CORS for a front end on `http://localhost:3000`, turned on in the local environment only | `src/main/resources/application-local.yaml`, [CORS](#cors) |
| The OpenAPI 3.1 document of every route, at `/v3/api-docs` (`synapse-api-rest-openapi`) | `pom.xml`, [OpenAPI](#openapi) |
| A stand-in data store (thread-safe, in memory) with its own model | `data/` |
| Problem responses (RFC 9457 `application/problem+json`) with `code`, `correlationId` and `violations`; `detail` only when `include-detail` is on (local) | [Errors](#errors) |
| The correlation id: read from `X-Correlation-Id` or generated, echoed on every response, written to the logs of the request | [Correlation id](#correlation-id) |
| ECS JSON logs; a failing operation logs one line, a successful one logs nothing | [Logs](#what-to-look-for-in-the-logs) |
| The same operations over gRPC: a `.proto` service compiled by the build, and one `GrpcBindings` bean with `.unary(...)`, `.notFound(...)`, a value from the request context (`x-channel` metadata) and `.bindAllByName()` for the rest; no change to the operations or models | `src/main/proto/books/v1/books.proto`, `api/grpc/BooksGrpcConfiguration.java`, [gRPC](#grpc) |
| gRPC errors: the status code from the error category (`NOT_FOUND`, `ABORTED`, `INVALID_ARGUMENT`), and `google.rpc.ErrorInfo` with the code as `reason` and the `correlationId`; `BadRequest` for violations | [gRPC errors](#grpc-errors) |
| Tests: the REST slice (`@SynapseRestTest`), the gRPC slice (`@SynapseGrpcTest`), the operation slice (`@SynapseOperationTest`), `SynapseOperations.invoke`, an end-to-end test on a real server, and the layering rules | `src/test/java` |

The packages follow Synapse's layering (specs/v2/BUILD.md section 4.3): `api.rest` and `api.grpc` call `operation`;
operations call `business` (only through `BusinessOperation.execute`) and `data`; business code calls `data`; nobody
calls `api`. `ArchitectureTest` checks it with `SynapseArchitecture.forApplication(BooksApplication.class)`.

Two choices worth knowing when you read the code:

* The reads (`GetBookOperation`, `SearchBooksOperation`, `GetCatalogStatsOperation`) have no business rule, so they
  read the store directly, which the layering allows. The writes go through business code, where the ISBN and version
  rules live.
* `BookErrors` is in `contract`, not `business`: the REST binding uses it (`.notFound(BookErrors.BOOK_NOT_FOUND)`), and
  the `api` layer may not depend on `business`. Every layer may use `contract`. In a multi-module service this enum
  would be in a `contract-books` module.

The `RestConventions` bean (app-wide binding rules by type) isn't used: this API has no component type shared by
several routes.

Every layer owns its model, and the caller maps: `model.Book` and `model.BookV2` (what versions 1 and 2 of the API
return), `business.model.NewBook`, `BookRevision` and `BookRemoval` (what the business operations take),
`business.model.CatalogBook` (what they return), and `data.StoredBook` (what the store keeps). The business operations
map `StoredBook` to `CatalogBook`; the operations map `CatalogBook` to `Book`. The reads, which call the store
directly, map `StoredBook` to `Book` or `BookV2` themselves (the operation to data edge is allowed).

Every book has a `version`: 1 when it is added, plus 1 on every change. The store writes only if the book is still at
the version the writer read (a compare-and-set, as `UPDATE ... WHERE version = ?` would do in a database), so two
concurrent updates can't both win. Over REST the version is the book's `ETag`, and a client sends it back in
`If-Match`.

## Run it

You need JDK 25 to build (Synapse's build requires it; the service itself runs on Java 21 or later): point
`JAVA_HOME` at a JDK 25 (`export JAVA_HOME=<path to a JDK 25>` and put `$JAVA_HOME/bin` first on `PATH`), so that
`java -version` reports 25. Then, from the repository root, in Git Bash on Windows or any POSIX shell:

```bash
./mvnw -pl samples/rest-books -am package -DskipTests
ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
```

The first command builds the sample and the Synapse modules it uses, without running the tests (`./mvnw verify` runs
them). The second runs the `local` environment: `application.yaml` maps `spring.profiles.active: ${ENV}`, and
`application-local.yaml` turns on `detail` in problem responses (`synapse.errors.include-detail: true`).
`spring-boot:run` runs this module as `local` too: the pom sets `spring-boot.run.profiles` to `local`, and
`-Dspring-boot.run.profiles=<env>` picks another. It runs the sample alone, so it takes the Synapse modules from your
local Maven repository: install them first (again after you change them):

```bash
./mvnw -pl samples/rest-books -am install -DskipTests
./mvnw -pl samples/rest-books spring-boot:run
```

`-DskipTests` only saves time on the install step; it is not a test run.

The service listens on port 8080, and on 9090 for gRPC ([gRPC](#grpc)); stop it with Ctrl+C. At startup it logs
which environment it runs and where its configuration comes from (see
[What to look for in the logs](#what-to-look-for-in-the-logs)), and the REST plugin logs one line per route, for
example:

```text
{"@timestamp":"2026-11-06T21:11:15.478127700Z","log":{"level":"INFO","logger":"io.americanexpress.synapse.api.rest"},"process":{"pid":34980,"thread":{"name":"main"}},"service":{"name":"rest-books","node":{}},"message":"Synapse REST exposes GET /books/{id} -> get-book on port 8080","ecs":{"version":"8.11"}}
```

It also logs a WARN that `synapse.api.rest.paging.cursor.key` is not set (see [Safe defaults](#safe-defaults)).

The service needs an environment. Started without `ENV`, it stops before anything else runs:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

No environment is set: spring.profiles.active is '${ENV}', and ENV has no value.

Action:

Set ENV to the name of the environment (the service has an application-<env>.yaml for each). Run locally with -Dspring-boot.run.profiles=local or an IDE run configuration; Synapse's test slices activate test; other Spring Boot tests declare @ActiveProfiles("test").
```

An environment without its own file (here `ENV=e9`; the sample ships only `application-local.yaml`) stops it too:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

The environment 'e9' has no file in the service: none of application-e9.yaml, application-e9.yml, application-e9.properties was loaded or found on the classpath. Environment names are case-sensitive.

Action:

Add application-e9.yaml to the service (it may be empty), or set the environment to the name of an existing file.
```

## Endpoints

| Method | Path | Operation | Input | Answers |
|---|---|---|---|---|
| `GET`, `HEAD` | `/books/{id}` | `get-book` | `id` from the path | 200 with `ETag`, `Cache-Control: no-cache` and `Vary: Accept`; 304 when `If-None-Match` matches; 404 `BOOK_NOT_FOUND` |
| `GET`, `HEAD` | `/v2/books/{id}` | `get-book-v2` | `id` from the path | Version 2: 200 with a `BookV2` (`authors` as a list, no `addedVia`) and the same `ETag` headers; 304; 404 `BOOK_NOT_FOUND` |
| `GET` | `/books` | `search-books` | `author` from the query string (optional; part of the name, any case); `size` (default 20, at most 100); `cursor` or `offset` (at most 1000); `sort` (`title`, `year`, `-` for descending; by title when absent) | 200 with a page `{items, nextCursor, hasNext, total}` and `Link: rel="next"` when there is a next page; 400 |
| `GET`, `HEAD` | `/books/stats` | `get-catalog-stats` | none | 200 with `X-Books-Total` |
| `POST` | `/books` | `create-book` | JSON body; `channel` from the `X-Channel` header | 201 + `Location` + `ETag`; 400; 409 `DUPLICATE_ISBN`; 413 over 16 KB |
| `PUT` | `/books/{id}` | `update-book` | `id` from the path, `version` from `If-Match` (optional), the rest from the JSON body | 200 with the new version (no `ETag`); 400; 404 `BOOK_NOT_FOUND`; 409 `DUPLICATE_ISBN`, or `STALE_VERSION` without `If-Match`; 412 `PRECONDITION_FAILED` |
| `DELETE` | `/books/{id}` | `delete-book` | `id` from the path, `version` from `If-Match` (required, because `DeleteBookRequest.version` isn't `@Nullable`; `*` for any version) | 204; 404 `BOOK_NOT_FOUND`; 412 `PRECONDITION_FAILED`; 428 `PRECONDITION_REQUIRED` |
| `GET` | `/actuator/health` | Spring Boot Actuator | | 200 |
| `GET` | `/v3/api-docs` | springdoc, with Synapse's routes (on in `local` only) | | 200 with the OpenAPI 3.1 document; 404 `ROUTE_NOT_FOUND` where it is off |

Any other method on the `/books` and `/v2/books` paths is 405 with `Allow`, and `OPTIONS` lists the methods. A version
no route declares, such as `/v3/books/{id}`, is 404 `ROUTE_NOT_FOUND`. The seeded books, all at version 1:

| id | Title | Author |
|---|---|---|
| `00000000-0000-0000-0000-000000000001` | Dune | Frank Herbert |
| `00000000-0000-0000-0000-000000000002` | The Left Hand of Darkness | Ursula K. Le Guin |
| `00000000-0000-0000-0000-000000000003` | A Wizard of Earthsea | Ursula K. Le Guin |

## Try it

Run these in a second terminal, in order, against a freshly started service (some change the catalog). The responses
below are real. Values the service generates differ on each run: the new books' ids, correlation ids, dates and paging
tokens (`nextCursor` and the `cursor` in `Link`, which are also new on every response). `application-local.yaml`
turns [CORS](#cors) on, so every response also carries `Vary: Origin`, `Vary: Access-Control-Request-Method` and
`Vary: Access-Control-Request-Headers` (Spring adds them whenever a CORS policy applies to the path, so that a cache
keeps the answers to different origins apart).

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
{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}
```

### Search

On `GET`, simple components of the input record come from the query string of the same name. The output is a
`PageResult`, so the answer is a page object: `items`, `hasNext`, `total`, and `nextCursor` when there is a next page.

```bash
curl -s "http://localhost:8080/books?author=guin"
```

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000003","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","year":1968,"addedVia":null,"version":1},{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null,"version":1}],"hasNext":false,"total":2}
```

### Pages and sorting

`SearchBooksRequest.page` is a `PageQuery`, which the plugin fills from the `size`, `cursor`, `offset` and `sort`
query parameters. `.sortable(SearchBooksRequest.SortKey.class)` allows `sort=title` and `sort=year` (`-year` for
descending, `year,title` for both). The operation pages through the books and returns its own cursor, the index of the
next book as a string; the plugin encrypts it (AES-256-GCM) before the client sees it, and adds a relative
`Link: rel="next"` header on `GET` and `HEAD`.

```bash
curl -s -i "http://localhost:8080/books?sort=title&size=2"
```

```text
HTTP/1.1 200 
X-Correlation-Id: 7cb6bce0-8743-4043-92f9-8ec01242d02b
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Link: </books?sort=title&size=2&cursor=AQTT4UQ6HZW_T7CzLvd85xaz-greOE6Pj-fsJR5GG0skYNfDtGrCA_nj>; rel="next"
Content-Type: application/json
Content-Length: 428
Date: Fri, 06 Nov 2026 21:11:22 GMT

{"items":[{"id":"00000000-0000-0000-0000-000000000003","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","year":1968,"addedVia":null,"version":1},{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}],"nextCursor":"AQTT4UQ6HZW_T7CzLvd85xaz-greOE6Pj-fsJR5GG0skYNfDtGrCA_nj","hasNext":true,"total":3}
```

Follow the token, either from `nextCursor` or from the `Link` target. The next page is the last one: no `nextCursor`,
`hasNext` is false, and no `Link`.

```bash
NEXT=$(curl -s "http://localhost:8080/books?sort=title&size=2" | sed -E 's/.*"nextCursor":"([^"]+)".*/\1/')
curl -s "http://localhost:8080/books?sort=title&size=2&cursor=$NEXT"
```

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null,"version":1}],"hasNext":false,"total":3}
```

A page can also start at an offset, up to `synapse.api.rest.paging.max-offset` (1000); beyond that, callers page by
cursor.

```bash
curl -s "http://localhost:8080/books?offset=2&size=2"
```

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null,"version":1}],"hasNext":false,"total":3}
```

The plugin checks the paging parameters before the operation runs. A sort key that isn't allowed:

```bash
curl -s "http://localhost:8080/books?sort=price"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"a1c8990e-f3de-427a-9719-5b1c31a65c95","violations":[{"field":"sort","message":"has a key that is not allowed (allowed: title, year)"}]}
```

A changed token (here its first character):

```bash
curl -s "http://localhost:8080/books?sort=title&size=2&cursor=$(echo "$NEXT" | sed 's/^A/B/')"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"720768dd-9fb7-4c4a-8cd8-bec66b80f0e2","violations":[{"field":"cursor","message":"is not valid for this request"}]}
```

A token is bound to the operation, the sort and the other inputs (such as `author`), so the same token with another
sort is rejected the same way. Every reason gets the same message.

```bash
curl -s "http://localhost:8080/books?sort=-year&size=2&cursor=$NEXT"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"fc0206f1-6972-4775-b3a8-3900f34495ba","violations":[{"field":"cursor","message":"is not valid for this request"}]}
```

An offset over the cap:

```bash
curl -s "http://localhost:8080/books?offset=1001"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"a07c71b2-9972-41a4-a2ff-364dceccc0f9","violations":[{"field":"offset","message":"must be less than or equal to 1000"}]}
```

### An output header

`.header("X-Books-Total", CatalogStats::books)` writes that component to a response header. `HEAD` answers the
headers of the `GET` without the body:

```bash
curl -s -I http://localhost:8080/books/stats
```

```text
HTTP/1.1 200 
X-Correlation-Id: 8499765e-9569-4190-9439-af2da032db26
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
X-Books-Total: 3
Content-Type: application/json
Content-Length: 23
Date: Fri, 06 Nov 2026 21:11:22 GMT
```

### Add a book

The JSON body fills the request record. `CreateBookRequest.channel` comes from the request context instead, which
`application.yaml` fills from the `X-Channel` header (`synapse.api.rest.context.headers.channel`). The answer is 201
with the new book's `Location`, and its `ETag` (`.etag(Book::version)`): the new book is at version 1.

```bash
curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'X-Channel: mobile' \
  -d '{"isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954}'
```

```text
HTTP/1.1 201 
X-Correlation-Id: 1da929c7-ae83-4178-9d13-3f6819977e5c
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Location: /books/13fcb598-30a6-4093-a9d4-841b8219cd2f
ETag: "1"
Content-Type: application/json
Content-Length: 178
Date: Fri, 06 Nov 2026 21:11:22 GMT

{"id":"13fcb598-30a6-4093-a9d4-841b8219cd2f","isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954,"addedVia":"mobile","version":1}
```

### Update a book

The `id` comes from the path and the rest from the body. A body `"id"` that differs from the path is a 400. Without
`If-Match`, the update applies to the version the business operation reads, and the version goes up by 1 (see
[Conditional requests](#conditional-requests) for `If-Match`).

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000002 \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019}'
```

```json
{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019,"addedVia":null,"version":2}
```

### Delete a book

`DELETE` requires `If-Match` with the version to delete, and answers 204 with no body. The requirement is part of
the operation's input, not of the REST route: `DeleteBookRequest.version` is a `VersionCondition<Long>` without
`@Nullable` (see [Conditional requests](#conditional-requests)).

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003 -H 'If-Match: "1"'
```

```text
HTTP/1.1 204 
X-Correlation-Id: c2e5c347-6a16-4916-bcad-3897ac8669b1
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Date: Sat, 07 Nov 2026 07:45:52 GMT
```

### Errors

Every error is `application/problem+json` with the `code` callers match and the `correlationId`.

Deleting the same book again, with `If-Match: *` (any version): the business operation throws
`SynapseException(BookErrors.BOOK_NOT_FOUND, id)`, and the message template `Book {0} was not found` is filled in.

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003 -H 'If-Match: *'
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000003 was not found","code":"BOOK_NOT_FOUND","correlationId":"20b13978-9d0c-4b27-b33e-023e0321f8b8"}
```

Reading a book that doesn't exist: the operation returns an empty `Optional`, and `.notFound(BookErrors.BOOK_NOT_FOUND)`
on the route answers it. The route's path values (here `{id}`) fill the template's placeholders, in template order.

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000099 was not found","code":"BOOK_NOT_FOUND","correlationId":"bd0fa663-dfb1-47ce-afdb-c9dea8218198"}
```

A validation error: the pipeline runs Jakarta Validation on the request record before the operation and reports
every violation.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"","title":" ","author":"Nobody","year":-1}'
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"a1f3694f-0dc6-4850-950f-ccee6bc46941","violations":[{"field":"isbn","message":"must not be blank"},{"field":"title","message":"must not be blank"},{"field":"year","message":"must be greater than 0"}]}
```

A business rule: the ISBN of Dune is taken.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert"}'
```

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"5f05b9fc-8948-438e-9c36-ef43d751f8fc"}
```

A method the path doesn't serve is 405 with `Allow`:

```bash
curl -s -i -X PATCH http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 405 
X-Correlation-Id: 001fbc89-8efd-4962-9077-5cdeb98aff42
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Allow: GET, HEAD, PUT, DELETE, OPTIONS
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Fri, 06 Nov 2026 21:11:23 GMT

{"type":"about:blank","title":"Method Not Allowed","status":405,"detail":"The method is not allowed for this route","code":"METHOD_NOT_ALLOWED","correlationId":"001fbc89-8efd-4962-9077-5cdeb98aff42"}
```

A body over the limit is 413 before the body is read. `POST /books` allows 16 KB (`.maxRequestSize(...)`); this sends
20,000 bytes. (`-H 'Expect:'` stops curl from waiting for a `100 Continue` first.)

```bash
head -c 20000 /dev/zero | tr '\0' x | curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'Expect:' --data-binary @-
```

```text
HTTP/1.1 413 
X-Correlation-Id: fd4329d0-028b-401a-85b4-cb4fc41a274b
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Fri, 06 Nov 2026 21:11:23 GMT
Connection: close

{"type":"about:blank","title":"Content Too Large","status":413,"detail":"The request body is too large","code":"PAYLOAD_TOO_LARGE","correlationId":"fd4329d0-028b-401a-85b4-cb4fc41a274b"}
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
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Fri, 06 Nov 2026 21:11:23 GMT

{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"demo-123"}
```

Without the header, the service generates a UUID. An id that isn't 1 to 128 characters of `[A-Za-z0-9._:-]` is
replaced.

### Conditional requests

`GET /books/{id}` sends the book's version as a strong `ETag` (`.etag(Book::version)`), with `Cache-Control: no-cache`
(a cache must check with the service before reusing it) and `Vary: Accept`:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 200 
X-Correlation-Id: 3f1e072c-1f26-4016-b266-4f5b95652a13
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Cache-Control: no-cache
Vary: Accept
ETag: "1"
Content-Type: application/json
Content-Length: 149
Date: Fri, 06 Nov 2026 21:11:23 GMT

{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}
```

A client that has version 1 sends it in `If-None-Match`, and gets 304 without a body while the book is unchanged:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-None-Match: "1"'
```

```text
HTTP/1.1 304 
X-Correlation-Id: 3d9db637-7b57-4f89-9360-11f3fb1f055f
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
ETag: "1"
Cache-Control: no-cache
Vary: Accept
Date: Fri, 06 Nov 2026 21:11:23 GMT
```

`HEAD` answers the same way:

```bash
curl -s -I http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-None-Match: "1"'
```

```text
HTTP/1.1 304 
X-Correlation-Id: 28b3bd06-55cf-4b5b-9115-445b83329219
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
ETag: "1"
Cache-Control: no-cache
Vary: Accept
Date: Fri, 06 Nov 2026 21:11:23 GMT
```

On `PUT`, `.header("If-Match", UpdateBookRequest::version)` binds the tag to the `version` component, a
`@Nullable VersionCondition<Long>`, so `If-Match` is optional: `"1"` is `Version(1)`, `*` is `AnyVersion`, and no
`If-Match` is `null`.
`UpdateBookOperation` maps it with a `switch` to the business model's `@Nullable Long version` (`Version(v)` to `v`, the
other two to `null`), and the update succeeds only on that version. The answer carries the new version in the body,
and no `ETag` (RFC 9110 section 9.3.4):

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":2}
```

The same request again is based on a version that is no longer current. `ReviseBook` throws
`SynapseErrors.STALE_VERSION`, and because the request carried `If-Match` the plugin answers 412
`PRECONDITION_FAILED`; the book is unchanged:

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"17fb75c7-19a7-484a-97e9-1f7d7d45530b"}
```

A weak tag can never match a write's precondition, so the plugin answers 412 before the operation runs:

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: W/"2"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"b13f0705-de61-4000-afca-f5a96e4eb0a8"}
```

`If-Match` is optional on `PUT`. Without it, `ReviseBook` checks the version it has just read, so a concurrent change
that wins between that read and the store's compare-and-set still fails: the store returns `STALE`, and the answer is
409 `STALE_VERSION` (412 if the request had carried `If-Match`). Two curl calls can't reliably hit that window;
`BooksApplicationIT.concurrentUpdateWithoutIfMatchIs409` produces it with a spy on the store whose `find` returns the
book at an older version.

`If-Match: *` binds `AnyVersion`, which the operation also maps to `null`: the update applies to the version
`ReviseBook` reads, as without the header, and only that race is answered differently (412 instead of 409). The
`VersionCondition` keeps `*` and no header apart for an operation whose answer differs, such as a create-only `PUT`
with `If-None-Match: *`.

`DELETE` requires `If-Match`, bound to `DeleteBookRequest.version` as on `PUT`. Here the component is a
`VersionCondition<Long>` without `@Nullable`, and that is the whole declaration: a version is part of the delete's
contract, the same over every protocol, so the route needs no REST-only setting. `"2"` is `Version(2)`, and `*` is
`AnyVersion`, which counts as a version: `DeleteBookOperation` passes the condition on to `BookRemoval`, and
`RemoveBook` deletes the version it has just read for `AnyVersion` (the [Errors](#errors) example uses it). The
operation never sees a delete without a version. Without the header the plugin answers 428 `PRECONDITION_REQUIRED`
before the operation runs, and the violation says how to retry, with `detail` off too:

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 428 
X-Correlation-Id: 0555413c-1549-44af-951d-6fd8096d7935
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 07:45:52 GMT

{"type":"about:blank","title":"Precondition Required","status":428,"detail":"The request must be conditional","code":"PRECONDITION_REQUIRED","correlationId":"0555413c-1549-44af-951d-6fd8096d7935","violations":[{"field":"If-Match","message":"is required; send the ETag from a GET of this resource"}]}
```

With the current version (2, after the update above), the book is deleted:

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-Match: "2"'
```

```text
HTTP/1.1 204 
X-Correlation-Id: 291b03f7-26b8-44c9-b233-7d8bafee9c05
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Date: Sat, 07 Nov 2026 07:45:52 GMT
```

### Versions

`.get("/books/{id}", getBookV2).version("2")` serves version 2 of the read at `/v2/books/{id}`: `.version("2")` adds the
`/v2` segment, and the declared path stays `/books/{id}`. Version 2 has its own operation (`GetBookV2Operation`,
`get-book-v2`) and its own output, `BookV2`: `authors` is a list, split from the stored author on `", "`, and
`addedVia` is gone. `.notFound(...)` and `.etag(...)` are declared on that route too. Add a book with two authors and
read it in version 2:

```bash
ID=$(curl -s -X POST http://localhost:8080/books -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0060853983","title":"Good Omens","author":"Terry Pratchett, Neil Gaiman","year":1990}' \
  | sed -E 's/.*"id":"([^"]+)".*/\1/')
curl -s -i http://localhost:8080/v2/books/$ID
```

```text
HTTP/1.1 200 
X-Correlation-Id: 48338570-bedf-4d3f-a84a-db12ae6ce561
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Cache-Control: no-cache
Vary: Accept
ETag: "1"
Content-Type: application/json
Content-Length: 158
Date: Fri, 06 Nov 2026 21:11:24 GMT

{"id":"bcfbf3d7-2f13-4387-a211-f0cb548952dd","isbn":"978-0060853983","title":"Good Omens","authors":["Terry Pratchett","Neil Gaiman"],"year":1990,"version":1}
```

`/books/{id}` keeps serving version 1 unchanged, one author string:

```bash
curl -s http://localhost:8080/books/$ID
```

```json
{"id":"bcfbf3d7-2f13-4387-a211-f0cb548952dd","isbn":"978-0060853983","title":"Good Omens","author":"Terry Pratchett, Neil Gaiman","year":1990,"addedVia":null,"version":1}
```

A version that no route declares is no route at all:

```bash
curl -s http://localhost:8080/v3/books/$ID
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"No route matches the request","code":"ROUTE_NOT_FOUND","correlationId":"5a0685ad-866e-44af-89e3-8a93cfef4e6e"}
```

### CORS

`application-local.yaml` allows one origin, `http://localhost:3000` (`synapse.api.rest.cors.allowed-origins`), so a
front end served there during development can call the API from the browser. Only that file turns CORS on: without
origins, the default in every environment, CORS is off, and in the tests (environment `test`) the service sends no
CORS headers.
Actuator's paths are left to Boot (`management.endpoints.web.cors.*`).

Before a `PUT` with `If-Match`, the browser sends a pre-flight. The edge answers it, without running an operation:

```bash
curl -s -i -X OPTIONS http://localhost:8080/books/00000000-0000-0000-0000-000000000002 \
  -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: PUT' \
  -H 'Access-Control-Request-Headers: Content-Type, If-Match'
```

```text
HTTP/1.1 200 
X-Correlation-Id: efda7bf7-7c93-4d0e-af97-d4117d598750
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET,HEAD,POST,PUT,DELETE
Access-Control-Allow-Headers: Content-Type, If-Match
Access-Control-Expose-Headers: X-Correlation-Id, ETag, Link, Location, Retry-After, Deprecation, Sunset
Access-Control-Max-Age: 1800
Content-Length: 0
Date: Fri, 06 Nov 2026 21:11:24 GMT
```

The actual request gets `Access-Control-Allow-Origin`, and `Access-Control-Expose-Headers` lets the page read the
headers Synapse writes: the correlation id, `ETag`, `Link`, `Location`, `Retry-After`, `Deprecation` and `Sunset`:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000002 -H 'Origin: http://localhost:3000'
```

```text
HTTP/1.1 200 
X-Correlation-Id: abfac255-db40-4197-8edb-308ea47ff946
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Expose-Headers: X-Correlation-Id, ETag, Link, Location, Retry-After, Deprecation, Sunset
Cache-Control: no-cache
Vary: Accept
ETag: "2"
Content-Type: application/json
Content-Length: 201
Date: Fri, 06 Nov 2026 21:11:24 GMT

{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019,"addedVia":null,"version":2}
```

Any other origin is rejected at the edge with a problem response, before an operation runs:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000002 -H 'Origin: https://evil.example'
```

```text
HTTP/1.1 403 
X-Correlation-Id: f2d1afd4-ddac-41fe-90c3-39079b0ce633
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Fri, 06 Nov 2026 21:11:24 GMT

{"type":"about:blank","title":"Forbidden","status":403,"detail":"The cross-origin request is not allowed","code":"CORS_REJECTED","correlationId":"f2d1afd4-ddac-41fe-90c3-39079b0ce633"}
```

### OpenAPI

`synapse-api-rest-openapi` (in `pom.xml`) documents the routes of `BooksRestConfiguration` in an OpenAPI 3.1 document,
which springdoc serves at `/v3/api-docs`: every route with its parameters, request and response schemas, statuses and
problem responses, named as the app's `JsonMapper` writes them. The document describes every route to anyone who can
reach the service, so it is off by default in every environment (404 `ROUTE_NOT_FOUND`): `application-local.yaml`
turns it on with `springdoc.api-docs.enabled: true`, and the tests, which run as `test`, turn it on only where they
check it.

```bash
curl -s http://localhost:8080/v3/api-docs | head -c 600
```

```text
{"openapi":"3.1.0","info":{"title":"OpenAPI definition","version":"v0"},"servers":[{"url":"http://localhost:8080","description":"Generated server url"}],"paths":{"/books/{id}":{"get":{"operationId":"get-book","parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string","format":"uuid"}},{"name":"If-None-Match","in":"header","description":"An entity tag (\"5\"), or *.","required":false,"schema":{"type":"string"}},{"name":"X-Correlation-Id","in":"header","description":"The request's correlation ID; one is generated when absent.","required":false,"schema":{"type":"string"}},{"
```

The `DELETE` of `/books/{id}` documents its `If-Match` as required, because `DeleteBookRequest.version` isn't
`@Nullable`, with the 428 among its responses:

```text
{"name":"If-Match","in":"header","description":"An entity tag (\"5\"), or *.","required":true,"schema":{"type":"string"}}
```

Synapse doesn't ship Swagger UI and turns it off (`springdoc.swagger-ui.enabled=false`). To browse the document
locally, add springdoc's UI starter to `pom.xml`. It is not in Synapse's BOM, so give it the version of
`springdoc-openapi-starter-webmvc-api` (3.2.0), and exclude `spring-boot-starter-logging` as for the other starters:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.2.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Then turn it on in `application-local.yaml` only, next to the document:

```yaml
springdoc:
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
```

Rebuild and start the service, and open http://localhost:8080/swagger-ui.html, which redirects to
`/swagger-ui/index.html` and loads the document from `/v3/api-docs`.

## gRPC

The same operations also serve a gRPC API, `synapse.samples.books.v1.BookService`, on port 9090 (Spring Boot's gRPC
server on Netty, next to Tomcat). The service is declared in `src/main/proto/books/v1/books.proto`; the build compiles
it into messages and stubs in `api.grpc.proto` (generated, under `target/generated-sources/protobuf`, and left out of
the coverage check). `api/grpc/BooksGrpcConfiguration.java` is a single `GrpcBindings` bean: Synapse maps each request
message to the operation's input record field by field (`added_via` to `addedVia`, a string `id` to a `UUID`, wrapper
types such as `google.protobuf.Int32Value year` to nullable components), runs the operation through the same pipeline
as REST, and maps the output back. The operation and model classes are the ones REST uses, unchanged.

| Method | Operation | Bound by | Errors |
|---|---|---|---|
| `GetBook(GetBookRequest) returns (Book)` | `get-book` | `.unary(...)` with `.notFound(BookErrors.BOOK_NOT_FOUND)` | `NOT_FOUND` `BOOK_NOT_FOUND`; `INVALID_ARGUMENT` for an id that isn't a UUID |
| `CreateBook(CreateBookRequest) returns (Book)` | `create-book` | `.unary(...)` with `.context(CreateBookRequest::channel, ...)`: `channel` from the `x-channel` metadata | `INVALID_ARGUMENT` `VALIDATION_FAILED`; `ABORTED` `DUPLICATE_ISBN` |
| `UpdateBook(UpdateBookRequest) returns (Book)` | `update-book` | `.bindAllByName()`; `version` is an `Int64Value`, unset for "the version the service reads" | `NOT_FOUND` `BOOK_NOT_FOUND`; `ABORTED` `DUPLICATE_ISBN` or `STALE_VERSION` |
| `DeleteBook(DeleteBookRequest) returns (google.protobuf.Empty)` | `delete-book` | `.bindAllByName()`; `version` as on `UpdateBook` | `NOT_FOUND` `BOOK_NOT_FOUND`; `ABORTED` `STALE_VERSION` |
| `GetCatalogStats(google.protobuf.Empty) returns (CatalogStats)` | `get-catalog-stats` | `.bindAllByName()` | |

The status code comes from the error's category: `NOT_FOUND` for `NOT_FOUND`, `ABORTED` for `CONFLICT`,
`INVALID_ARGUMENT` for `INVALID_INPUT`. `synapse.api.grpc.errors.status.[CODE]` changes it for a code. There is no
`If-Match` over gRPC: the version is a field, and a stale one is `ABORTED`, where REST answers 412.

At startup the gRPC plugin logs one line per method, after Boot's line for the server:

```text
{"@timestamp":"2026-11-06T21:52:03.760530100Z","log":{"level":"INFO","logger":"io.americanexpress.synapse.api.grpc"},"process":{"pid":22820,"thread":{"name":"main"}},"service":{"name":"rest-books","node":{}},"message":"Synapse gRPC exposes synapse.samples.books.v1.BookService/GetBook -> get-book on port 9090","ecs":{"version":"8.11"}}
```

### Call it with grpcurl

The commands below are standard [grpcurl](https://github.com/fullstorydev/grpcurl) usage, quoted for Git Bash or any
POSIX shell; they were not run with grpcurl here. Under `local`, server reflection is on
(`spring.grpc.server.reflection.enabled: true` in `application-local.yaml`), so grpcurl needs no `.proto` file. Every
other environment keeps Synapse's default, reflection off: pass the file instead, with
`-import-path samples/rest-books/src/main/proto -proto books/v1/books.proto`.

The outputs shown are real, recorded from the running service (`ENV=local`), but they come from a small grpc-java
client, not from grpcurl: it called the same methods with the same metadata and printed the response message, or the
error's code, message and `google.rpc.Status`, as protobuf JSON. grpcurl's own layout may differ.

```bash
grpcurl -plaintext localhost:9090 list
```

```text
grpc.health.v1.Health
synapse.samples.books.v1.BookService
grpc.reflection.v1.ServerReflection
```

Read a seeded book:

```bash
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000001"}' \
  localhost:9090 synapse.samples.books.v1.BookService/GetBook
```

```json
{
  "id": "00000000-0000-0000-0000-000000000001",
  "isbn": "978-0441172719",
  "title": "Dune",
  "author": "Frank Herbert",
  "year": 1965,
  "version": "1"
}
```

`added_via` is a `StringValue` and the book has none, so it is not in the output. `version` is an `int64`, which
protobuf JSON writes as a string.

Add a book, with the channel in the `x-channel` metadata (as `X-Channel` does over REST):

```bash
grpcurl -plaintext -H 'x-channel:grpc' \
  -d '{"isbn": "978-0151006205", "title": "The Telling", "author": "Ursula K. Le Guin", "year": 2000}' \
  localhost:9090 synapse.samples.books.v1.BookService/CreateBook
```

```json
{
  "id": "e0436518-3020-411a-ac5e-3f0a8f79e96f",
  "isbn": "978-0151006205",
  "title": "The Telling",
  "author": "Ursula K. Le Guin",
  "year": 2000,
  "addedVia": "grpc",
  "version": "1"
}
```

The book is in the same store REST reads: `curl -s http://localhost:8080/books/<id>` returns it.

### gRPC errors

An error is a status code, a message, and a `google.rpc.Status` in the `grpc-status-details-bin` trailer. The details
are an `ErrorInfo` whose `reason` is the error code and whose `domain` is `spring.application.name`, with the
`correlationId` in its metadata, then a `BadRequest` when there are violations.
The message is the formatted error message only when `synapse.errors.include-detail` is on (`local`); otherwise it is
empty. No stack trace or exception name ever reaches the caller.

A book that doesn't exist:

```bash
grpcurl -plaintext -H 'x-correlation-id:demo-123' -d '{"id": "00000000-0000-0000-0000-000000000099"}' \
  localhost:9090 synapse.samples.books.v1.BookService/GetBook
```

```text
Code: NOT_FOUND
Message: The resource was not found
google.rpc.Status: {
  "code": 5,
  "message": "The resource was not found",
  "details": [{
    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    "reason": "BOOK_NOT_FOUND",
    "domain": "rest-books",
    "metadata": {
      "correlationId": "demo-123"
    }
  }]
}
```

The reason is the declared code, `BOOK_NOT_FOUND`, but the message is `NOT_FOUND`'s. Over gRPC a `.notFound(...)` code
gets no message arguments, so when its template has placeholders (`Book {0} was not found`) the edge describes the error
with `NOT_FOUND`'s message, localized like any other, instead of printing the template unfilled. A template without
placeholders keeps its own text. (REST fills in the path variables, so its `detail` is
`Book 00000000-0000-0000-0000-000000000099 was not found`.) An id that isn't a UUID is `INVALID_ARGUMENT` before the
operation runs:

```bash
grpcurl -plaintext -H 'x-correlation-id:demo-125' -d '{"id": "not-a-uuid"}' \
  localhost:9090 synapse.samples.books.v1.BookService/GetBook
```

```text
Code: INVALID_ARGUMENT
Message: The request is invalid
google.rpc.Status: {
  "code": 3,
  "message": "The request is invalid",
  "details": [{
    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    "reason": "VALIDATION_FAILED",
    "domain": "rest-books",
    "metadata": {
      "correlationId": "demo-125"
    }
  }, {
    "@type": "type.googleapis.com/google.rpc.BadRequest",
    "fieldViolations": [{
      "field": "id",
      "description": "is not a valid value"
    }]
  }]
}
```

The `BadRequest` field is the proto field's path. Like a request that doesn't bind over REST, this call is turned away
before an operation runs, so it logs nothing.

A duplicate ISBN is a `CONFLICT`, so `ABORTED`, and the operation logs its failure line as over REST:

```bash
grpcurl -plaintext -H 'x-correlation-id:demo-124' \
  -d '{"isbn": "978-0441172719", "title": "The Telling", "author": "Ursula K. Le Guin", "year": 2000}' \
  localhost:9090 synapse.samples.books.v1.BookService/CreateBook
```

```text
Code: ABORTED
Message: A book with ISBN 978-0441172719 already exists
google.rpc.Status: {
  "code": 10,
  "message": "A book with ISBN 978-0441172719 already exists",
  "details": [{
    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    "reason": "DUPLICATE_ISBN",
    "domain": "rest-books",
    "metadata": {
      "correlationId": "demo-124"
    }
  }]
}
```

```json
{"@timestamp":"2026-11-06T21:53:41.091834300Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"},"process":{"pid":22820,"thread":{"name":"grpc-12"}},"service":{"name":"rest-books","node":{}},"message":"Operation create-book failed with DUPLICATE_ISBN (CONFLICT): A book with ISBN 978-0441172719 already exists","correlationId":"demo-124","error":{"category":"CONFLICT","code":"DUPLICATE_ISBN"},"operation":"create-book","spanId":"b380950ce6abfbef","traceId":"3ec84631f9d68f2388d79da88f791c8b","ecs":{"version":"8.11"}}
```

### x-correlation-id

gRPC uses the correlation header's name in lower case, `x-correlation-id` (`synapse.correlation.header`), as metadata.
An id the caller sends is echoed; without one, the service generates a UUID. A successful call echoes it in the
response headers; an error carries it in the trailers, next to `grpc-status-details-bin`, and in
`ErrorInfo.metadata.correlationId`. From the calls above:

```text
GetBook (x-correlation-id: demo-123, NOT_FOUND)  trailers: x-correlation-id=demo-123
CreateBook (no x-correlation-id, OK)             headers:  x-correlation-id=dfd00e1e-d329-4585-addb-d566a91b5cfe
```

`SearchBooks` comes to gRPC with page tokens (plan 3b): its `PageQuery` input and `PageResult` output have no gRPC
mapping yet, so `books.proto` leaves it out.

## What to look for in the logs

The console shows one JSON object per line, in the Elastic Common Schema (from `synapse-logging-log4j2`).

* At startup, one INFO event on `io.americanexpress.synapse.environment` names the environment and where it was set,
  then every configuration source, highest priority first, with the module or jar it comes from. It never prints a
  value. Its `message`, with `ENV=local`:

  ```text
  Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)
  Configuration, highest priority first:
    1. server.ports
    2. Synapse baggage fields
    3. Java system properties
    4. environment variables
    5. application-local.yaml  synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
    6. application.yaml        synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
    7. logCorrelation
    8. applicationInfo
    9. defaults
    10. Management Server
  ```

* Successful calls log nothing.
* A failing operation logs one WARN line on `io.americanexpress.synapse.operation` (ERROR with the stack trace for an
  internal error), with `operation`, `error.code`, `error.category`, `correlationId` and the trace and span ids, plus
  the context attribute `channel` when the request sent `X-Channel`. The correlation id example above logs:

  ```json
  {"@timestamp":"2026-11-06T21:11:23.524834100Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"},"process":{"pid":34980,"thread":{"name":"tomcat-handler-36"}},"service":{"name":"rest-books","node":{}},"message":"Operation create-book failed with DUPLICATE_ISBN (CONFLICT): A book with ISBN 978-0441172719 already exists","channel":"web","correlationId":"demo-123","error":{"category":"CONFLICT","code":"DUPLICATE_ISBN"},"operation":"create-book","spanId":"bd3affb3bdeb4a44","traceId":"cc5f4f56c0b84927a34859ebdfd3c1de","ecs":{"version":"8.11"}}
  ```

* A stale `If-Match` logs the operation's failure as `STALE_VERSION`, although the client gets 412
  `PRECONDITION_FAILED`: `Operation update-book failed with STALE_VERSION (CONFLICT): The resource was changed by
  another request`, with the request's `correlationId`.
* Requests turned away before an operation runs are not logged: an unknown book on `GET`, 405, 413, a request that
  doesn't bind, a sort key, offset or cursor the plugin rejects, a weak `If-Match` (412), a `DELETE` without
  `If-Match` (428), a version no route declares (404) and an origin CORS rejects (403).

## Safe defaults

Synapse's defaults are the same in every environment; only `application-local.yaml` turns `detail` (and CORS and
the OpenAPI document) on. Stop the service and start it with `detail` turned off again:

```bash
ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar \
  --synapse.errors.include-detail=false
```

No cursor key is set, so the plugin generates one at startup and warns, at every start, that tokens won't survive a
restart or work on another instance:

```text
{"@timestamp":"2026-11-06T21:11:53.773449800Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.api.rest"},"process":{"pid":41036,"thread":{"name":"main"}},"service":{"name":"rest-books","node":{}},"message":"synapse.api.rest.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor fails with 400 after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).","ecs":{"version":"8.11"}}
```

Problem responses leave out `detail`:

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"4040c3c9-094d-47b1-b35a-e5e0b46d84b3"}
```

Deleting a book that doesn't exist (with `If-Match: *`, so the route's 428 doesn't answer first) fails in the business
operation:

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000099 -H 'If-Match: *'
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"6e65779e-8135-4281-a7a9-16331413fd69"}
```

The log line of the failure still carries the message (`Book 00000000-0000-0000-0000-000000000099 was not found`), so
the `correlationId` a caller reports leads to it.

To silence the WARN, give the service a key. Stop it and start it with one (a real deployment sets the same key on
every instance, from the environment or a secret store; see `application.yaml`):

```bash
ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar \
  --synapse.api.rest.paging.cursor.key=$(openssl rand -base64 32)
```

The startup log then has no line about `synapse.api.rest.paging.cursor.key`, and no WARN at all.

## Configuration

`src/main/resources/application.yaml` lists the settings worth changing while you try the service, with comments: the
correlation header, error detail, the context header mapping, the deadline (`Request-Timeout`), the body limit, the
concurrency limit, and (commented out) the paging settings: `max-offset` and the cursor key, read from
`BOOKS_CURSOR_KEY`. Boot's OTLP metrics export is turned off there, because no OpenTelemetry collector runs with the
sample. `application-local.yaml` holds the local conveniences: error detail, the CORS origin of a local front end and
the OpenAPI document.

For gRPC it lists the server port (`spring.grpc.server.port: 9090`), the `x-channel` metadata mapping
(`synapse.api.grpc.context.metadata.channel`), the deadline cap and default, and the concurrency limit
(`synapse.api.grpc.max-concurrent-calls`). Reflection is off there (Synapse's default); `application-local.yaml` turns
it on.

This module inherits the repository's build parent so it passes the same gates as the library. Your own service
inherits `io.americanexpress.synapse:synapse-parent` and declares the same dependencies as this `pom.xml`.

## Tests

`./mvnw -pl samples/rest-books -am verify` builds the modules and runs every test:

| Test | Shows |
|---|---|
| `api/rest/BooksRestTest` | `@SynapseRestTest`: the bindings, edge filters and problem responses on MockMvc, with `@MockitoBean` operations: the page object and `Link`, 304, `If-Match` binding, 428, the version 2 route beside version 1 |
| `api/grpc/BooksGrpcTest` | `@SynapseGrpcTest`: the gRPC bindings and edge on Boot's in-process transport, with `@MockitoBean` operations and a client from `@ImportGrpcClients`: field mapping and wrapper presence, `NOT_FOUND` with `BOOK_NOT_FOUND` and `NOT_FOUND`'s message, `x-channel` into the context, an unset version as `null`, `Empty` in and out, `ABORTED` for `STALE_VERSION` and `DUPLICATE_ISBN` |
| `operation/BookOperationsTest` | `@SynapseOperationTest`: operations and business beans with the real pipeline (validation, error codes, versions, paging and sorting, the authors list of `BookV2`) |
| `business/ReviseBookTest` | `SynapseOperations.invoke`: business operations through their pipeline, without Spring, including the races that end in `STALE_VERSION` |
| `data/InMemoryBookStoreTest` | The store, as a plain unit test: the compare-and-set |
| `BooksApplicationIT` | The whole service on a real port, as `test` with `synapse.errors.include-detail=false` (the safe defaults: no `detail`): cursors and `Link`, offsets, 304, 412, 409 with a `@MockitoSpyBean` store, 428, `/v2` beside `/books` and `/v3` as 404, `/v3/api-docs` off in `test`, CORS off in `test`; a book added over REST and read over gRPC (Netty on a free port; each nested context starts its own); nested classes with `/v3/api-docs` on (every route listed), with CORS on: Actuator's paths untouched, and under a context path, an Actuator request's error response without CORS headers however the path is spelled; tagged `smoke` |
| `BooksLocalEnvironmentIT` | The whole service as `local`, with the real `application-local.yaml`: that file turns `/v3/api-docs` on; tagged `smoke` |
| `ArchitectureTest` | The layering rules |
