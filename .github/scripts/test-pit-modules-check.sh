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
# Tests for pit-modules-check.sh against crafted effective POMs and a crafted repository of three modules (the root,
# core/a and api/b). Needs bash and awk only.
#
#   bash .github/scripts/test-pit-modules-check.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/pit-modules-check.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pit-modules-check-test.XXXXXX")"
trap 'rm -rf -- "$TMP_ROOT"' EXIT

# The repository: each pom.xml with a parent block first, as Maven modules have; a template under src is ignored.
REPO="$TMP_ROOT/repo"
pom() {
    mkdir -p "$REPO/$1"
    printf '<project>\n  <parent>\n    <artifactId>parent</artifactId>\n  </parent>\n  <artifactId>%s</artifactId>\n</project>\n' \
        "$2" > "$REPO/$1/pom.xml"
}
pom . root
pom core/a a
pom api/b b
pom api/b/src/main/resources/archetype-resources template

# project ID PLUGINS: one project of a multi-project effective POM, indented as help:effective-pom writes it.
project() {
    printf '  <project>\n    <parent>\n      <artifactId>parent</artifactId>\n    </parent>\n    <artifactId>%s</artifactId>\n' "$1"
    printf '    <build>\n      <pluginManagement>\n        <plugins>\n%s        </plugins>\n      </pluginManagement>\n' "$(pit false)"
    printf '      <plugins>\n%s      </plugins>\n    </build>\n' "$2"
    printf '    <profiles>\n      <profile>\n        <build>\n          <plugins>\n%s          </plugins>\n' "$(pit false)"
    printf '        </build>\n      </profile>\n    </profiles>\n  </project>\n'
}

# pit SKIP [GROUP]: a plugin with one <skip> (NONE: none, EMPTY: <skip/>), as an effective POM writes it.
pit() {
    local skip="$1" group="${2:-org.pitest}"
    printf '        <plugin>\n          <groupId>%s</groupId>\n          <artifactId>pitest-maven</artifactId>\n' "$group"
    printf '          <dependencies>\n            <dependency>\n              <groupId>org.pitest</groupId>\n'
    printf '            </dependency>\n          </dependencies>\n          <configuration>\n'
    case "$skip" in
        NONE) ;;
        EMPTY) printf '            <skip/>\n' ;;
        *) printf '            <skip>%s</skip>\n' "$skip" ;;
    esac
    printf '          </configuration>\n        </plugin>\n'
}

# other: a plugin whose configuration nests a <plugin> (as protobuf-maven-plugin's does) and has a <skip> of false.
other() {
    printf '        <plugin>\n          <groupId>io.github.ascopes</groupId>\n          <configuration>\n'
    printf '            <skip>false</skip>\n            <plugins>\n              <plugin kind="binary-maven">\n'
    printf '                <groupId>org.pitest</groupId>\n              </plugin>\n            </plugins>\n'
    printf '          </configuration>\n        </plugin>\n'
}

passed=0 failed=0

# check NAME EXPECT PIT_MODULES ROOT A B: EXPECT pass or fail; ROOT, A and B are each project's build plugins.
# IDS overrides the three projects' artifactIds (default: root a b).
check() {
    local name="$1" expect="$2" modules="$3" rc got ids
    local file="$TMP_ROOT/$name.xml"
    read -r -a ids <<< "${IDS:-root a b}"
    {
        printf '<?xml version="1.0" encoding="UTF-8"?>\n<projects>\n'
        project "${ids[0]}" "$4"
        project "${ids[1]}" "$5"
        project "${ids[2]}" "$6"
        printf '</projects>\n'
    } > "$file"
    PIT_MODULES="$modules" bash "$SCRIPT" "$file" "$REPO" > "$TMP_ROOT/$name.out" 2>&1
    rc=$?
    if ((rc == 0)); then got=pass; else got=fail; fi
    if [[ "$got" == "$expect" ]]; then
        passed=$((passed + 1))
        echo "ok   $name"
    else
        failed=$((failed + 1))
        echo "FAIL $name: expected $expect, got $got (exit $rc)"
        sed 's/^/     /' "$TMP_ROOT/$name.out"
    fi
}

SKIPPED="$(pit true)"

check only-listed-module-opts-in   pass core/a     "$SKIPPED" "$(pit false)" "$SKIPPED"
check unlisted-module-opts-in      fail core/a     "$SKIPPED" "$(pit false)" "$(pit false)"
check both-listed                  pass core/a,api/b "$SKIPPED" "$(pit false)" "$(pit false)"
check root-default-flipped         fail core/a     "$(pit false)" "$(pit false)" "$(pit false)"
check empty-value                  fail core/a     "$SKIPPED" "$(pit false)" "$(pit '')"
check self-closing-skip            fail core/a     "$SKIPPED" "$(pit false)" "$(pit EMPTY)"
check other-value                  fail core/a     "$SKIPPED" "$(pit false)" "$(pit no)"
# shellcheck disable=SC2016 # the literal ${pitest.skip} is the point: an undefined property stays uninterpolated.
check undefined-property           fail core/a     "$SKIPPED" "$(pit false)" "$(pit '${pitest.skip}')"
check no-skip-at-all               fail core/a     "$SKIPPED" "$(pit false)" "$(pit NONE)"
check pluginmanagement-and-profiles-ignored pass core/a "$SKIPPED" "$(pit false)" "$SKIPPED"
check other-plugins-ignored        pass core/a     "$SKIPPED" "$(pit false)" "$(other)$SKIPPED"
check no-pit-plugin-at-all         pass core/a     "" "$(pit false)" ""
IDS="root a ghost" check unknown-artifact fail core/a,api/b "$SKIPPED" "$(pit false)" "$(pit false)"
IDS="root a ghost" check unknown-but-skipped pass core/a "$SKIPPED" "$(pit false)" "$SKIPPED"
check list-names-a-path-not-an-id  fail a          "$SKIPPED" "$(pit false)" "$SKIPPED"

echo "passed: $passed, failed: $failed"
((failed == 0))
