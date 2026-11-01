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
| `bom/synapse-bom` | Bill of materials for Synapse artifacts |
| `parent/synapse-parent` | Parent POM for applications built with Synapse |
| `core/synapse-core` | Protocol-agnostic core: operations, errors, correlation, conventions |
| `docs/` | Documentation site (Antora) |
| `specs/` | Design specifications |

## Contributing

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Contributors to American Express open-source
projects must sign the [Contributor License Agreement](https://cla-assistant.io/americanexpress/synapse).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

## Code of conduct

This project follows the [American Express Community Guidelines](CODE_OF_CONDUCT.md).
