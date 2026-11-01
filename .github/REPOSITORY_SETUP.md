# Repository setup (maintainers)

The workflows in `.github/workflows` depend on settings that live in the GitHub repository, not in files. Do these
once per repository: first on a fork for the dry run, then on `americanexpress/synapse` once `main` exists there
(the upstream steps probably need the American Express org admins).

Everything here is in the repository's **Settings** pages unless noted.

## Checklist

- [ ] **Make `main` the default branch.** *General → Default branch.* Both `americanexpress/synapse` and the fork
  still default to `develop`. Until this changes, these don't work as intended:
  - **Snapshot publishing:** `snapshot-publish.yml` is a `workflow_run` workflow, which GitHub only starts when the
    workflow file is on the default branch.
  - **Scheduled runs:** `nightly.yml`, `codeql.yml`, `scorecard.yml` and `osv-scanner.yml`'s full scan run on a
    `schedule`, which only runs on the default branch.
  - **Manual runs:** `workflow_dispatch` (re-running `release.yml`, `nightly.yml`, `docs.yml`, `osv-scanner.yml` by
    hand) only offers workflows whose file is on the default branch. Scorecard's `branch_protection_rule` trigger has
    the same rule.
  - **Dependabot:** reads `.github/dependabot.yml` from the default branch only, and security updates always target
    the default branch.
  - **release-please:** with no `target-branch` input, `release-please-action` loads its config and manifest from the
    repository's default branch (seen in the action's `src/index.ts`; how it picks the release pull request's base in
    that case wasn't checked). Expect it to need `main` as the default branch, or a `target-branch: main` input.

  Sources: GitHub's [events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
  (`schedule`, `workflow_run`, `workflow_dispatch`, `branch_protection_rule`), the
  [Dependabot options reference](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference)
  (`target-branch`), and the `release-please-action` source.
  Not affected: the release and snapshot workflows check out `refs/heads/main` explicitly, not the default branch.

- [ ] **Squash merging only.** *General → Pull Requests:* allow squash merging; turn off merge commits and rebase
  merging. Set the default squash commit message to the pull request title.
  Why: the PR title becomes the commit on `main` that release-please reads (`pr-title.yml` checks the format).

- [ ] **`release` environment.** *Environments → New environment →* `release`.
  - Required reviewers: the maintainers (a release waits for one approval), with **Prevent self-review** on, so
    whoever started the run can't approve it.
  - Deployment branches and tags: `main` only (*Selected branches and tags →* `main`; no tag rules).
    release-please runs the release from `main`, and a manual re-run of **Release** is dispatched from `main` with
    the tag as its `tag` input; the workflow checks the tag out itself.
  - Environment secrets:

    | Secret | Value |
    |---|---|
    | `CENTRAL_USERNAME` | Central Portal user-token username |
    | `CENTRAL_TOKEN` | Central Portal user-token password |
    | `GPG_PRIVATE_KEY` | ASCII-armored private signing key (`gpg --armor --export-secret-keys <key-id>`) |
    | `GPG_PASSPHRASE` | Passphrase for that key |

  Why: only the `publish` job of `release.yml` uses this environment, so the signing key and publishing token are
  visible to that job alone, and only after approval. The release itself is built and tested by the `build` job,
  which has no environment and no secrets; `publish` only signs, uploads and attests the files it staged (with
  `.github/scripts/central-publish.sh`, never Maven). The values are the same credentials v1 uses today (CircleCI
  contexts `maven_central_credentials` and `code_signing_credentials`).

- [ ] **`snapshots` environment.** *Environments → New environment →* `snapshots`. No required reviewers.
  Deployment branches: `main` only (*Selected branches and tags →* `main`). Environment secrets: `CENTRAL_USERNAME`
  and `CENTRAL_TOKEN` (same values as above). Never add the GPG secrets here, and don't add the Central token as a
  repository secret.
  Why: `snapshot-build.yml` builds snapshots with no environment and no secrets, for pushes to `main` and for
  same-repository PRs carrying the `publish-snapshot` label. `snapshot-publish.yml` is a `workflow_run` job that
  always runs from `main` and is the only job in this environment. It uploads the prebuilt files without running
  Maven or any PR code. Until the secrets exist it checks the staged files, prints what it would upload and skips
  publishing with a notice.
  Note: nothing is published until `main` is the default branch (first item).

- [ ] **SNAPSHOTs enabled on the Central namespace.** On [central.sonatype.com](https://central.sonatype.com),
  *View Namespaces →* `io.americanexpress.synapse` *→ dropdown → Enable SNAPSHOTs → Confirm*. The namespace then shows
  a "SNAPSHOTs enabled" badge.
  Why: Central enables `-SNAPSHOT` publishing per namespace
  ([Publishing -SNAPSHOT releases](https://central.sonatype.org/publish/publish-portal-snapshots/)). Expect the
  publish job's uploads to be refused until this is done (not yet observed).

- [ ] **`publish-snapshot` label.** *Issues → Labels → New label* (not under Settings).
  Why: adding it to a pull request from a branch in this repository publishes `<version>-pr<number>-SNAPSHOT`.

- **`nightly-failure` label: nothing to do.** The nightly workflow opens one issue with this label when a nightly job
  fails, and comments on it while it stays open. It creates the label itself before reporting (with `--force`, which
  also resets the label's colour and description each time).

- [ ] **GitHub Pages.** *Pages → Build and deployment → Source:* GitHub Actions.
  Why: `docs.yml` publishes the Antora site from `main`. The workflow creates the `github-pages` environment itself.

- [ ] **`release-please` environment.** *Environments → New environment →* `release-please`, deployment branches
  limited to `main`. The release-please job always runs in it; nothing else is required.
- [ ] **`RELEASE_PLEASE_TOKEN` (optional).** A GitHub App installation token or a fine-grained personal access token
  with *Contents* and *Pull requests* read/write on this repository, saved as a secret of the `release-please`
  environment.
  Why: GitHub doesn't start CI on pull requests opened with the default `GITHUB_TOKEN`, so without it the release
  pull request shows no CI checks. If branch protection requires checks, the release PR can't merge without this.

## Rulesets (proposed; not yet agreed)

Both rulesets are proposals until the maintainers agree on them. *Rules → Rulesets → New ruleset.*

- [ ] **Branch ruleset for `main`.** Target: the default branch (`main`). Rules:
  - Require a pull request before merging.
  - Require these status checks to pass:
    - `Verify (JDK 25)`
    - `Smoke (tests on JDK 21)`
    - `Conventional Commits title`
    - `Dependency review`
    - `Analyze (java-kotlin)`
    - `Build site`
    - `actionlint`, `zizmor` and `Publish script tests` (see the note below)
    - `New vulnerabilities in this PR`
    - the CLA check (reported by the CLA assistant, not by a workflow in this repository: pick the name it shows on a
      pull request)
  - Block force pushes.
  - Restrict deletions.

  The names are the job `name:` keys in `.github/workflows`, so renaming a job there needs the same change here. Only
  jobs that run on pull requests are listed. The three Workflow lint jobs only run when a pull request changes
  `.github/**`, and a required check that never runs blocks the merge, so require them only after the path filter is
  dropped from `workflow-lint.yml` (or leave them out).

  Why: every change to `main` is reviewed and gated, and `main`'s history (which the release workflow checks tags
  against) can't be rewritten.

- [ ] **Tag ruleset for `v*`.** Target: tags matching `v*`. Rules: restrict creations, restrict updates and restrict
  deletions. Bypass list: the maintainers, plus whatever identity release-please creates the release tags with.
  release-please creates every `v*` tag when a release pull request is merged, so without that bypass it can't
  tag a release:
  - with the default `GITHUB_TOKEN`: GitHub Actions (the `github-actions` app);
  - with `RELEASE_PLEASE_TOKEN`: the GitHub App or user behind that token.

  Not yet checked: whether GitHub Actions can be added to a ruleset's bypass list. If it can't, `RELEASE_PLEASE_TOKEN`
  becomes required.

  Why: a release tag selects what the release workflow builds and publishes. The workflow also refuses a tag whose
  commit isn't on `main`, and a tag moved after the build was checked, but only maintainers and release-please should
  be able to create, move or delete one.

## Checking the setup

| Setting | How to confirm it works |
|---|---|
| Squash merging | Merge a small PR; the commit on `main` has the PR title as its message. |
| `release` environment and secrets | The release-please dry run on the fork: merging the release PR starts **Release**. **Build** runs without approval and uploads `release-staging`; **Publish** waits for approval (the person who started the run can't approve it), then signs and uploads to Central. |
| Branch ruleset | A push straight to `main`, or a force push, is rejected; a PR can't merge until the listed checks pass. |
| Tag ruleset | `git push origin v0.0.0-test` from a non-maintainer account is rejected. |
| `snapshots` environment and Central SNAPSHOTs | The next push to `main` runs **Snapshot build**, then **Snapshot publish** without the "secrets are not configured" notice, and `2.0.0-SNAPSHOT` appears at `https://central.sonatype.com/repository/maven-snapshots/io/americanexpress/synapse/synapse-core/`. *Environments → snapshots* lists the deployment from `main`. |
| `publish-snapshot` label | Add it to a same-repository PR: **Snapshot build** builds `2.0.0-pr<number>-SNAPSHOT` and **Snapshot publish** publishes it. On a fork PR both skip their jobs, and the jars are attached to the **CI** run instead. |
| Pages | **Docs** on `main` deploys and prints the site URL. |
| `RELEASE_PLEASE_TOKEN` | The release PR shows CI checks. |
