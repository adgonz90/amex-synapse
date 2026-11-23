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
# Fails when a module would run PIT in the nightly job's profile but isn't in PIT_MODULES, the job's -pl list.
#
# Usage: PIT_MODULES=core/synapse-core pit-modules-check.sh <effective-pom.xml> [repository root, default .]
#   effective-pom.xml  the whole reactor's effective POM in the job's profile:
#                      ./mvnw -q -Pmutation help:effective-pom -Doutput=<file>
#
# Fails closed: a project opts in when its effective build plugins (not pluginManagement, not profiles) contain a
# plugin of groupId org.pitest whose configuration has no <skip>, or any <skip> that isn't exactly "true". The
# effective POM is interpolated, so this covers pitest.skip set to false, to anything else (empty, no, an undefined
# ${...}), the root's default flipped, and a plugin a module declares itself. Each opted-in project is mapped to its
# directory by the artifactId of the pom.xml files in the repository (target, node_modules and src trees skipped);
# one that maps to no directory fails too.

set -euo pipefail

effective_pom="${1:?usage: PIT_MODULES=<modules> $0 <effective-pom.xml> [repository root]}"
root="${2:-.}"
modules="${PIT_MODULES:?PIT_MODULES is not set}"

# artifactId of every project that opts in, from the effective POM (projects are indented 2 spaces in the
# multi-project file, their children 4).
opted_ids="$(awk '
    /^  <project[ >]/ { in_project = 1; id = ""; parent = 0; skipped = 0; depth = 0; next }
    /^  <\/project>/ { if (opted) print id; in_project = 0; opted = 0; next }
    !in_project { next }
    /^    <parent>/ { parent = 1 }
    /^    <\/parent>/ { parent = 0; next }
    id == "" && !parent && /^    <artifactId>/ { id = $0; gsub(/.*<artifactId>|<\/artifactId>.*/, "", id) }
    /<pluginManagement>|<profiles>/ { skipped++ }
    /<\/pluginManagement>|<\/profiles>/ { skipped--; next }
    skipped > 0 { next }
    /<plugin[ >]/ {
        depth++
        if (depth == 1) { group = ""; artifact = ""; skips = 0; other = 0; next }
    }
    /<\/plugin>/ {
        depth--
        if (depth == 0 && group == "org.pitest" && (skips == 0 || other > 0)) opted = 1
        next
    }
    depth >= 1 {
        if (group == "" && /<groupId>/) { group = $0; gsub(/.*<groupId>|<\/groupId>.*/, "", group) }
        if (/<skip>|<skip\/>/) {
            skips++
            value = $0
            if (value ~ /<skip\/>/) value = ""; else gsub(/.*<skip>|<\/skip>.*/, "", value)
            if (value != "true") other++
        }
    }
' "$effective_pom")"

# directory of each artifactId: the pom.xml files of the repository, without their <parent> block.
declare -A directory_of=()
while IFS= read -r pom; do
    id="$(awk '
        /<parent>/ { parent = 1 }
        /<\/parent>/ { parent = 0; next }
        !parent && /<artifactId>/ { gsub(/.*<artifactId>|<\/artifactId>.*/, ""); print; exit }
    ' "$pom")"
    dir="$(dirname "${pom#"$root"/}")"
    directory_of["$id"]="$dir"
done < <(find "$root" -name pom.xml -not -path '*/target/*' -not -path '*/node_modules/*' -not -path '*/src/*' \
    | sort)

missing=0
opted_dirs=()
while IFS= read -r id; do
    [ -n "$id" ] || continue
    dir="${directory_of[$id]:-}"
    if [ -z "$dir" ]; then
        echo "::error::$id opts in to PIT, but no pom.xml in the repository has that artifactId"
        missing=1
        continue
    fi
    opted_dirs+=("$dir")
    if [[ ",${modules}," != *",${dir},"* ]]; then
        echo "::error file=${dir}/pom.xml::${dir} opts in to PIT but isn't in PIT_MODULES (nightly.yml)"
        missing=1
    fi
done <<< "$opted_ids"

echo "Modules that opt in to PIT: ${opted_dirs[*]:-none}; PIT_MODULES: ${modules}"
exit "$missing"
