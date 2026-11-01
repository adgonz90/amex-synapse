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
# Tests for snapshot-check.sh against crafted snapshot.env files. A fake gh on PATH answers the compare and pull request
# API calls from FAKE_* variables, so nothing reaches GitHub. Needs bash only.
#
#   bash .github/scripts/test-snapshot-check.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/snapshot-check.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/snapshot-check-test.XXXXXX")"
trap 'rm -rf -- "$TMP_ROOT"' EXIT

A=0123456789abcdef0123456789abcdef01234567
B=89abcdef0123456789abcdef0123456789abcdef
REPO=example/synapse

mkdir -p "$TMP_ROOT/bin"
cat > "$TMP_ROOT/bin/gh" << 'EOF'
#!/usr/bin/env bash
# Fake gh: logs its arguments and answers from FAKE_* variables.
echo "gh $*" >> "$FAKE_LOG"
case "$*" in
    *"/compare/"*)
        [[ "$*" == *"...refs/heads/main "* ]] || { echo "compare must use refs/heads/main" >&2; exit 1; }
        [[ -z "${FAKE_COMPARE_FAIL:-}" ]] || { echo "HTTP 404" >&2; exit 1; }
        echo "$FAKE_COMPARE"
        ;;
    *"/pulls/"*)
        [[ -z "${FAKE_PR_FAIL:-}" ]] || { echo "HTTP 404" >&2; exit 1; }
        printf '%s\n' "$FAKE_STATE" "$FAKE_HEAD_REPO" "$FAKE_PR_SHA" "$FAKE_BASE" "$FAKE_LABELLED"
        ;;
    *) echo "unexpected gh call" >&2; exit 1 ;;
esac
EOF
chmod +x "$TMP_ROOT/bin/gh"

passed=0 failed=0

# check NAME EXPECT EVENT CONTENT [VAR=value ...]
#   EXPECT    publish, skip or fail
#   CONTENT   snapshot.env as a printf %b format, or NONE for no file
#   VAR=value overrides the defaults below (a genuine push to main or open, labelled pull request at commit A).
check() {
    local name="$1" expect="$2" event="$3" content="$4" t rc got version
    shift 4
    t="$TMP_ROOT/$name"
    mkdir -p "$t/snapshot-staging"
    [[ "$content" == NONE ]] || printf '%b' "$content" > "$t/snapshot-staging/snapshot.env"
    : > "$t/out"
    : > "$t/gh.log"
    (
        export PATH="$TMP_ROOT/bin:$PATH" RUNNER_TEMP="$t" GITHUB_OUTPUT="$t/out" GITHUB_REPOSITORY="$REPO" \
            FAKE_LOG="$t/gh.log" GH_TOKEN=fake RUN_EVENT="$event" RUN_HEAD_SHA="$A" \
            RUN_PATH=.github/workflows/snapshot-build.yml FAKE_COMPARE=ahead FAKE_STATE=open FAKE_HEAD_REPO="$REPO" \
            FAKE_PR_SHA="$A" FAKE_BASE=main FAKE_LABELLED=true
        for kv in "$@"; do export "${kv?}"; done
        bash "$SCRIPT"
    ) > "$t/stdout" 2>&1
    rc=$?
    if ((rc != 0)); then
        got=fail
    elif grep -qx 'publish=true' "$t/out"; then
        got=publish
    elif grep -qx 'publish=false' "$t/out"; then
        got=skip
    else
        got=exit0-without-output
    fi
    if [[ "$got" == publish && "$expect" == publish ]]; then
        # The version output is snapshot.env's VERSION, unchanged.
        version="$(printf '%b' "$content" | sed -n '1s/^VERSION=//p')"
        if ! grep -qx "version=$version" "$t/out"; then
            got="publish without version=$version"
        fi
    fi
    if [[ "$got" == "$expect" ]]; then
        passed=$((passed + 1))
        printf 'PASS %-34s %-7s | %s\n' "$name" "$got" "$(grep -m1 -E '::(error|warning)::|^Publishing' "$t/stdout")"
    else
        failed=$((failed + 1))
        printf 'FAIL %-34s expected %s, got %s\n' "$name" "$expect" "$got"
        sed 's/^/      | /' "$t/stdout"
    fi
}

# snapshot.env contents as CONTENT formats (escaped newlines, so command substitution keeps the last one).
main_env() { printf 'VERSION=%s\\nPR_NUMBER=\\nHEAD_SHA=%s\\n' "$1" "$A"; }
pr_env() { printf 'VERSION=%s\\nPR_NUMBER=%s\\nHEAD_SHA=%s\\n' "$1" "$2" "$A"; }

# Pushes to main.
check push-main publish push "$(main_env 2.0.0-SNAPSHOT)"
check push-main-identical publish push "$(main_env 2.1.3-SNAPSHOT)" FAKE_COMPARE=identical
check push-not-on-main fail push "$(main_env 2.0.0-SNAPSHOT)" FAKE_COMPARE=diverged
check push-compare-404 fail push "$(main_env 2.0.0-SNAPSHOT)" FAKE_COMPARE_FAIL=1
check push-with-pr-number fail push "$(pr_env 2.0.0-pr5-SNAPSHOT 5)"
check push-pr-version fail push "$(main_env 2.0.0-pr5-SNAPSHOT)"
check head-sha-mismatch fail push "VERSION=2.0.0-SNAPSHOT\nPR_NUMBER=\nHEAD_SHA=$B\n"
# shellcheck disable=SC2016 # a literal $(id): the script must reject it, never expand it
check run-sha-malformed fail push "$(main_env 2.0.0-SNAPSHOT)" 'RUN_HEAD_SHA=$(id)'

# Pull requests.
check pr-ok publish pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)"
check pr-unstamped fail pull_request "$(pr_env 2.0.0-SNAPSHOT 12)"
check pr-other-number-in-version fail pull_request "$(pr_env 2.0.0-pr13-SNAPSHOT 12)"
check pr-no-number fail pull_request "$(main_env 2.0.0-pr12-SNAPSHOT)"
check pr-leading-zero fail pull_request "$(pr_env 2.0.0-pr012-SNAPSHOT 012)"
check pr-closed skip pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_STATE=closed
check pr-unlabelled skip pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_LABELLED=false
check pr-head-moved skip pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_PR_SHA="$B"
check pr-from-fork fail pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_HEAD_REPO=evil/synapse
check pr-head-repo-deleted fail pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_HEAD_REPO=
check pr-into-develop fail pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_BASE=develop
check pr-api-404 fail pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" FAKE_PR_FAIL=1

# The run and snapshot.env itself.
check other-event fail workflow_dispatch "$(main_env 2.0.0-SNAPSHOT)"
check no-env-file fail push NONE
check extra-line fail push "VERSION=2.0.0-SNAPSHOT\nPR_NUMBER=\nHEAD_SHA=$A\nX=\$(id)\n"
check reordered fail push "PR_NUMBER=\nVERSION=2.0.0-SNAPSHOT\nHEAD_SHA=$A\n"
check injection-in-version fail push "VERSION=2.0.0-SNAPSHOT\$(id)\nPR_NUMBER=\nHEAD_SHA=$A\n"
check trailing-empty-line fail push "VERSION=2.0.0-SNAPSHOT\nPR_NUMBER=\nHEAD_SHA=$A\n\n"
check crlf fail push "VERSION=2.0.0-SNAPSHOT\r\nPR_NUMBER=\r\nHEAD_SHA=$A\r\n"
check v1-version fail push "$(main_env 0.4.20-SNAPSHOT)"
check v3-version fail push "$(main_env 3.0.0-SNAPSHOT)"
check release-version fail push "$(main_env 2.0.0-M1)"
check uppercase-sha fail push "VERSION=2.0.0-SNAPSHOT\nPR_NUMBER=\nHEAD_SHA=${A^^}\n"
check oversized fail push "VERSION=2.0.0-SNAPSHOT\nPR_NUMBER=\nHEAD_SHA=$A$(printf '%600s' '')\n"
check wrong-workflow-path fail push "$(main_env 2.0.0-SNAPSHOT)" RUN_PATH=.github/workflows/other.yml
check empty-workflow-path fail pull_request "$(pr_env 2.0.0-pr12-SNAPSHOT 12)" RUN_PATH=
check path-with-ref-suffix fail push "$(main_env 2.0.0-SNAPSHOT)" \
    RUN_PATH=.github/workflows/snapshot-build.yml@refs/tags/main
check path-traversal-lookalike fail push "$(main_env 2.0.0-SNAPSHOT)" \
    RUN_PATH=.github/workflows/../workflows/snapshot-build.yml

# After a milestone, release-please sets main to <next patch>-M<n>-SNAPSHOT (or -RC<n>-SNAPSHOT).
check push-after-milestone publish push "$(main_env 2.0.1-M1-SNAPSHOT)"
check push-after-rc publish push "$(main_env 2.0.0-RC1-SNAPSHOT)"
check pr-after-milestone publish pull_request "$(pr_env 2.0.1-M1-pr12-SNAPSHOT 12)"
check pr-after-milestone-unstamped fail pull_request "$(pr_env 2.0.1-M1-SNAPSHOT 12)"
check pr-after-milestone-other-number fail pull_request "$(pr_env 2.0.1-M1-pr13-SNAPSHOT 12)"
check push-after-milestone-pr-version fail push "$(main_env 2.0.1-M1-pr5-SNAPSHOT)"
check qualifier-after-pr-number fail pull_request "$(pr_env 2.0.1-pr12-M1-SNAPSHOT 12)"
check milestone-zero fail push "$(main_env 2.0.1-M0-SNAPSHOT)"
check milestone-without-number fail push "$(main_env 2.0.1-M-SNAPSHOT)"
check two-qualifiers fail push "$(main_env 2.0.1-M1-RC1-SNAPSHOT)"
check other-qualifier fail push "$(main_env 2.0.1-beta1-SNAPSHOT)"
check milestone-release-version fail push "$(main_env 2.0.1-M1)"

echo "$passed passed, $failed failed"
((failed == 0))
