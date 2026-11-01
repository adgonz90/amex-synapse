# P0 build hardening: follow-ups

Findings deferred during the P0 reviews (plan: [2026-11-01-p0-build-hardening.md](2026-11-01-p0-build-hardening.md)).
The final whole-branch review triaged every one as not blocking. Items that were later fixed, or that maintainer
decisions made moot (Dependabot on `develop`, the v1 cleanup), are left out.

## Check on the first GitHub runs
These can only be confirmed on GitHub. Check them during the fork demonstrations (BUILD.md §15, P0 remaining scope).

- [ ] **Mockito agent path quoting on Linux.** The root `pom.xml` quotes the `-javaagent` path (the local repo path
  contains a space). The `tests-with-mockito` invoker project asserts the argument, so the first CI run on ubuntu
  confirms it.
- [ ] **`workflow_run.path` in a live event.** `snapshot-publish.yml` and `snapshot-check.sh` require
  `.github/workflows/snapshot-build.yml`. If a live payload uses another shape, the job skips (fails closed).
- [ ] **Skipped Snapshot build runs.** When the build workflow's only job is skipped (an unlabelled same-repo PR),
  check whether GitHub reports `success`. If it does, the publish job enters the `snapshots` environment and stages
  nothing, which only adds deployment-log noise.
- [ ] **Dependency submission.** `ci.yml`'s `dependency-graph` job has no `with:` block: it uses the default token and
  the runner's `mvn`, not the wrapper. Check the first push to `main`.
- [ ] **release-please's release PR base** when `target-branch` isn't set (expected: the default branch, `main`).
- [ ] **Nightly failure label.** `nightly.yml`'s `report-failure` runs `gh label create nightly-failure --force` with
  `GITHUB_TOKEN`. Confirm on a deliberately failing `workflow_dispatch` run. A transient failure of that command
  currently goes unreported.
- [ ] **Tag ruleset bypass for release-please.** GitHub's docs list GitHub Apps as bypass actors but don't confirm the
  built-in GitHub Actions identity. Recommended: a GitHub App behind `RELEASE_PLEASE_TOKEN` (minted with
  `actions/create-github-app-token`), added to the `v*` ruleset's bypass list.

## Publishing scripts
- [ ] `.github/scripts/central-publish.sh`: the `maven-metadata.xml*` glob also accepts names like
  `maven-metadata.xmlfoo.jar` inside our own version directory. Match the exact names
  (`maven-metadata\.xml(\.md5|\.sha1|\.sha256|\.sha512)?`).
- [ ] `central-publish.sh` `write_checksums`: `printf '%s' "$(md5sum … | cut …)"` masks a failing `md5sum`/`sha1sum`
  (an empty checksum file is written). Compute into a variable and fail when it's empty.
- [ ] `central-publish.sh`: curl has no `--connect-timeout`/`--max-time`, and a persistent 4xx on the status call is
  retried until `PUBLISH_TIMEOUT_SECONDS`. Add timeouts and fail fast on 4xx.
- [ ] `test-central-publish.sh`: no cases for an upload failure, a malformed deployment id, a status error followed by
  recovery, or a failed snapshot PUT.
- [ ] `test-central-publish.sh` case 9 (real gpg signing) failed once locally, then passed in later runs; the cause is
  unknown. Watch for it in CI.
- [ ] `snapshot-publish.yml`: the staged files are validated in the same step that holds the `CENTRAL_*` secrets. As
  defence in depth, run `central-publish.sh validate` in the secret-free check step first.
- [ ] `release.yml` `publish` job: `contents: write` is granted job-wide but only the SBOM attach needs it.
- [ ] `release.yml`: a `workflow_dispatch` re-run of an old tag uses `main`'s script against that tag's staging layout.
  Fine while the Maven staging layout is stable; revisit if it changes.
- [ ] `snapshot-build.yml` (HEAD_SHA comment): say that a `pull_request` build is of the merge ref, while HEAD_SHA
  records the PR head.

## Build rules and invoker tests
- [ ] The invoker projects copy the root `.mvn/jvm.config` (needed for Error Prone on JDK 25), so the copies can drift
  from it. Add a "mirror the root file" comment, or copy it during invoker setup.
- [ ] `coverage-no-tests/verify.groovy` reads one `build.log` for all builds; builds after the first are checked only
  through `invoker.buildResult.N`.
- [ ] The `tests-with-mockito` and `tests-without-mockito` fixtures duplicate `jvm.config` and the `Greeter` classes
  (acceptable for isolated fixtures).
- [ ] antrun logs "clashes with an existing Maven property, SKIPPING" in every module (cosmetic).
- [ ] `synapse.mockitoAgentArg` exists only after `process-test-classes`; a direct `./mvnw surefire:test` leaves the
  literal `@{synapse.mockitoAgentArg}` in the argLine. Same limitation as before this change. Add a comment.
- [ ] No invoker case proves that `synapse.enforcer.allowTestLibraries=true` still bans `junit:junit` (guards the skip
  staying on the right enforcer execution).
- [ ] The test-scope ban is exercised only with `junit-jupiter`; the mockito, assertj and `spring-boot-starter-test`
  patterns have no direct case.

## CI and dependencies
- [ ] `.github/requirements/diff-cover.txt`: the header lacks the exact regeneration command
  (`uvx --python 3.12 --from pip-tools pip-compile --generate-hashes -o .github/requirements/diff-cover.txt .github/requirements/diff-cover.in`).
- [ ] `.github/dependabot.yml` (pip entry): the comment says Dependabot updates the hashes in the lock; that's
  unverified. Soften it to "regenerate the lock if the hashes aren't updated".
- [ ] `ci.yml` and `nightly.yml`: the two-JDK setup block is duplicated (style).
- [ ] `scorecard.yml`: the cron has no comment; revisit `fail_on_publish_error` when the action is bumped past v2.5.0.

## Docs
- [ ] `docs/tools/component-version.mjs` `projectVersion()`: finds the first `<version>` after `</parent>` with
  `indexOf`, ignores XML comments and has no test. Add tests (a parent block, a dependency `<version>`, CRLF).
- [ ] `.github/REPOSITORY_SETUP.md`: say why the label-gated **Snapshot build** isn't a required check.
- [ ] `snapshot-publish.yml`: one comment line runs past the 120-column wrap.
