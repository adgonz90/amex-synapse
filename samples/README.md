# Samples

Runnable Synapse 2 services. They are never published; each one has a README with the commands to run it. Read them
in this order: each one adds to the one before.

| # | Sample | What it adds | Protocols | Docker |
|---|---|---|---|---|
| 1 | [hello-rest](hello-rest/README.md) | The quick start: one operation, one route, the tests. Inherits `synapse-parent` as a real service does | REST | No |
| 2 | [rest-books](rest-books/README.md) | The REST tour, in memory: business rules, paging and cursors, ETags and conditional requests, API versions, CORS, the OpenAPI document, problem responses and the safe defaults. Inherits `synapse-parent` | REST | No |
| 3 | [grpc-inventory](grpc-inventory/README.md) | The gRPC tour, in memory, with the `.proto` in a contract module: every field kind (timestamps, durations, wrappers, enums, repeated, maps, nested messages), a `oneof` and a renamed field, entity tags, page tokens, context metadata, error details and a per-operation status override, called with `grpcurl`. Inherits `synapse-parent` | gRPC | No |
| 4 | [multi-store](multi-store/README.md) | Two named data stores on PostgreSQL, each in a data module of its own: `orders` on JPA (`AuditedEntity`, UUIDv7 ids) and `ledger` on Spring Data JDBC (an `Auditable` with its own columns). Flyway per store, module-owned settings, `@Transactional` that names its store, a write to two stores that is not atomic, the audit system user, readiness that only one store takes part in, and `@SynapseDataTest` on Testcontainers. Run with `docker compose`. Inherits `synapse-parent` | REST | Yes: the databases and the integration tests |
| 5 | [reference-bookstore](reference-bookstore/README.md) | The reference service, laid out as a Synapse service should be: a contract module (records, error codes and the `.proto`), a data module (the `books` store on JPA and PostgreSQL, with its settings per environment) and a service module, with one set of operations served over REST and gRPC with the same contract. An `ETag` and a page token from one protocol work on the other. Readiness at `/health`; the OpenAPI document and CORS in `local` only; the audit user; environments (`local` on `docker compose`, `test`, `build`, `dev`), and AOT mode, opt-in with `-Paot` (see below), in which an environment that turns on a build-time switch fails startup. One scenario suite runs every case over both protocols, against the test context and, with `-Paot`, against the packaged jar in AOT mode. Inherits `synapse-parent` | REST, gRPC | Yes: the database, the integration tests and the images |

## Before you start

- **JDK 25** builds Synapse and the samples (the services themselves run on Java 21 or later). Point `JAVA_HOME` at a
  JDK 25 (`export JAVA_HOME=<path to a JDK 25>`) and put `$JAVA_HOME/bin` first on `PATH`, so that `java -version`
  reports 25.
- **A shell:** the commands run from the repository root, in Git Bash on Windows or any POSIX shell.
- **Docker**, for multi-store and the reference bookstore. In Git Bash on Windows, if `docker` isn't found, put Docker
  Desktop's `resources/bin` directory first on `PATH` (for a machine-wide install,
  `export PATH="/c/Program Files/Docker/Docker/resources/bin:$PATH"`).
- **Formatting and license headers:** the samples inherit `synapse-parent`, which has no formatter or license check,
  as a service of yours wouldn't. The repository root formats their code (Spotless) and checks their license headers.
  A build with `-am` includes the root (the samples depend on modules whose parent it is), so it formats and checks
  them too; CI's run (`CI=true`) checks without formatting. `./mvnw -N process-sources` formats them without a build.
  A copy of a sample outside this repository has neither until you add your own.

## Building a sample

Build and test one through its bootable module, from the repository root: `./mvnw -pl samples/hello-rest -am verify`.
A sample made of modules is built the same way, through the module that starts the service, which pulls in the
others: `./mvnw -pl samples/grpc-inventory/service-inventory -am verify` (`-pl samples/grpc-inventory` alone would
build only its aggregator pom). `-am` builds and tests the Synapse modules the sample depends on first, their
integration tests included.

A sample README's command for one test class adds `-Djacoco.skip=true`: the coverage gate needs the tests of every
module the build includes.

## AOT mode, in the reference bookstore only

Spring AOT processing is opt-in (`-Paot`, `synapse-parent`'s `aot` profile), and only the reference bookstore uses it:
`./mvnw -Paot -pl samples/reference-bookstore/service-bookstore -am verify` (its README's
[AOT mode](reference-bookstore/README.md#aot-mode)). The other samples have no build environment file
(`application-build.yaml`), on purpose: they show a service that needs none. So `-Paot` fails in them, with `The
environment 'build' has no file in the service`, and a build with `-Paot` from the repository root fails too.
