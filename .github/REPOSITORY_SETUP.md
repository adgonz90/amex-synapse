# Repository setup (maintainers)

The workflows in `.github/workflows` depend on settings that live in the GitHub repository, not in files. Do these
once per repository: first on a fork for the dry run, then on `americanexpress/synapse` once `main` exists there
(the upstream steps probably need the American Express org admins).

Everything here is in the repository's **Settings** pages unless noted.

## Checklist

- [ ] **Squash merging only.** *General → Pull Requests:* allow squash merging; turn off merge commits and rebase
  merging. Set the default squash commit message to the pull request title.
  Why: the PR title becomes the commit on `main` that release-please reads (`pr-title.yml` checks the format).

- [ ] **`release` environment.** *Environments → New environment →* `release`.
  - Required reviewers: the maintainers (a release waits for one approval).
  - Deployment branches and tags: tags matching `v*`, plus `main` (release-please runs the release from `main`).
  - Environment secrets:

    | Secret | Value |
    |---|---|
    | `CENTRAL_USERNAME` | Central Portal user-token username |
    | `CENTRAL_TOKEN` | Central Portal user-token password |
    | `GPG_PRIVATE_KEY` | ASCII-armored private signing key (`gpg --armor --export-secret-keys <key-id>`) |
    | `GPG_PASSPHRASE` | Passphrase for that key |

  Why: only `release.yml` uses this environment, so the signing key and publishing token are visible to that job
  alone, and only after approval. The values are the same credentials v1 uses today (CircleCI contexts
  `maven_central_credentials` and `code_signing_credentials`).

- [ ] **`snapshots` environment.** *Environments → New environment →* `snapshots`. No required reviewers and no
  branch restriction (the workflow itself only runs for pushes to `main` and for same-repository PRs carrying the
  `publish-snapshot` label). Environment secrets: `CENTRAL_USERNAME` and `CENTRAL_TOKEN` (same values as above).
  Never add the GPG secrets here, and don't add the Central token as a repository secret.
  Why: `snapshot.yml` publishes unsigned snapshots from this environment only. Until the secrets exist it skips with
  a notice.

- [ ] **`publish-snapshot` label.** *Issues → Labels → New label* (not under Settings).
  Why: adding it to a pull request from a branch in this repository publishes `<version>-pr<number>-SNAPSHOT`.

- [ ] **GitHub Pages.** *Pages → Build and deployment → Source:* GitHub Actions.
  Why: `docs.yml` publishes the Antora site from `main`. The workflow creates the `github-pages` environment itself.

- [ ] **`release-please` environment.** *Environments → New environment →* `release-please`, deployment branches
  limited to `main`. The release-please job always runs in it; nothing else is required.
- [ ] **`RELEASE_PLEASE_TOKEN` (optional).** A GitHub App installation token or a fine-grained personal access token
  with *Contents* and *Pull requests* read/write on this repository, saved as a secret of the `release-please`
  environment.
  Why: GitHub doesn't start CI on pull requests opened with the default `GITHUB_TOKEN`, so without it the release
  pull request shows no CI checks. If branch protection requires checks, the release PR can't merge without this.

- [ ] **Branch protection for `main` (proposed; not yet agreed).** *Rules → Rulesets:* require a pull request,
  require these status checks, and block force pushes and deletion:
  - `Verify (build JDK 25, tests on JDK 21)` and `Verify (build JDK 25, tests on JDK 25)`
  - `Conventional Commits title`
  - `dependency-review`
  - `Analyze (java-kotlin)`
  - `Build site`
  - the CLA check

## Checking the setup

| Setting | How to confirm it works |
|---|---|
| Squash merging | Merge a small PR; the commit on `main` has the PR title as its message. |
| `release` environment and secrets | The release-please dry run on the fork: merging the release PR starts **Release**, which waits for approval, then uploads to Central. |
| `snapshots` environment | The next push to `main` runs **Snapshot** without the "secrets are not configured" notice, and `2.0.0-SNAPSHOT` appears at `https://central.sonatype.com/repository/maven-snapshots/io/americanexpress/synapse/synapse-core/`. |
| `publish-snapshot` label | Add it to a same-repository PR; **Snapshot** publishes `2.0.0-pr<number>-SNAPSHOT`. |
| Pages | **Docs** on `main` deploys and prints the site URL. |
| `RELEASE_PLEASE_TOKEN` | The release PR shows CI checks. |
