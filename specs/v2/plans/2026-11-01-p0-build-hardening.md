# P0 build hardening: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish Phase 0 of Synapse 2: close the remaining build-gate gaps, split release and snapshot publishing
into secret-free build jobs plus environment publish jobs, complete the CI matrix and supply-chain workflows, and
retire v1's dead `main` workflow.

**Architecture:** Maven gate fixes in the root `pom.xml` are each proven by an invoker project in
`tests/synapse-build-rules-tests`. Each publishing workflow is split in two. The build job deploys to a local staging
directory (`target/staging-deploy`) and uploads it as an artifact. A publish job in a protected environment then
signs and uploads those files to Central with one script, `.github/scripts/central-publish.sh`, which never runs
Maven or PR code.

**Tech Stack:** Maven 3.9.16 (wrapper), JDK 25 (build) / JDK 21 (tests), maven-invoker-plugin, maven-antrun-plugin,
GitHub Actions, bash, GnuPG, curl, Central Publisher API, Antora 3.2 + Collector, Node 24 (`node:test`).

**Spec:** [specs/v2/BUILD.md](../BUILD.md) §14 (Quality gates and CI) and §15 *P0: Repository and build*. The reasons
behind the decisions are in [DESIGN.md](../DESIGN.md).

## Global Constraints
- Build with JDK 25 (`~/.jdks/jdk-25.0.5+8`), `--release 21`; JDK 21 test runs use `-Djvm=$HOME/.jdks/jdk-21.0.12.1+1/bin/java`.
- Always use `./mvnw`, never a global `mvn`.
- "**Builds run without secrets.** A release or snapshot build and its tests run in a job with no secrets and upload
  their artifacts; a separate job in a protected environment only signs (release), publishes and attests them."
- Every GitHub Action is pinned to a full commit SHA with the version in a comment. Find the SHA with
  `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object.sha` (dereference annotated tags).
- Every workflow and job declares least-privilege `permissions:`, with a comment on each grant.
- `actionlint` (`C:/Users/Tony/.tools/actionlint-1.7.12/actionlint.exe`) and `uvx zizmor@1.31.1 --persona auditor .github/workflows`
  must both be clean after every task that touches `.github/`.
- Conventional Commits; one commit per task; commit messages end with the `Co-Authored-By` line from the session.
- **Never push** (fork or upstream) until Tony says so. Task 11 waits for that.
- Work on branch `feature/v2-p0-build-hardening` from `main`. Task 10 works on its own branch from `develop`.

## Review Focus
1. A developer running `./mvnw verify -DskipTests` (or `-Dmaven.test.skip=true`) must not trip the new
   coverage-data rule. Test in Task 1.
2. A fork PR that edits `snapshot-build.yml` so it always uploads artifacts must not get them published: the publish
   workflow re-checks the triggering run's repository, event and label itself. Test in Task 6 (script checks) and by
   review of the workflow condition.
3. A staged directory containing files outside `io/americanexpress/synapse/`, or a version that doesn't match the
   expected one, must be refused before any upload. Test in Task 5.
4. A release tag whose commit isn't on `main` fails the build job before anything is built. Test in Task 5 (script).
5. Central answers `FAILED`, or never leaves `VALIDATING`: the publish job fails, prints Central's errors, and exits
   within its timeout. Test in Task 5 (stubbed `curl`).

---

### Task 0: Branch
- [ ] `git switch -c feature/v2-p0-build-hardening main`, then `git status` shows a clean tree.

### Task 1: Coverage gate fails when a module has main code but no coverage data

**Files:**
- Modify: `pom.xml` (properties; `java-module` profile; new `skip-tests-coverage` profile)
- Create: `tests/synapse-build-rules-tests/src/it/coverage-no-tests/{pom.xml,invoker.properties,verify.groovy,src/main/java/example/Uncovered.java}`
- Modify: `.github/workflows/ci.yml` (changed-lines step)

**Interfaces:**
- Produces: property `jacoco.skip` (default `false`); enforcer execution id `require-coverage-data`, message
  `No test coverage data for a module with main sources: add tests (see the Quality gates section of specs/v2/BUILD.md).`

- [ ] **Step 1: Write the invoker project.** A `jar` module inheriting `synapse` `@project.version@` (same parent
  block as `banned-junit4-compile`), with one class `example.Uncovered` and no tests. `invoker.properties`:
  ```properties
  invoker.goals.1 = verify -Dspotless.check.skip=true -Dspotless.apply.skip=true
  invoker.buildResult.1 = failure
  invoker.goals.2 = verify -DskipTests -Dspotless.check.skip=true -Dspotless.apply.skip=true
  invoker.buildResult.2 = success
  ```
  `verify.groovy` asserts `build.log` contains the message above.
- [ ] **Step 2: Run** `./mvnw -pl tests/synapse-build-rules-tests -am verify`. Expected: FAIL, because build 1
  succeeds today (JaCoCo skips the check when there's no data).
- [ ] **Step 3: Implement.**
  - In `java-module`, add a `maven-enforcer-plugin` execution `require-coverage-data` (phase `verify`, rule
    `requireFilesExist` on `${project.build.directory}/jacoco-merged.exec`, `<skip>${jacoco.skip}</skip>`), declared
    before the JaCoCo plugin so it runs ahead of `check`.
  - Add `<jacoco.skip>false</jacoco.skip>` to the properties.
  - Add a profile `skip-tests-coverage`, activated by property `skipTests` or `maven.test.skip` (one profile each,
    because activation takes a single property), that sets `jacoco.skip=true`.
- [ ] **Step 4: Run** the same command. Expected: PASS (both invoker builds match their expected results), and the
  `banned-junit4-compile` project still passes.
- [ ] **Step 5: Fix diff-cover's no-report bypass in `ci.yml`.** When `roots` is non-empty and `reports` is empty,
  print `::error::No JaCoCo reports found for modules with main sources.` and `exit 1`. Keep the `exit 0` only when
  there are no `src/main/java` roots at all.
- [ ] **Step 6: Run** `./mvnw verify` (JDK 25), plus actionlint and zizmor. Expected: all pass.
- [ ] **Step 7: Commit** `build: fail the coverage gate when a module has no coverage data`.

### Task 2: Mockito agent only for modules that have Mockito

**Files:**
- Modify: `pom.xml` (surefire/failsafe `argLine`; `java-module` profile; antrun version property and plugin management)
- Create: `tests/synapse-build-rules-tests/src/it/tests-without-mockito/` and `tests/synapse-build-rules-tests/src/it/tests-with-mockito/`

**Interfaces:**
- Produces: property `synapse.mockitoAgentArg` (`-javaagent:<mockito-core jar>` or empty), set during the build and
  never declared in the POM.

- [ ] **Step 1: Write the invoker projects.** Both are `jar` modules with one main class and a JUnit 5 test covering
  it.
  - `tests-without-mockito`: depends only on `org.junit.jupiter:junit-jupiter` (test scope), with goal `verify`,
    expected result `success`.
  - `tests-with-mockito`: adds `org.mockito:mockito-core` (test scope). Its test asserts
    `ManagementFactory.getRuntimeMXBean().getInputArguments()` has an element matching
    `-javaagent:.*mockito-core-.*\.jar`.
- [ ] **Step 2: Run** `./mvnw -pl tests/synapse-build-rules-tests -am verify`. Expected: FAIL. `tests-without-mockito`
  breaks because the JVM gets the literal `-javaagent:${org.mockito:mockito-core:jar}`.
- [ ] **Step 3: Implement.**
  - Pin `maven-antrun-plugin` `3.2.0` (property and plugin management).
  - In `java-module`, add an antrun `run` execution at phase `process-test-classes`, with
    `<exportAntProperties>true</exportAntProperties>` and this target:
    ```xml
    <condition property="synapse.mockitoAgentArg" value="-javaagent:${org.mockito:mockito-core:jar}" else="">
        <available file="${org.mockito:mockito-core:jar}"/>
    </condition>
    ```
  - Change both `argLine`s to `@{argLine} @{synapse.mockitoAgentArg} -Xshare:off`.
  - Keep `dependency:properties` as is.
- [ ] **Step 4: Run** the same command. Expected: PASS for all invoker projects. `./mvnw -pl core/synapse-core verify`
  still passes.
- [ ] **Step 5: Commit** `build: attach the Mockito agent only when the module uses Mockito`.

### Task 3: Test-library ban with an opt-out for `synapse-test`

**Files:**
- Modify: `pom.xml` (split the `bannedDependencies` rule)
- Create: `tests/synapse-build-rules-tests/src/it/test-library-compile-banned/` and `tests/synapse-build-rules-tests/src/it/test-library-compile-allowed/`

**Interfaces:**
- Produces: property `synapse.enforcer.allowTestLibraries` (default `false`); enforcer execution `enforce-test-scope`,
  message `Test libraries must stay in test scope (only synapse-test may export them): see the Quality gates section of specs/v2/BUILD.md.`
  `synapse-test` (P1) sets the property to `true` in its POM.

- [ ] **Step 1: Write the invoker projects.** Both are `pom` modules with `org.junit.jupiter:junit-jupiter` in
  compile scope and goal `validate -Dspotless.check.skip=true`.
  - `-banned`: expected `failure`; `verify.groovy` asserts the message above and `org.junit.jupiter:junit-jupiter`.
  - `-allowed`: sets `<synapse.enforcer.allowTestLibraries>true</synapse.enforcer.allowTestLibraries>`; expected
    `success`.
- [ ] **Step 2: Run** the invoker build. Expected: FAIL (`-allowed` is rejected by today's single rule).
- [ ] **Step 3: Implement.** Move the six test-library `<exclude>`s (junit-jupiter, mockito, assertj,
  spring-boot-starter-test, compile and runtime) into a new execution `enforce-test-scope`, with
  `<skip>${synapse.enforcer.allowTestLibraries}</skip>`. The logging and JUnit 4 bans stay in `enforce`.
- [ ] **Step 4: Run** the invoker build. Expected: PASS, including `banned-junit4-compile`.
- [ ] **Step 5: Commit** `build: let synapse-test opt out of the test-scope ban`.

### Task 4: Pin build tools (help plugin, Maven distribution, diff-cover)

**Files:**
- Modify: `pom.xml` (`maven-help-plugin.version` `3.5.2`, plus plugin management)
- Modify: `.mvn/wrapper/maven-wrapper.properties` (`distributionSha256Sum`)
- Create: `.github/requirements/diff-cover.in` (`diff-cover==11.0.0`) and `.github/requirements/diff-cover.txt` (hash-locked)
- Modify: `.github/workflows/ci.yml`, `.github/dependabot.yml` (add a `pip` entry for `/.github/requirements`)

- [ ] **Step 1: Pin the help plugin.** Then run
  `./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout`. Expected: `2.0.0-SNAPSHOT`, and the debug log
  (`-X`) resolves `maven-help-plugin:3.5.2`.
- [ ] **Step 2: Checksum the Maven distribution.**
  - Download `apache-maven-3.9.16-bin.zip`.
  - Verify its SHA-512 against Central's `.sha512`. The expected value starts with `ed41650d42485cfc…`.
  - Set `distributionSha256Sum=<sha256 of the zip>`.
  - Prove it: with a temporary `MAVEN_USER_HOME`, `./mvnw -v` succeeds. With one hex digit changed it fails with a
    checksum error. Restore the correct value.
- [ ] **Step 3: Lock diff-cover.** Run
  `uvx --from pip-tools pip-compile --generate-hashes --python-version 3.12 -o .github/requirements/diff-cover.txt .github/requirements/diff-cover.in`.
  This may need a permission rule; if it's blocked, stop and report it.
  - In `ci.yml`, replace `pipx run diff-cover==11.0.0` with:
    - a step running `python -m pip install --require-hashes --no-deps -r .github/requirements/diff-cover.txt`
    - then `diff-cover …` with the same arguments.
- [ ] **Step 4: Run** actionlint, zizmor and `./mvnw verify`. Expected: clean, and the build passes.
- [ ] **Step 5: Commit** `build: pin the help plugin, Maven distribution checksum and diff-cover dependencies`.

### Task 5: Release: secret-free build job, environment publish job, shared publish script

**Files:**
- Create: `.github/scripts/central-publish.sh`, `.github/scripts/test-central-publish.sh`
- Modify: `pom.xml` (`synapse-release` and `synapse-snapshot` profiles; remove gpg and central-publishing plugins and their version properties)
- Modify: `tests/synapse-build-rules-tests/pom.xml` (drop `skipPublishing`)
- Modify: `.github/workflows/release.yml`, `.github/workflows/release-please.yml`, `.github/REPOSITORY_SETUP.md`

**Interfaces:**
- Produces: `central-publish.sh <mode> <staging-dir>` with these modes:
  - `check-tag <tag>`: the tag format check plus the "commit is on `origin/main`" check.
  - `release`: sign, checksum, bundle, upload and poll.
  - `snapshot`: validate, then PUT. Used by Task 6.
- Every mode starts with `validate <staging-dir> <expected-version>`: every file is under
  `io/americanexpress/synapse/<artifactId>/<expected-version>/`, and nothing else is in the directory.
- Environment for the script:
  - inputs: `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` (release only)
  - `CENTRAL_API` (default `https://central.sonatype.com/api/v1/publisher`)
  - `CENTRAL_SNAPSHOTS` (default `https://central.sonatype.com/repository/maven-snapshots/`)
  - `PUBLISH_TIMEOUT_SECONDS` (default `1800`)
  - `DRY_RUN=1`: build everything but make no HTTP calls.
- Build command used by both workflows:
  `./mvnw -B -ntp -P<profile> deploy -DaltDeploymentRepository=staging::file:${GITHUB_WORKSPACE}/target/staging-deploy`

- [ ] **Step 1: Write `test-central-publish.sh`.** It's a plain bash test runner with one function per case. A fake
  `curl` and `gpg` on `PATH` log their arguments and return canned responses. Cases:
  1. `validate` accepts a staged tree for `2.0.0-M1`.
  2. `validate` rejects a file under `org/evil/` and a `2.0.0-M2` directory, and uploads nothing.
  3. `release` with `DRY_RUN=1` produces a bundle zip whose entries:
     - include `<artifact>.asc`, `.md5` and `.sha1` for every `.jar` and `.pom`
     - contain no `maven-metadata.xml*`
  4. `release` against a fake `curl` whose status goes `PENDING → VALIDATING → PUBLISHING` exits 0.
     - Upload request: `POST …/upload?publishingType=AUTOMATIC&name=synapse-<version>` with
       `Authorization: Bearer base64(user:token)`.
  5. Status `FAILED` with `{"errors":…}` exits 1 and prints the errors.
  6. Status stuck at `VALIDATING` with `PUBLISH_TIMEOUT_SECONDS=2` exits 1 with `Timed out`.
  7. `check-tag v2.0.0-M1` fails for a commit not reachable from `origin/main`, using a temporary git repo.
  8. `snapshot` PUTs version-level files only (artifacts, checksums, then the version-level `maven-metadata.xml`
     last), never the artifactId-level `maven-metadata.xml`, with basic auth.
- [ ] **Step 2: Run** `bash .github/scripts/test-central-publish.sh`. Expected: FAIL (the script doesn't exist).
- [ ] **Step 3: Implement `central-publish.sh`** (`set -euo pipefail`).
  - **Signing:** in a throwaway `GNUPGHOME` under `RUNNER_TEMP`, deleted on exit, use
    `gpg --batch --pinentry-mode loopback --passphrase-fd 0 --armor --detach-sign`.
  - **Checksums:** `md5sum`/`sha1sum` for every file that lacks them.
  - **Bundle:** zip relative to the staging root.
  - **Status polling:** POST to `status?id=` every 10 s. Success is `PUBLISHING` or `PUBLISHED`; failure is `FAILED`
    or the timeout.
  - **Snapshot upload:** `curl --fail-with-body -u user:token -T file <CENTRAL_SNAPSHOTS><path>`.
- [ ] **Step 4: Run** the test script. Expected: all 8 cases PASS.
- [ ] **Step 5: Change the release profiles in `pom.xml`.**
  - `synapse-release` keeps the sources, javadoc and CycloneDX `makeAggregateBom`, and drops gpg and
    `central-publishing-maven-plugin`.
  - `synapse-snapshot` keeps sources only.
  - Delete `maven-gpg-plugin.version` and `central-publishing-maven-plugin.version`, and their plugin management.
  - Run `./mvnw -Psynapse-release deploy -DaltDeploymentRepository=staging::file:$PWD/target/staging-deploy`. Expected:
    - `target/staging-deploy/io/americanexpress/synapse/` holds `synapse-bom`, `synapse-parent`, `synapse` and
      `synapse-core` (jar, sources, javadoc, pom)
    - no `synapse-build-rules-tests`
    - `target/bom.json` exists
  - Then run the script with `DRY_RUN=1 release` on that directory, using a throwaway test key generated in the test
    script. Expected: a bundle that passes case 3's checks.
- [ ] **Step 6: Rewrite `release.yml`.**
  - **Job `build`:** no environment and `contents: read`. It checks out the tag with `fetch-depth: 0` and runs
    `central-publish.sh check-tag`. Then it checks that the POM version matches the tag, runs the build command with
    `-Psynapse-release`, and uploads the `release-staging` artifact (the staging dir plus `target/bom.json` and
    `target/bom.xml`, 7-day retention).
  - **Job `publish`:**
    - It needs `build` and runs in environment `release`.
    - It declares the permissions that release.yml's single job has today.
    - Steps: download `release-staging`, check out `.github/scripts` from the tag (sparse), run
      `central-publish.sh release` with the secrets, then attest build provenance on the staged jars and
      `bom.json`, then attach the SBOM.
  - Keep the `workflow_call` secrets and the `workflow_dispatch` input.
  - In `release-please.yml`, only the comment changes.
- [ ] **Step 7: Update `REPOSITORY_SETUP.md`.**
  - **`release` environment:** add "Prevent self-review"; deployment branches stay `main` and `v*` tags.
  - **New section "Rulesets":**
    - a branch ruleset for `main`: PR required, the status checks listed in Task 9, no force push, no deletion
    - a tag ruleset on `v*` where only maintainers may create, update or delete
    - mark both "proposed; not yet agreed" until Tony confirms
- [ ] **Step 8: Run** actionlint, zizmor, the test script and `./mvnw verify`. Expected: clean, and everything passes.
- [ ] **Step 9: Commit** `ci: build releases without secrets and publish them from the release environment`.

### Task 6: Snapshots: secret-free build, `workflow_run` publish from `main`

**Files:**
- Replace: `.github/workflows/snapshot.yml` with `.github/workflows/snapshot-build.yml` and `.github/workflows/snapshot-publish.yml`
- Modify: `.github/REPOSITORY_SETUP.md` (the `snapshots` environment is limited to `main`; enable SNAPSHOTs on the Central namespace)

**Interfaces:**
- Consumes: Task 5's `central-publish.sh snapshot` and the build command with `-Psynapse-snapshot`.
- Produces: the artifact `snapshot-staging`, which holds the staging dir plus `snapshot.env` (`VERSION=…`,
  `PR_NUMBER=…` or empty, `HEAD_SHA=…`).

- [ ] **Step 1: Write `snapshot-build.yml`.**
  - Name: `Snapshot build`. It keeps today's triggers and `if`.
  - Permissions: `contents: read`, with no environment and no secrets.
  - Steps:
    - Stamp the PR version (today's step).
    - Read the version. If it doesn't end in `-SNAPSHOT`, write `::notice::Not a snapshot version (<v>); nothing to publish.`
      and end successfully, uploading nothing.
    - Otherwise build and upload `snapshot-staging` (3-day retention).
- [ ] **Step 2: Write `snapshot-publish.yml`.**
  - Trigger: `on: workflow_run: workflows: [Snapshot build], types: [completed]`.
  - The job runs only when the triggering run succeeded and its `head_repository.full_name == github.repository`.
    Then one of these must hold:
    - `event == 'push'` and `head_branch == 'main'`
    - `event == 'pull_request'`, and a `gh api` check confirms that PR `PR_NUMBER` is open, belongs to this
      repository and carries `publish-snapshot`
  - Environment `snapshots`. Permissions: `actions: read` (download the other run's artifact) and `contents: read`.
  - Check out `main`'s `.github/scripts` (sparse).
  - Download `snapshot-staging` with `run-id: ${{ github.event.workflow_run.id }}`.
  - Check `VERSION` against `^2\.[0-9]+\.[0-9]+(-pr${PR_NUMBER})?-SNAPSHOT$`.
  - Run `central-publish.sh snapshot`. If there are no Central secrets, keep today's skip-with-notice behaviour.
  - Never run `./mvnw` and never check out the PR ref.
- [ ] **Step 3: Delete `snapshot.yml`.** Then run actionlint and zizmor. Expected: clean. zizmor's
  `dangerous-triggers` finding on `workflow_run` gets a same-line ignore whose comment explains that no PR code runs
  and the artifact is validated.
- [ ] **Step 4: Run** the Task 5 test script again (case 8 covers the upload shape). Expected: PASS.
- [ ] **Step 5: Commit** `ci: publish snapshots from prebuilt artifacts in a workflow_run job`.

### Task 7: CI matrix, JDK 21 smoke tests, Javadoc, nightly full run with failure issues

**Files:**
- Modify: `pom.xml` (failsafe `<groups>${synapse.it.groups}</groups>`, property default empty)
- Modify: `core/synapse-core/src/test/java/io/americanexpress/synapse/core/autoconfigure/SynapseCoreAutoConfigurationIT.java` (`@Tag("smoke")`)
- Modify: `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`, `AGENTS.md` (smoke rule, `-DskipTests` note)

**Interfaces:**
- Produces: the JUnit tag `smoke` and property `synapse.it.groups`. These job names are what Task 9 lists:
  - `Verify (JDK 25)`
  - `Smoke (tests on JDK 21)`
  - nightly `Full build (tests on JDK 21)`, `Mutation testing (PIT)` and `Report failure`

- [ ] **Step 1: Prove the filter.**
  - Tag the IT, then run
    `./mvnw -pl core/synapse-core verify -Dsynapse.it.groups=smoke -Djacoco.skip=true -Djvm=$HOME/.jdks/jdk-21.0.12.1+1/bin/java`.
    Expected: failsafe `Tests run: 1`.
  - With `-Dsynapse.it.groups=none`: `Tests run: 0` (allow it with `-Dfailsafe.failIfNoSpecifiedTests=false` in that
    check only).
  - With the property unset: `Tests run: 1`, so an empty value means all tests.
- [ ] **Step 2: Rewrite `ci.yml`.**
  - **Job `verify`** (`Verify (JDK 25)`) runs `./mvnw verify javadoc:javadoc`. The fork-jar upload and the
    changed-lines check move here.
  - **Job `smoke`** (`Smoke (tests on JDK 21)`) runs
    `./mvnw verify -Djvm="$JAVA_HOME_21_X64/bin/java" -Dsynapse.it.groups=smoke -Djacoco.skip=true`.
  - **Job `dependency-graph`** runs on push to `main` only, with `contents: write`.
    It uses `advanced-security/maven-dependency-submission-action` `v6.0.1`, pinned by SHA.
- [ ] **Step 3: Add jobs to `nightly.yml`.**
  - **Job `full-jdk21`:** `./mvnw verify -Djvm=…21…`.
  - **Job `report-failure`:** `needs: [full-jdk21, mutation-testing]`, `if: failure()`, `issues: write`. If an open
    issue labelled `nightly-failure` exists, it comments the run URL there. Otherwise it creates
    `Nightly build failed` with that label (`gh issue create --label nightly-failure`).
  - Add the label to REPOSITORY_SETUP's label step.
- [ ] **Step 3a: Update AGENTS.md.** Every module with integration tests tags one of them `@Tag("smoke")`. `-DskipTests`
  also skips the coverage gate.
- [ ] **Step 4: Run** actionlint, zizmor, `./mvnw verify javadoc:javadoc` (JDK 25) and the smoke command from Step 2
  locally. Expected: all pass.
- [ ] **Step 5: Commit** `ci: full build on JDK 25, smoke tests on JDK 21, nightly JDK 21 with failure issues`.

### Task 8: OpenSSF Scorecard and Dependabot `develop` entries

**Files:**
- Create: `.github/workflows/scorecard.yml`
- Modify: `.github/dependabot.yml`

- [ ] **Step 1: Write `scorecard.yml`.**
  - Triggers: push to `main`, weekly schedule, `branch_protection_rule`.
  - Top-level `permissions: read-all` is not allowed: use `{}`, then at job level `security-events: write`
    (upload SARIF), `id-token: write` (publish results) and `contents: read`. Add `actions: read` only if the action
    requires it on a private repo; comment why.
  - Uses `ossf/scorecard-action` `v2.5.0` with `publish_results: true`, then `github/codeql-action/upload-sarif` at
    the SHA `codeql.yml` already uses.
- [ ] **Step 2: Add Dependabot entries for `develop`.**
  - `package-ecosystem: maven`, `directory: /`, `target-branch: develop`, weekly, security updates grouped.
  - Comment: "Until 0.4.x end of life (6 months after 2.0.0 GA)."
- [ ] **Step 3: Run** actionlint and zizmor. Expected: clean.
- [ ] **Step 4: Commit** `ci: add OpenSSF Scorecard and Dependabot for the 0.4.x line`.

### Task 9: Antora UI bundle pinned, `prerelease` from the version, setup doc check names

**Files:**
- Create: `docs/ui/ui-bundle.zip` (vendored) and `docs/ui/README.md` (source commit, date, sha256, how to update)
- Create: `docs/tools/component-version.mjs` and `docs/tools/component-version.test.mjs`
- Modify: `docs/antora-playbook.yml`, `docs/antora.yml`, `.github/workflows/docs.yml`, `.github/REPOSITORY_SETUP.md`

**Interfaces:**
- Produces: `componentVersion(pomVersion: string): { version: '2.0', prerelease: boolean }`. It's prerelease for
  `-SNAPSHOT`, `-M<n>` and `-RC<n>`, and not for `2.0.0` or `2.0.1`. The version is `<major>.<minor>`. The script
  writes `docs/build/generated-docs/antora.yml` with those keys.

- [ ] **Step 1: Write `component-version.test.mjs`** (`node:test`) with cases:
  - `2.0.0-SNAPSHOT` → `{version:'2.0', prerelease:true}`
  - `2.0.0-M1` → prerelease
  - `2.0.0-RC2` → prerelease
  - `2.0.0` → not prerelease
  - `2.1.3` → `{version:'2.1', prerelease:false}`
  - `garbage` throws
- [ ] **Step 2: Run** `node --test docs/tools/`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement `component-version.mjs`.** It reads `<version>` from the root `pom.xml`'s project element,
  the first `<version>` after `</parent>`. Add it as a second `ext.collector.run` entry in `antora.yml`, and remove
  the hard-coded `prerelease`. This relies on Collector merging metadata from a generated `antora.yml` in the scan
  dir. If the built site doesn't reflect it (Step 5), stop and report: the fallback is a `docs.yml` step that rewrites
  `docs/antora.yml` before the build.
- [ ] **Step 4: Pin the UI.**
  - Download the current default UI bundle once into `docs/ui/ui-bundle.zip`, recording the GitLab job URL, date and
    sha256 in `docs/ui/README.md`.
  - The playbook uses `ui.bundle.url: ./ui/ui-bundle.zip` with no `snapshot`.
  - `docs.yml` runs `node --test tools/` before Antora.
- [ ] **Step 5: Run** `cd docs && node --test tools/ && npx antora antora-playbook.yml`. Expected: tests pass, the
  site builds offline from the vendored UI, and the 2.0 version is marked prerelease in the generated site (the
  version menu or `site.json` / page attributes). Change the root version to `2.0.0` temporarily and rebuild: it's no
  longer prerelease. Revert.
- [ ] **Step 6: Update REPOSITORY_SETUP's required checks** to the actual job names from Tasks 1–9:
  - `Verify (JDK 25)`
  - `Smoke (tests on JDK 21)`
  - `Conventional Commits title`
  - `dependency-review`
  - `Analyze (java-kotlin)`
  - `Build site`
  - the workflow-lint job names
  - OSV-Scanner's PR job name
  - the CLA check

  Check each name against the `name:` keys in the workflows with
  `grep -h "^\s*name:" .github/workflows/*.yml`.
- [ ] **Step 7: Run** actionlint, zizmor and `./mvnw verify`. Expected: clean.
- [ ] **Step 8: Commit** `docs: pin the Antora UI and derive prerelease from the project version`.

### Task 10: v1 maintenance on `develop` (local branch only)

**Files (on `develop`):**
- Modify: `.circleci/config.yml` (delete the `release-maven-central-build-test-deploy-workflow` block, about lines 169–187)
- Modify: `README.md` (maintenance note at the top)

- [ ] **Step 1: Branch.** Run `git switch -c chore/v1-maintenance-notice develop`, with JDK 21 per CLAUDE.md.
- [ ] **Step 2: Edit the CircleCI config.** Delete the `main`-filtered workflow and its comment lines. Then check that
  `git grep -n "\- main" .circleci/config.yml` returns nothing and that the YAML still parses
  (`python -c "import yaml,sys;yaml.safe_load(open('.circleci/config.yml'))"` or `npx yaml-lint`).
- [ ] **Step 3: Add the README note** under the title:
  > **Maintenance mode.** Synapse 0.4.x receives security fixes only and reaches end of life 6 months after
  > Synapse 2.0.0 is released. New development happens on Synapse 2 (`main` branch).
- [ ] **Step 4: Commit** `chore: retire the main-branch release workflow and announce 0.4.x maintenance`. Then
  `git switch feature/v2-p0-build-hardening`.

### Task 11: Acceptance on the fork (needs Tony's go-ahead to push)
These P0 acceptance items can only be shown on GitHub. Do none of this until Tony explicitly says to push.
- [ ] Do the fork setup from REPOSITORY_SETUP: environments, secrets, label, Pages, SNAPSHOTs enabled on the
  namespace.
- [ ] A throwaway PR containing a failing test, a formatting error and a NullAway error is rejected, then closed. A
  module without tests fails the coverage gate.
- [ ] A release-please dry run on the fork proposes `2.0.0-M1` and updates every `pom.xml` and `synapse.version`.
  Record the version it proposes afterwards.
- [ ] Snapshots:
  - A push to `main` publishes `2.0.0-SNAPSHOT`.
  - A labelled same-repo PR publishes `2.0.0-pr<N>-SNAPSHOT`.
  - A fork PR publishes nothing.
  - With the secrets removed, the publish job skips with a notice.
  - Check that `GPG_*` never appears in either build job's environment.
- [ ] Docs CI builds the site with a generated property table. `develop`'s CircleCI no longer has a `main` workflow
  (after its PR merges).

### Final check (before reporting done)
- [ ] `./mvnw clean verify` on JDK 25. The smoke command on JDK 21. `node --test docs/tools/` and the Antora build.
  actionlint and zizmor clean. `bash .github/scripts/test-central-publish.sh` passes.
- [ ] Mark the P0 items done in `specs/v2/BUILD.md` §15, and say which acceptance items still wait for Task 11.
