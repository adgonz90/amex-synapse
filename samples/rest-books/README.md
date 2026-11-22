# Sample: rest-books, the REST tour

A small Synapse 2 service you can run and call: a catalog of books served over REST. It tours what the REST plugin
does, from paging and conditional requests to API versions, CORS and the OpenAPI document. It uses `synapse-api-rest`,
`synapse-api-rest-openapi`, `synapse-logging-log4j2` and the Synapse test modules, and inherits `synapse-parent` as a
real service does. The data lives in memory, starts with three books, and is lost when the service stops.

For the smallest service, see [`hello-rest`](../hello-rest/README.md). For gRPC, see [gRPC](#grpc).

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
| The environment: `spring.profiles.active: ${ENV}` in `application.yaml`, and one file per environment (`local` for local runs, `test` for the tests) | `src/main/resources/`, `src/test/resources/` |
| The pom of a real service: `synapse-parent` as the parent, dependencies without versions | `pom.xml` |
| Tests: the REST slice (`@SynapseRestTest`), the operation slice (`@SynapseOperationTest`), `SynapseOperations.invoke`, end-to-end tests on a real server, and the layering rules | [Tests](#tests) |

The packages follow Synapse's layering (specs/v2/BUILD.md section 4.3): `api.rest` calls `operation`; operations call
`business` (only through `BusinessOperation.execute`) and `data`; business code calls `data`; nobody calls `api`.
`ArchitectureTest` checks it with `SynapseArchitecture.forApplication(BooksApplication.class)`.

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

You need JDK 25 to build (see [Before you start](../README.md#before-you-start)). Then, from the repository root,
in Git Bash on Windows or any POSIX shell:

```bash
./mvnw -pl samples/rest-books -am package -DskipTests
ENV=local java -jar samples/rest-books/target/rest-books-2.0.0-SNAPSHOT.jar
```

The first command builds the sample and the Synapse modules it uses, without running the tests (`./mvnw verify` runs
them; see [Tests](#tests)). The second runs the `local` environment: `application.yaml` maps
`spring.profiles.active: ${ENV}`, and `application-local.yaml` turns on `detail` in problem responses
(`synapse.errors.include-detail: true`), CORS for a local front end and the OpenAPI document.

You can also run it with `spring-boot:run`, which runs the `local` environment too (the pom sets
`spring-boot.run.profiles`; `-Dspring-boot.run.profiles=<env>` picks another). It runs the sample alone, so it takes
the Synapse modules from your local Maven repository: install them first (again after you change them):

```bash
./mvnw -pl samples/rest-books -am install -DskipTests
./mvnw -pl samples/rest-books spring-boot:run
```

`install` puts the Synapse modules in `~/.m2`, replacing any Synapse snapshot you have there.

The service listens on port 8080; stop it with Ctrl+C. At startup it logs which environment it runs and where its
configuration comes from (see [What to look for in the logs](#what-to-look-for-in-the-logs)), and the REST plugin logs
one line per route, for example:

```text
{"@timestamp":"2026-11-07T18:05:22.946134600Z","log":{"level":"INFO","logger":"io.americanexpress.synapse.api.rest"},"process":{"pid":29344,"thread":{"name":"main"}},"service":{"name":"rest-books","version":"2.0.0-SNAPSHOT","node":{}},"message":"Synapse REST exposes GET /books/{id} -> get-book on port 8080","ecs":{"version":"8.11"}}
```

It also logs a WARN that `synapse.paging.cursor.key` is not set (see [Safe defaults](#safe-defaults)).

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
X-Correlation-Id: 31d95fde-57af-439d-9dac-c06d5901d86d
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Link: </books?sort=title&size=2&cursor=AnK_zMM8l6T6v7MB1U6O8pmlQyLWN9tCq4VlbkHR4LYx-xlUOGW_KQOsPfU>; rel="next"
Content-Type: application/json
Content-Length: 431
Date: Sat, 07 Nov 2026 18:05:50 GMT

{"items":[{"id":"00000000-0000-0000-0000-000000000003","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","year":1968,"addedVia":null,"version":1},{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}],"nextCursor":"AnK_zMM8l6T6v7MB1U6O8pmlQyLWN9tCq4VlbkHR4LYx-xlUOGW_KQOsPfU","hasNext":true,"total":3}
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

A page can also start at an offset, up to `synapse.paging.max-offset` (1000); beyond that, callers page by
cursor. An offset page that has a next page gets a `nextCursor` and a `Link` too, which carry on from that page. This
one is the last:

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
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"a43911b1-9550-40fe-9728-80f295fa9b5a","violations":[{"field":"sort","message":"has a key that is not allowed (allowed: title, year)"}]}
```

A changed token (here its first character):

```bash
curl -s "http://localhost:8080/books?sort=title&size=2&cursor=$(echo "$NEXT" | sed 's/^A/B/')"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"9667777e-05f3-48d3-85b6-56ac65ae9c59","violations":[{"field":"cursor","message":"is not valid for this request"}]}
```

A token is bound to the operation, the sort and the other inputs (such as `author`), so the same token with another
sort is rejected the same way. Every reason gets the same message.

```bash
curl -s "http://localhost:8080/books?sort=-year&size=2&cursor=$NEXT"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"8cb62fae-10b5-41e2-81ed-504afc9dba7c","violations":[{"field":"cursor","message":"is not valid for this request"}]}
```

An offset over the cap:

```bash
curl -s "http://localhost:8080/books?offset=1001"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"b4442baf-fe09-4170-b277-ada04122f6f2","violations":[{"field":"offset","message":"must be less than or equal to 1000"}]}
```

### An output header

`.header("X-Books-Total", CatalogStats::books)` writes that component to a response header. `HEAD` answers the
headers of the `GET` without the body:

```bash
curl -s -I http://localhost:8080/books/stats
```

```text
HTTP/1.1 200 
X-Correlation-Id: 6b8d8d97-0da7-43b6-bc40-0863766044e0
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
X-Books-Total: 3
Content-Type: application/json
Content-Length: 23
Date: Sat, 07 Nov 2026 18:05:50 GMT
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
X-Correlation-Id: 7060f339-0d09-4811-8c9c-30f4b49ba6b5
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Location: /books/8306a1ef-99b8-4b69-b78a-2b052c74afe5
ETag: "1"
Content-Type: application/json
Content-Length: 178
Date: Sat, 07 Nov 2026 18:05:51 GMT

{"id":"8306a1ef-99b8-4b69-b78a-2b052c74afe5","isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954,"addedVia":"mobile","version":1}
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
X-Correlation-Id: 205b6a78-37e4-4bea-bbb3-8c87b16177c8
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Date: Sat, 07 Nov 2026 18:05:51 GMT
```

### Errors

Every error is `application/problem+json` with the `code` callers match and the `correlationId`.

Deleting the same book again, with `If-Match: *` (any version): the business operation throws
`SynapseException(BookErrors.BOOK_NOT_FOUND, id)`, and the message template `Book {0} was not found` is filled in.

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003 -H 'If-Match: *'
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000003 was not found","code":"BOOK_NOT_FOUND","correlationId":"8305d8a9-20dd-40ab-82a9-d81ebf9d637d"}
```

Reading a book that doesn't exist: the operation returns an empty `Optional`, and `.notFound(BookErrors.BOOK_NOT_FOUND)`
on the route answers it. The route's path values (here `{id}`) fill the template's placeholders, in template order.

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000099 was not found","code":"BOOK_NOT_FOUND","correlationId":"30b654f3-7447-469f-afb9-7633bcc9dd21"}
```

A validation error: the pipeline runs Jakarta Validation on the request record before the operation and reports
every violation.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"","title":" ","author":"Nobody","year":-1}'
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"8695c669-b93a-4b42-9aeb-e4c948bcd51f","violations":[{"field":"isbn","message":"must not be blank"},{"field":"title","message":"must not be blank"},{"field":"year","message":"must be greater than 0"}]}
```

A business rule: the ISBN of Dune is taken.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert"}'
```

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"f8d3b9c2-a93b-4075-a7ea-cf2e950eff40"}
```

A method the path doesn't serve is 405 with `Allow`:

```bash
curl -s -i -X PATCH http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 405 
X-Correlation-Id: f2afc87e-ac60-4495-a661-b32f078f884b
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Allow: GET, HEAD, PUT, DELETE, OPTIONS
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 18:05:51 GMT

{"type":"about:blank","title":"Method Not Allowed","status":405,"detail":"The method is not allowed for this route","code":"METHOD_NOT_ALLOWED","correlationId":"f2afc87e-ac60-4495-a661-b32f078f884b"}
```

A body over the limit is 413 before the body is read. `POST /books` allows 16 KB (`.maxRequestSize(...)`); this sends
20,000 bytes. (`-H 'Expect:'` stops curl from waiting for a `100 Continue` first.)

```bash
head -c 20000 /dev/zero | tr '\0' x | curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'Expect:' --data-binary @-
```

```text
HTTP/1.1 413 
X-Correlation-Id: 8612d44e-3968-4ba1-b43e-3b203884b594
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 18:05:51 GMT
Connection: close

{"type":"about:blank","title":"Content Too Large","status":413,"detail":"The request body is too large","code":"PAYLOAD_TOO_LARGE","correlationId":"8612d44e-3968-4ba1-b43e-3b203884b594"}
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
Date: Sat, 07 Nov 2026 18:05:51 GMT

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
X-Correlation-Id: e4ce3482-8244-4dd2-8831-f7b9e90eba7f
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Cache-Control: no-cache
Vary: Accept
ETag: "1"
Content-Type: application/json
Content-Length: 149
Date: Sat, 07 Nov 2026 18:05:51 GMT

{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}
```

A client that has version 1 sends it in `If-None-Match`, and gets 304 without a body while the book is unchanged:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-None-Match: "1"'
```

```text
HTTP/1.1 304 
X-Correlation-Id: fc66bee8-b38a-4bca-88fc-38a6e333fc5b
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
ETag: "1"
Cache-Control: no-cache
Vary: Accept
Date: Sat, 07 Nov 2026 18:05:51 GMT
```

`HEAD` answers the same way:

```bash
curl -s -I http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-None-Match: "1"'
```

```text
HTTP/1.1 304 
X-Correlation-Id: 9ce1215d-7d0a-4676-9ec4-649784645c29
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
ETag: "1"
Cache-Control: no-cache
Vary: Accept
Date: Sat, 07 Nov 2026 18:05:51 GMT
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
{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"c001795c-ef58-4bf0-bb3b-5f3614f17bf5"}
```

A weak tag can never match a write's precondition, so the plugin answers 412 before the operation runs:

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: W/"2"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"e10c1ffd-0f3f-4566-8b2c-043c3f2fec06"}
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
X-Correlation-Id: e67fd8f2-fe8a-483d-8277-40124080d27e
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 18:05:51 GMT

{"type":"about:blank","title":"Precondition Required","status":428,"detail":"The request must be conditional","code":"PRECONDITION_REQUIRED","correlationId":"e67fd8f2-fe8a-483d-8277-40124080d27e","violations":[{"field":"If-Match","message":"is required; send the ETag from a GET of this resource"}]}
```

With the current version (2, after the update above), the book is deleted:

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-Match: "2"'
```

```text
HTTP/1.1 204 
X-Correlation-Id: c9c1f3bc-a0b3-4719-a273-7e8178567d15
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Date: Sat, 07 Nov 2026 18:05:51 GMT
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
X-Correlation-Id: 049db2d7-b168-4f9a-bdd9-2e917b77cae2
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Cache-Control: no-cache
Vary: Accept
ETag: "1"
Content-Type: application/json
Content-Length: 158
Date: Sat, 07 Nov 2026 18:05:52 GMT

{"id":"0e800962-549b-492f-9c61-2e6b0cbd09c8","isbn":"978-0060853983","title":"Good Omens","authors":["Terry Pratchett","Neil Gaiman"],"year":1990,"version":1}
```

`/books/{id}` keeps serving version 1 unchanged, one author string:

```bash
curl -s http://localhost:8080/books/$ID
```

```json
{"id":"0e800962-549b-492f-9c61-2e6b0cbd09c8","isbn":"978-0060853983","title":"Good Omens","author":"Terry Pratchett, Neil Gaiman","year":1990,"addedVia":null,"version":1}
```

A version that no route declares is no route at all:

```bash
curl -s http://localhost:8080/v3/books/$ID
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"No route matches the request","code":"ROUTE_NOT_FOUND","correlationId":"d1199394-4a92-4b36-941f-237389f81a59"}
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
X-Correlation-Id: 9556c9de-aec4-46cc-999a-144023b1847b
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Methods: GET,HEAD,POST,PUT,DELETE
Access-Control-Allow-Headers: Content-Type, If-Match
Access-Control-Expose-Headers: X-Correlation-Id, ETag, Link, Location, Retry-After, Deprecation, Sunset
Access-Control-Max-Age: 1800
Content-Length: 0
Date: Sat, 07 Nov 2026 18:05:52 GMT
```

The actual request gets `Access-Control-Allow-Origin`, and `Access-Control-Expose-Headers` lets the page read the
headers Synapse writes: the correlation id, `ETag`, `Link`, `Location`, `Retry-After`, `Deprecation` and `Sunset`:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000002 -H 'Origin: http://localhost:3000'
```

```text
HTTP/1.1 200 
X-Correlation-Id: f27359f5-9608-41cb-bebb-9a8d6baa4eb3
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
Date: Sat, 07 Nov 2026 18:05:52 GMT

{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019,"addedVia":null,"version":2}
```

Any other origin is rejected at the edge with a problem response, before an operation runs:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000002 -H 'Origin: https://evil.example'
```

```text
HTTP/1.1 403 
X-Correlation-Id: 39e0d962-1a1c-47fc-8902-a5836372f05a
Vary: Origin
Vary: Access-Control-Request-Method
Vary: Access-Control-Request-Headers
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 18:05:52 GMT

{"type":"about:blank","title":"Forbidden","status":403,"detail":"The cross-origin request is not allowed","code":"CORS_REJECTED","correlationId":"39e0d962-1a1c-47fc-8902-a5836372f05a"}
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

This sample is REST only. Its operations and models have no protocol in them, so serving them over gRPC too changes
none of them: the service adds `synapse-api-grpc`, a `.proto` service and one `GrpcBindings` bean. Two samples show
it:

* [`grpc-inventory`](../grpc-inventory/README.md): a gRPC-first service, with its `.proto` in a contract module, called
  with grpcurl;
* [`reference-bookstore`](../reference-bookstore/README.md): a bookstore over REST and gRPC on PostgreSQL, in the
  archetype's multi-module layout, with one scenario suite that runs over both protocols: the same `ETag` token, error
  codes and page tokens on each.

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
    5. application-local.yaml  rest-books-2.0.0-SNAPSHOT.jar
    6. application.yaml        rest-books-2.0.0-SNAPSHOT.jar
    7. logCorrelation
    8. applicationInfo
    9. defaults
    10. Management Server
  ```

* Successful calls log nothing.
* A failing operation logs one WARN line on `io.americanexpress.synapse.operation` (ERROR with the stack trace for an
  internal error), with `operation`, `errorCode`, `errorCategory`, `correlationId` and the trace and span ids, plus
  the context attribute `channel` when the request sent `X-Channel`. The correlation id example above logs:

  ```json
  {"@timestamp":"2026-11-07T18:05:51.532239100Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"},"process":{"pid":29344,"thread":{"name":"tomcat-handler-36"}},"service":{"name":"rest-books","version":"2.0.0-SNAPSHOT","node":{}},"message":"Operation create-book failed with DUPLICATE_ISBN (CONFLICT): A book with ISBN 978-0441172719 already exists","channel":"web","correlationId":"demo-123","errorCategory":"CONFLICT","errorCode":"DUPLICATE_ISBN","operation":"create-book","spanId":"8600eb7c051ad355","traceId":"ba0885394f6ce24fd05849bdb3f11f6d","ecs":{"version":"8.11"}}
  ```

* A stale `If-Match` logs the operation's failure as `STALE_VERSION`, although the client gets 412
  `PRECONDITION_FAILED`: `Operation update-book failed with STALE_VERSION (CONFLICT): The resource was changed by
  another request`, with the request's `correlationId`.
* Requests turned away before an operation runs are not logged: an unknown book on `GET`, 405, 413, a request that
  doesn't bind, a sort key, offset or cursor the plugin rejects, a weak `If-Match` (412), a `DELETE` without
  `If-Match` (428), a version no route declares (404) and an origin CORS rejects (403).

## Safe defaults

Synapse's defaults are the same in every environment; only `application-local.yaml` turns `detail` (and CORS and the
OpenAPI document) on. Stop the service and start it with `detail` turned off again:

```bash
ENV=local java -jar samples/rest-books/target/rest-books-2.0.0-SNAPSHOT.jar \
  --synapse.errors.include-detail=false
```

No cursor key is set, so Synapse generates one at startup and warns, at every start, that tokens won't survive a
restart or work on another instance:

```text
{"@timestamp":"2026-11-07T18:06:25.093767Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.core"},"process":{"pid":8836,"thread":{"name":"main"}},"service":{"name":"rest-books","version":"2.0.0-SNAPSHOT","node":{}},"message":"synapse.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor is rejected after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).","ecs":{"version":"8.11"}}
```

Problem responses leave out `detail`:

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"33770d24-57b0-4af1-abe5-c3640af32d85"}
```

Deleting a book that doesn't exist (with `If-Match: *`, so the route's 428 doesn't answer first) fails in the business
operation:

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000099 -H 'If-Match: *'
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"8024cde7-698a-4ced-8c02-75602bf7baa5"}
```

The log line of the failure still carries the message (`Book 00000000-0000-0000-0000-000000000099 was not found`), so
the `correlationId` a caller reports leads to it.

To silence the WARN, give the service a key. Stop it, and start it again with the key in the environment variable
`SYNAPSE_PAGING_CURSOR_KEY`, as `application-local.yaml` says. Boot binds that variable to `synapse.paging.cursor.key`
(dots become underscores, letters upper case), so no file names the key. The variable is set for the `java` command
only, so the rest of your shell is unchanged and the runs above still show the WARN. A real deployment sets the same
key on every instance, from the environment or a secret store, never from a file in the repository:

```bash
KEY=$(openssl rand -base64 32)
SYNAPSE_PAGING_CURSOR_KEY=$KEY ENV=local java -jar samples/rest-books/target/rest-books-2.0.0-SNAPSHOT.jar
```

The startup log then has no line about `synapse.paging.cursor.key`, and no WARN at all. A cursor now survives a
restart: stop the service, run the same `java` command again (with the same `$KEY`), and a `nextCursor` from before the
restart still pages.

## Configuration

`src/main/resources/application.yaml` lists the settings worth changing while you try the service, with comments: the
correlation header, error detail, the context header mapping, the deadline (`Request-Timeout`), the body limit, the
concurrency limit, and (commented out) core's paging settings under `synapse.paging`: `max-offset`, and the cursor
key, which no file sets: it comes from the environment variable `SYNAPSE_PAGING_CURSOR_KEY` (see
[Safe defaults](#safe-defaults)). Boot's OTLP metrics export is turned off there, because no OpenTelemetry collector
runs with the sample. `application-local.yaml` holds the local conveniences: error detail, the CORS origin of a local
front end and the OpenAPI document, and a comment on how to give local runs a cursor key.

The sample builds without Spring AOT processing, which `synapse-parent` runs only with `-Paot`, so it has no build
environment file (`application-build.yaml`), and `java -jar` runs the configuration classes as usual. Under AOT, the
OpenAPI document is a build-time switch: the [reference bookstore](../reference-bookstore/README.md#aot-mode) shows the
opt-in, and a run in `local` that fails because `springdoc.api-docs.enabled: true` differs from the build.

`pom.xml` is the pom of a real service: it inherits `io.americanexpress.synapse:synapse-parent`, and no dependency has
a version. Only the four `maven.*.skip` properties, which keep the sample from being published, are not what your
service would have.

## Tests

`./mvnw -pl samples/rest-books -am verify` builds the modules and runs every test:

| Test | Shows |
|---|---|
| `api/rest/BooksRestTest` | `@SynapseRestTest`: the bindings, edge filters and problem responses on MockMvc, with `@MockitoBean` operations: the page object and `Link`, 304, `If-Match` binding, 428, the version 2 route beside version 1 |
| `operation/BookOperationsTest` | `@SynapseOperationTest`: operations and business beans with the real pipeline (validation, error codes, versions, paging and sorting, the authors list of `BookV2`) |
| `business/ReviseBookTest` | `SynapseOperations.invoke`: business operations through their pipeline, without Spring, including the races that end in `STALE_VERSION` |
| `data/InMemoryBookStoreTest` | The store, as a plain unit test: the compare-and-set |
| `BooksApplicationIT` | The whole service on a real port, as `test` with `synapse.errors.include-detail=false` (the safe defaults: no `detail`): cursors and `Link`, offsets, 304, 412 on `PUT` and `DELETE`, 409 with a `@MockitoSpyBean` store, 428, `/v2` beside `/books` and `/v3` as 404, `/v3/api-docs` off in `test`, CORS off in `test`; nested classes with `/v3/api-docs` on (every route listed), with CORS on: Actuator's paths untouched, and under a context path, an Actuator request's error response without CORS headers however the path is spelled; tagged `smoke` |
| `BooksLocalEnvironmentIT` | The whole service as `local`, with the real `application-local.yaml` and no test property: that file turns `/v3/api-docs` on; tagged `smoke` |
| `ArchitectureTest` | The layering rules |

To run one unit test class, or one integration test class alone:

```bash
./mvnw -pl samples/rest-books -am test -Dtest=BookOperationsTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl samples/rest-books -am verify -Dit.test=BooksApplicationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

`-Djacoco.skip=true` turns off the coverage gate, which needs the tests of every module the build includes.

## Layout

```
samples/rest-books/
  pom.xml                             parent synapse-parent; the REST plugin, the OpenAPI add-on, and the test modules
  src/main/java/.../books/
    BooksApplication.java             @SpringBootApplication
    api/rest/BooksRestConfiguration   RestBindings: every route, its paging, ETags, versions and headers
    operation/                        Get, Search, Create, Update and Delete book, GetBookV2, GetCatalogStats
    business/                         AddBook, ReviseBook, RemoveBook: the ISBN and version rules
    business/model/                   NewBook, BookRevision, BookRemoval, CatalogBook
    contract/BookErrors               BOOK_NOT_FOUND, DUPLICATE_ISBN
    model/                            the API's records: Book, BookV2, CatalogStats and the requests
    data/                             InMemoryBookStore (a compare-and-set on the version), StoredBook
  src/main/resources/
    application.yaml                  spring.profiles.active: ${ENV}, the application name, the REST limits, OTLP
                                      metrics push off
    application-local.yaml            local conveniences: detail, CORS and the OpenAPI document
  src/test/java/.../books/            the tests above
  src/test/resources/
    application-test.yaml             the test environment: detail on, so the tests can check it
```

## Make it yours

rest-books is a tour, so a service of yours starts from [hello-rest](../hello-rest/README.md) or the quickstart
archetype more often than from a copy of it. To copy it anyway, change these:

* `pom.xml`: the `groupId`, `artifactId` and `description`; the parent's `relativePath` (drop it when the parent comes
  from a repository); and remove the four `maven.*.skip` properties, which only keep this sample from being published.
* The package `io.americanexpress.synapse.samples.books` and `BooksApplication`.
* `spring.application.name` in `application.yaml`.
* `data/InMemoryBookStore`, which stands in for a database: a data module on a store replaces it, as in
  [multi-store](../multi-store/README.md) and the [reference bookstore](../reference-bookstore/README.md).
