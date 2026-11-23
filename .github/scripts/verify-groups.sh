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
# Checks the module groups of CI's split Verify and prints them as a GitHub Actions matrix (JSON), for example
# [{"group":"core","modules":".,core/synapse-core"}].
#
# Usage: verify-groups.sh <groups file> [repository root, default .]
#   groups file  lines of "<group> <comma-separated module paths>"; "#" starts a comment, "." is the root POM.
#
# Fails, naming the module, when a group lists a path that isn't a module, lists a module twice (in one group or
# two), or leaves out a module of the repository: every directory with a pom.xml outside target, node_modules and src
# trees, the root included. So a new module fails the pull request until it is given a group.

set -euo pipefail

groups_file="${1:?usage: $0 <groups file> [repository root]}"
root="${2:-.}"

# The modules: every directory with a pom.xml outside target, node_modules and src trees ("." for the root).
declare -A is_module=()
modules_found=()
while IFS= read -r pom; do
    module="$(dirname "${pom#"$root"/}")"
    is_module["$module"]=1
    modules_found+=("$module")
done < <(find "$root" -name pom.xml -not -path '*/target/*' -not -path '*/node_modules/*' -not -path '*/src/*' \
    | sort)

declare -A group_of=()
errors=0
json=""
names=()

while read -r group modules extra; do
    [ -n "${group:-}" ] || continue
    case "$group" in \#*) continue ;; esac
    if [ -z "${modules:-}" ] || [ -n "${extra:-}" ]; then
        echo "::error file=$groups_file::group $group: expected '<group> <comma-separated modules>'" >&2
        errors=1
        continue
    fi
    # "main" names the push-only jobs (Verify (JDK 25, main), Smoke (tests on JDK 21, main)): a group can't take it.
    if [ "$group" = main ]; then
        echo "::error file=$groups_file::group name main is reserved for the push-only jobs" >&2
        errors=1
        continue
    fi
    # Plain names only: they end up in JSON, job names and artifact names.
    if [[ ! "$group" =~ ^[a-z0-9-]+$ ]] || [[ ! "$modules" =~ ^[A-Za-z0-9._/,-]+$ ]]; then
        echo "::error file=$groups_file::group $group: use [a-z0-9-] for the name and [A-Za-z0-9._/,-] for the modules" >&2
        errors=1
        continue
    fi
    for name in "${names[@]}"; do
        if [ "$name" = "$group" ]; then
            echo "::error file=$groups_file::group $group is defined twice" >&2
            errors=1
        fi
    done
    names+=("$group")
    IFS=',' read -r -a list <<< "$modules"
    for module in "${list[@]}"; do
        if [ -z "${is_module[$module]:-}" ]; then
            echo "::error file=$groups_file::group $group lists $module, which isn't a module of the repository" >&2
            errors=1
        elif [ -n "${group_of[$module]:-}" ]; then
            echo "::error file=$groups_file::$module is in group ${group_of[$module]} and in group $group" >&2
            errors=1
        else
            group_of["$module"]="$group"
        fi
    done
    json="${json:+$json,}{\"group\":\"$group\",\"modules\":\"$modules\"}"
done < "$groups_file"

for module in "${modules_found[@]}"; do
    if [ -z "${group_of[$module]:-}" ]; then
        echo "::error file=${module}/pom.xml::${module} is in no group of $groups_file" >&2
        errors=1
    fi
done

if [ "$errors" -ne 0 ]; then
    exit 1
fi
echo "[$json]"
