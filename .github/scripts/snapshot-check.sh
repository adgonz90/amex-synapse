#!/usr/bin/env bash
#
# Copyright 2026 American Express Travel Related Services Company, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#
# The "Check the run and the pull request" step of snapshot-publish.yml: decides whether the snapshot-staging artifact
# of a Snapshot build run may be published. Run from main's checkout (trusted/), never from the triggering run. The
# artifact's snapshot.env is untrusted data: it is parsed line by line, never sourced or evaluated.
#
# Environment (set by the workflow step):
#   RUN_EVENT, RUN_HEAD_SHA, RUN_PATH   the triggering run's event, head commit and workflow file
#   GH_TOKEN, GITHUB_REPOSITORY         for the GitHub API (gh)
#   RUNNER_TEMP                         holds snapshot-staging/snapshot.env
#   GITHUB_OUTPUT                       receives the outputs
#
# Outputs: publish=true with version=<version>, or publish=false (a warning says why) when a pull request has been
# closed, unlabelled or moved on. Anything that doesn't look like a genuine Snapshot build run fails the step.
#
# Tests: .github/scripts/test-snapshot-check.sh
set -euo pipefail
shopt -s inherit_errexit

: "${GITHUB_OUTPUT:?}" "${GITHUB_REPOSITORY:?}" "${RUNNER_TEMP:?}"
RUN_EVENT="${RUN_EVENT:-}"
RUN_HEAD_SHA="${RUN_HEAD_SHA:-}"
RUN_PATH="${RUN_PATH:-}"

# 2.<minor>.<patch>, then an optional milestone or release-candidate qualifier: after a milestone, release-please sets
# main to the next patch with the qualifier (for example 2.0.1-M1-SNAPSHOT).
readonly BASE_VERSION_RE='2\.[0-9]+\.[0-9]+(-(M|RC)[1-9][0-9]*)?'

fail() {
    echo "::error::$*"
    exit 1
}
skip() {
    echo "::warning::$* Not publishing."
    echo "publish=false" >> "$GITHUB_OUTPUT"
    exit 0
}

# Repeats the job condition: only snapshot-build.yml's artifacts are published, whatever the workflow is named.
[[ "$RUN_PATH" == .github/workflows/snapshot-build.yml ]] \
    || fail "The triggering run is not .github/workflows/snapshot-build.yml (path: ${RUN_PATH})."

[[ "$RUN_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "Unexpected head commit for the triggering run: ${RUN_HEAD_SHA}"

# snapshot.env must be exactly the three lines snapshot-build.yml writes. It is parsed, never sourced.
env_file="${RUNNER_TEMP}/snapshot-staging/snapshot.env"
[[ -f "$env_file" && ! -L "$env_file" ]] || fail "The artifact has no snapshot.env."
(($(wc -c < "$env_file") <= 512)) || fail "snapshot.env is too large."
mapfile -t lines < "$env_file"
((${#lines[@]} == 3)) || fail "snapshot.env must have exactly 3 lines, not ${#lines[@]}."
[[ "${lines[0]}" =~ ^VERSION=(${BASE_VERSION_RE}(-pr[1-9][0-9]*)?-SNAPSHOT)$ ]] \
    || fail "snapshot.env line 1 is not VERSION=2.<minor>.<patch>[-M<n>|-RC<n>][-pr<number>]-SNAPSHOT."
version="${BASH_REMATCH[1]}"
[[ "${lines[1]}" =~ ^PR_NUMBER=([1-9][0-9]{0,9})?$ ]] || fail "snapshot.env line 2 is not PR_NUMBER=[<number>]."
pr_number="${BASH_REMATCH[1]}"
[[ "${lines[2]}" =~ ^HEAD_SHA=([0-9a-f]{40})$ ]] || fail "snapshot.env line 3 is not HEAD_SHA=<40-hex commit>."
head_sha="${BASH_REMATCH[1]}"
[[ "$head_sha" == "$RUN_HEAD_SHA" ]] \
    || fail "snapshot.env names commit ${head_sha}, but the triggering run built ${RUN_HEAD_SHA}."

case "$RUN_EVENT" in
    push)
        [[ -z "$pr_number" ]] || fail "A push to main staged a pull request snapshot (PR_NUMBER=${pr_number})."
        [[ "$version" =~ ^${BASE_VERSION_RE}-SNAPSHOT$ ]] || fail "Version ${version} is not a main snapshot version."
        # The main branch, not a tag that happens to be called main: head_branch is only a name.
        status="$(gh api "repos/${GITHUB_REPOSITORY}/compare/${RUN_HEAD_SHA}...refs/heads/main" --jq .status)"
        [[ "$status" == identical || "$status" == ahead ]] \
            || fail "Commit ${RUN_HEAD_SHA} is not on the main branch (compare status: ${status})."
        ;;
    pull_request)
        [[ -n "$pr_number" ]] || fail "A pull request run staged no PR_NUMBER."
        [[ "$version" =~ ^${BASE_VERSION_RE}-pr${pr_number}-SNAPSHOT$ ]] \
            || fail "Version ${version} is not a snapshot version for pull request #${pr_number}."
        # One field per line; a deleted head repository prints an empty line.
        pr="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" --jq '
            .state, (.head.repo.full_name // ""), .head.sha, .base.ref,
            ([.labels[].name] | index("publish-snapshot") != null)')"
        mapfile -t fields <<< "$pr"
        ((${#fields[@]} == 5)) || fail "Unexpected answer from the pull request API for #${pr_number}."
        state="${fields[0]}" head_repo="${fields[1]}" pr_head_sha="${fields[2]}" base_ref="${fields[3]}"
        labelled="${fields[4]}"
        [[ "$head_repo" == "$GITHUB_REPOSITORY" ]] \
            || fail "Pull request #${pr_number} is not from a branch in ${GITHUB_REPOSITORY}."
        [[ "$base_ref" == main ]] || fail "Pull request #${pr_number} is not into main."
        [[ "$state" == open ]] || skip "Pull request #${pr_number} is ${state}."
        [[ "$labelled" == true ]] || skip "Pull request #${pr_number} no longer carries the publish-snapshot label."
        [[ "$pr_head_sha" == "$RUN_HEAD_SHA" ]] \
            || skip "Pull request #${pr_number} has moved on to ${pr_head_sha}; the run built ${RUN_HEAD_SHA}."
        ;;
    *) fail "Unexpected triggering event: ${RUN_EVENT}" ;;
esac

echo "Publishing ${version} (commit ${RUN_HEAD_SHA})."
echo "version=${version}" >> "$GITHUB_OUTPUT"
echo "publish=true" >> "$GITHUB_OUTPUT"
