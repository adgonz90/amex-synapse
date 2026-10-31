# Contributing to Synapse

Thanks for your interest in Synapse. This guide covers how to propose a change and how changes are released.
Design decisions live in [specs/v2/SPEC.md](specs/v2/SPEC.md), and the engineering rules and quality gates are in
[AGENTS.md](AGENTS.md); read both before starting anything non-trivial.

## Before you start

- **Contributor License Agreement.** Contributors to American Express open-source projects must sign the
  [CLA](https://cla-assistant.io/americanexpress/synapse). The CLA check on your pull request links to it.
- **Discuss larger changes first.** Open an issue for new features or design changes. If the question is listed
  under *Open questions* in the spec, the maintainers decide it before code is written.
- **Security issues** are never reported in public issues. See [SECURITY.md](SECURITY.md).

## Branches

| Branch | Line | Accepts |
|---|---|---|
| `main` | Synapse 2 (in development) | All new work |
| `develop` | Synapse 0.4.x | Security (CVE) fixes only |

Work on a short-lived branch (`feature/<topic>` or `fix/<topic>`) and open a pull request against `main`.

## Building and testing

Building needs JDK 25 (applications run on Java 21 or newer). Always use the Maven Wrapper:

```bash
./mvnw verify                                  # unit + integration tests and all quality gates
./mvnw verify -Djvm=/path/to/jdk-21/bin/java   # run the tests on a Java 21 runtime
```

Local builds format your code; CI only checks formatting. Every gate in the [AGENTS.md](AGENTS.md#quality-gates-ci-fails-on-any-of-these)
list must pass, including at least 90% coverage of the lines your pull request changes. Bug fixes start with a test
that reproduces the bug.

The documentation site is built with [Antora](https://antora.org) from [docs/](docs/):

```bash
cd docs && npm ci && npx antora antora-playbook.yml   # output in docs/build/site
```

## Pull requests

Pull requests are squash-merged, so **the pull request title becomes the commit message** on `main`. Titles must
follow [Conventional Commits](https://www.conventionalcommits.org/); a check enforces this. Release notes and version
numbers are generated from these titles.

| Prefix | Use for | Release effect |
|---|---|---|
| `feat:` | A new feature | Minor version |
| `fix:` | A bug fix | Patch version |
| `feat!:` / `fix!:` (any type with `!`) | A breaking change; explain it in the description | Major version |
| `docs:`, `test:`, `build:`, `ci:`, `chore:`, `refactor:`, `perf:` | Everything else | None on their own |

Note CVE or advisory IDs in titles and descriptions of dependency upgrades made for security.

Each pull request should:

- do one thing, with tests for the behavior it adds or changes;
- update the docs and the spec when it changes a documented decision or property;
- pass every CI check, including the CLA, title, dependency review and CodeQL checks.

## Releases (maintainers)

Releases are automated with [release-please](https://github.com/googleapis/release-please):

1. Every merge to `main` updates an open release pull request with the next version and the CHANGELOG.
2. Merging the release pull request tags the release and runs the release workflow, which builds, signs and uploads
   the artifacts to Maven Central, attaches the SBOM and records build-provenance attestations. The workflow runs in
   the protected `release` environment and needs a maintainer's approval.
3. The workflow waits until Central has validated the upload, then Central publishes it automatically. Publishing can
   take a while after the workflow finishes; check progress on the [Central Portal](https://central.sonatype.com).

**Milestones (2.0.0-M1, M2, ...).** release-please doesn't derive milestone numbers from commits, so the next
milestone is set in `release-please-config.json` (`release-as`). After a milestone is released, change `release-as`
to the next milestone in the same pull request that starts the next phase. Before the GA release, set it to `2.0.0`
and `prerelease` to `false`; after GA, remove `release-as`.

**Snapshots.** Every merge to `main` publishes `<next>-SNAPSHOT` to the Central snapshot repository. A maintainer can
publish a pull request build as `<next>-pr<number>-SNAPSHOT` by adding the `publish-snapshot` label; this works only
for branches in this repository, never for forks. Snapshots are unsigned and are removed by Central after 90 days.
