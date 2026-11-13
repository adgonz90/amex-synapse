# Synapse

> **Synapse 2 is under active development on this branch (`main`) and is not released yet.**
> The current release line, 0.4.x, lives on the [`develop`](https://github.com/americanexpress/synapse/tree/develop)
> branch and receives security fixes only.

Synapse is a set of Spring Boot starters for building enterprise services with consistent, prescriptive
conventions. You write each business operation once, in a module that knows nothing about protocols, and expose it
over REST, gRPC, GraphQL or cloud functions by adding the matching Synapse plugin. Clients, data access and
messaging follow the same conventions, with secure defaults and built-in observability.

The build specification and delivery plan for Synapse 2 are in [specs/v2/BUILD.md](specs/v2/BUILD.md), and the
design decisions and roadmap are in [specs/v2/DESIGN.md](specs/v2/DESIGN.md).

## Getting started

- **Samples.** [samples/README.md](samples/README.md) lists the runnable samples in reading order: `hello-rest` (one
  route), `rest-books` (the REST tour), `grpc-inventory` (gRPC with a contract module), `multi-store` (data modules
  and two stores) and `reference-bookstore` (the reference service, over REST and gRPC). Each README has the commands
  to run it and the output to expect.
- **Archetypes.** Two Maven archetypes generate a new project shaped like a sample: `synapse-archetype-quickstart`
  (like `hello-rest`) and `synapse-archetype-service` (like the reference bookstore). The `mvn archetype:generate`
  commands are in [docs/modules/ROOT/pages/archetypes.adoc](docs/modules/ROOT/pages/archetypes.adoc). Synapse 2 is
  not published yet: until it is, `./mvnw install` from this repository puts the archetypes and `synapse-parent` in
  your local Maven repository, where `archetype:generate` finds them.
- **Documentation.** The guides are in [docs/modules/ROOT/pages](docs/modules/ROOT/pages), starting with
  [core.adoc](docs/modules/ROOT/pages/core.adoc); the site's navigation is
  [docs/modules/ROOT/nav.adoc](docs/modules/ROOT/nav.adoc).

## Requirements

| | Version |
|---|---|
| Java (applications) | 21 or newer |
| Java (building Synapse) | 25 |
| Spring Boot | 4.1.x |
| Maven | 3.9.6 or newer (use the included wrapper) |

## Building

```bash
./mvnw verify                      # unit tests, integration tests and all quality gates
./mvnw verify -Djvm=/path/to/jdk-21/bin/java   # run the tests on a Java 21 runtime
./mvnw -Pmutation verify           # also run mutation testing (PIT)
```

Local builds format the code for you. In CI, unformatted code fails the build. The code style is defined in
[.config/synapse-java-format.xml](.config/synapse-java-format.xml); import it into your IDE so format-on-save matches
the build (instructions are at the top of the file).

The build also fails on Error Prone/NullAway errors, banned dependencies, failing unit or integration tests, and
coverage below the per-module threshold. Pull requests must additionally cover at least 90% of the lines they change.

## Repository layout

| Path | Contents |
|---|---|
| `pom.xml` | Root aggregator and internal build parent (versions, gates, profiles) |
| `bom/synapse-bom` | Bill of materials for consumers (does not import Spring Boot's BOM) |
| `parent/synapse-parent` | Parent POM for consumer applications (extends `spring-boot-starter-parent`) |
| `core/synapse-core` | Protocol-agnostic core |
| `core/synapse-test` | Test support: `SynapseOperations`, `@SynapseOperationTest`, architecture rules, AOT assertions |
| `core/synapse-logging-log4j2` | Prescriptive Log4j2 logging: ECS JSON default, optional failure-log burst limit |
| `api/` | API plugins, their test slices and add-ons (synapse-api-rest, synapse-test-rest, synapse-api-rest-openapi, synapse-api-grpc, synapse-test-grpc) |
| `data/` | Data modules and their test slice (synapse-data-core, -jpa, -jdbc, -postgres, synapse-test-data) |
| `samples/` | Runnable samples (hello-rest, rest-books, grpc-inventory, multi-store, reference-bookstore), each on `synapse-parent` with a README run against the app; never published |
| `archetypes/` | Maven archetypes (synapse-archetype-quickstart, shaped like hello-rest; synapse-archetype-service, shaped like the reference bookstore), each with an IT that generates a project and builds it; javadoc-placeholder (the javadoc jar they attach on release) |
| `docs/` | Documentation site (Antora, AsciiDoc); property tables are generated from configuration metadata |
| `tests/` | Tests of the build itself (maven-invoker projects for the Enforcer rules) and of modules that only meet in an application (synapse-data-mixed-tests: JPA and JDBC stores together; synapse-logging-logback-tests: Synapse's log lines on Logback in Boot's structured formats); never published |
| `specs/` | Design specifications |
| `.config/` | Formatter profile and license header |

## Contributing

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Contributors to American Express open-source
projects must sign the [Contributor License Agreement](https://cla-assistant.io/americanexpress/synapse).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

## Code of conduct

This project follows the [American Express Community Guidelines](CODE_OF_CONDUCT.md).
