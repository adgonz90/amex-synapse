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
# Tests for verify-groups.sh against crafted groups files and a crafted repository (the root, core/a, api/b and a
# template pom.xml under src, which is not a module). Needs bash only.
#
#   bash .github/scripts/test-verify-groups.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/verify-groups.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/verify-groups-test.XXXXXX")"
trap 'rm -rf -- "$TMP_ROOT"' EXIT

REPO="$TMP_ROOT/repo"
for dir in . core/a api/b api/b/src/main/resources/archetype-resources; do
    mkdir -p "$REPO/$dir"
    echo '<project/>' > "$REPO/$dir/pom.xml"
done

passed=0 failed=0

# check NAME EXPECT CONTENT [OUTPUT]: EXPECT pass or fail; CONTENT is the groups file (printf %b); OUTPUT, if given, is
# the exact JSON a pass must print.
check() {
    local name="$1" expect="$2" content="$3" output="${4:-}" rc got
    printf '%b' "$content" > "$TMP_ROOT/$name.txt"
    bash "$SCRIPT" "$TMP_ROOT/$name.txt" "$REPO" > "$TMP_ROOT/$name.out" 2>&1
    rc=$?
    if ((rc == 0)); then got=pass; else got=fail; fi
    if [[ "$got" == pass && -n "$output" && "$(cat "$TMP_ROOT/$name.out")" != "$output" ]]; then
        got=wrong-output
    fi
    if [[ "$got" == "$expect" ]]; then
        passed=$((passed + 1))
        echo "ok   $name"
    else
        failed=$((failed + 1))
        echo "FAIL $name: expected $expect, got $got (exit $rc)"
        sed 's/^/     /' "$TMP_ROOT/$name.out"
    fi
}

check all-modules-in-groups pass '# comment\n\none .,core/a\ntwo api/b\n' \
    '[{"group":"one","modules":".,core/a"},{"group":"two","modules":"api/b"}]'
check one-group pass 'all .,core/a,api/b\n' '[{"group":"all","modules":".,core/a,api/b"}]'
check module-left-out fail 'one .,core/a\n'
check root-left-out fail 'one core/a,api/b\n'
check module-in-two-groups fail 'one .,core/a\ntwo core/a,api/b\n'
check module-twice-in-a-group fail 'one .,core/a,core/a,api/b\n'
check no-such-module fail 'one .,core/a,api/b,api/c\n'
check template-is-not-a-module fail 'one .,core/a,api/b,api/b/src/main/resources/archetype-resources\n'
check group-defined-twice fail 'one .,core/a\none api/b\n'
check group-without-modules fail 'one .,core/a,api/b\ntwo\n'
check modules-with-a-space fail 'one .,core/a, api/b\n'
check quote-in-a-group-name fail 'o"ne .,core/a,api/b\n'
check quote-in-the-modules fail 'one .,core/a,api/b"\n'
check upper-case-group-name fail 'One .,core/a,api/b\n'
check reserved-group-name fail 'main .,core/a,api/b\n'

echo "passed: $passed, failed: $failed"
((failed == 0))
