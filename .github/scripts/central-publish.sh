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
# Publishes artifacts that a secret-free build job staged with
#   ./mvnw -B -ntp -P<profile> deploy -DaltDeploymentRepository=staging::file:<staging-dir>
# to Maven Central. Used by the publish jobs of release.yml and the snapshot workflow. It never runs Maven and never
# executes anything from the staging directory; it only reads, copies, signs and uploads files.
#
# Usage:
#   central-publish.sh check-tag <tag>                     tag format, tag is checked out, and its commit is on origin/main
#   central-publish.sh validate <staging-dir> <version>    only io/americanexpress/synapse/<artifactId>/<version>/ files
#   central-publish.sh release <staging-dir> <version>     validate, sign, checksum, bundle, upload, wait for validation
#   central-publish.sh snapshot <staging-dir> <version>    validate, then PUT to the snapshot repository
#
# Environment:
#   CENTRAL_USERNAME, CENTRAL_TOKEN   Central Portal user token (release and snapshot; not needed with DRY_RUN=1)
#   GPG_PRIVATE_KEY, GPG_PASSPHRASE   ASCII-armored signing key and its passphrase (release only)
#   CENTRAL_API                       default https://central.sonatype.com/api/v1/publisher
#   CENTRAL_SNAPSHOTS                 default https://central.sonatype.com/repository/maven-snapshots/
#   PUBLISH_TIMEOUT_SECONDS           how long to wait for Central to validate a release (default 1800)
#   POLL_INTERVAL_SECONDS             delay between status requests (default 10)
#   DRY_RUN=1                         do everything except HTTP requests (the release bundle is kept and printed)
#   RUNNER_TEMP                       parent of the working directory (default TMPDIR, then /tmp)
#
# Snapshot versions: <major>.<minor>.<patch>[-M<n>|-RC<n>][-pr<number>]-SNAPSHOT. After a milestone, release-please
# sets main to the next patch with the milestone qualifier (for example 2.0.1-M1-SNAPSHOT).
set -euo pipefail
# Command substitutions inherit errexit, so a failing find inside validate fails the run instead of returning a
# partial file list.
shopt -s inherit_errexit

readonly GROUP_PATH="io/americanexpress/synapse"
readonly RELEASE_VERSION_RE='^[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$'
readonly SNAPSHOT_VERSION_RE='^[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?(-pr[0-9]+)?-SNAPSHOT$'
readonly TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$'

CENTRAL_API="${CENTRAL_API:-https://central.sonatype.com/api/v1/publisher}"
CENTRAL_API="${CENTRAL_API%/}"
CENTRAL_SNAPSHOTS="${CENTRAL_SNAPSHOTS:-https://central.sonatype.com/repository/maven-snapshots/}"
CENTRAL_SNAPSHOTS="${CENTRAL_SNAPSHOTS%/}"
PUBLISH_TIMEOUT_SECONDS="${PUBLISH_TIMEOUT_SECONDS:-1800}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-10}"
DRY_RUN="${DRY_RUN:-0}"

WORK_DIR=""
GNUPG_DIR=""
AUTH_HEADER_FILE=""
KEEP_WORK_DIR=0

die() {
    echo "::error::$*" >&2
    exit 1
}

usage() {
    sed -n '/^# Usage:/,/^# Environment:/p' "$0" | sed '$d; s/^# \{0,1\}//' >&2
    exit 2
}

# Removes everything secret: the throwaway keyring (after stopping its agent) and the auth header file. The work
# directory goes too, unless it holds the bundle of a successful release run.
cleanup() {
    local status=$?
    if [[ -n "$GNUPG_DIR" && -d "$GNUPG_DIR" ]]; then
        gpgconf --homedir "$GNUPG_DIR" --kill all > /dev/null 2>&1 || true
        rm -rf -- "$GNUPG_DIR"
    fi
    if [[ -n "$AUTH_HEADER_FILE" ]]; then
        rm -f -- "$AUTH_HEADER_FILE"
    fi
    if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]] && ! ((KEEP_WORK_DIR && status == 0)); then
        rm -rf -- "$WORK_DIR"
    fi
    return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

make_work_dir() {
    local parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
    WORK_DIR="$(umask 077 && mktemp -d "${parent%/}/central-publish.XXXXXX")"
    WORK_DIR="$(cd "$WORK_DIR" && pwd)" # absolute: make_zip changes directory
}

require_env() {
    local name
    for name in "$@"; do
        [[ -n "${!name:-}" ]] || die "$name is not set."
    done
}

is_dry_run() { [[ "$DRY_RUN" == 1 ]]; }

# --- check-tag ---------------------------------------------------------------------------------------------------------

check_tag() {
    local tag="$1" commit head
    [[ "$tag" =~ $TAG_RE ]] || die "'$tag' is not a release tag (expected v2.0.0, v2.0.0-M1 or v2.0.0-RC1)."
    commit="$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}")" || die "Tag $tag does not exist."
    head="$(git rev-parse --verify HEAD)"
    [[ "$head" == "$commit" ]] || die "The checked-out commit $head is not tag $tag ($commit)."
    git rev-parse --verify --quiet refs/remotes/origin/main > /dev/null \
        || die "origin/main is not available (check out with fetch-depth: 0)."
    git merge-base --is-ancestor "$commit" refs/remotes/origin/main \
        || die "Tag $tag ($commit) is not on main: release tags must point at a commit on origin/main."
    echo "Tag $tag ($commit) is on main."
}

# --- validate ----------------------------------------------------------------------------------------------------------

# Prints the staged files (paths relative to the staging directory), one per line, after checking that every one is
#   io/americanexpress/synapse/<artifactId>/<version>/<artifactId>-<version>...  or  .../<version>/maven-metadata.xml*
#   io/americanexpress/synapse/<artifactId>/maven-metadata.xml[.md5|.sha1|.sha256|.sha512]  (never published)
# and that the directory holds nothing else (no symlinks or other special files).
validate() {
    local dir="$1" version="$2" list rel aid file_version name bad=0 artifacts=0 odd
    local version_re="^${GROUP_PATH}/([a-z0-9][a-z0-9.-]*)/([^/]+)/([A-Za-z0-9][A-Za-z0-9._-]*)$"
    local metadata_re="^${GROUP_PATH}/[a-z0-9][a-z0-9.-]*/maven-metadata\.xml(\.md5|\.sha1|\.sha256|\.sha512)?$"
    [[ -d "$dir" ]] || die "Staging directory not found: $dir"
    [[ "$version" =~ $RELEASE_VERSION_RE || "$version" =~ $SNAPSHOT_VERSION_RE ]] || die "Not a valid version: '$version'"

    odd="$(find "$dir" ! -type f ! -type d -print)"
    [[ -z "$odd" ]] || die "The staging directory contains links or special files: ${odd//$'\n'/, }"

    list="$WORK_DIR/files.txt"
    find "$dir" -type f -print > "$list"
    : > "$WORK_DIR/valid.txt"
    while IFS= read -r rel; do
        rel="${rel#"$dir"/}"
        if [[ "$rel" =~ $version_re ]]; then
            aid="${BASH_REMATCH[1]}" file_version="${BASH_REMATCH[2]}" name="${BASH_REMATCH[3]}"
            if [[ "$file_version" == "$version" ]] \
                && [[ "$name" == maven-metadata.xml* || "$name" == "$aid-${version%-SNAPSHOT}"* ]]; then
                printf '%s\n' "$rel" >> "$WORK_DIR/valid.txt"
                [[ "$name" == maven-metadata.xml* ]] || artifacts=$((artifacts + 1))
                continue
            fi
        elif [[ "$rel" =~ $metadata_re ]]; then
            continue
        fi
        echo "::error::Not allowed in the staging directory for $version: $rel" >&2
        bad=1
    done < "$list"
    ((bad == 0)) || die "The staging directory holds files that must not be published (expected only ${GROUP_PATH}/<artifactId>/${version}/)."
    ((artifacts > 0)) || die "Nothing to publish for $version in $dir."
    LC_ALL=C sort "$WORK_DIR/valid.txt"
}

# --- shared ------------------------------------------------------------------------------------------------------------

is_checksum() { [[ "$1" == *.md5 || "$1" == *.sha1 || "$1" == *.sha256 || "$1" == *.sha512 ]]; }

# write_auth_header Bearer|Basic: the Authorization header goes in a file curl reads (-H @file), so the token never
# appears on a command line.
write_auth_header() {
    local scheme="$1" credentials
    credentials="$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_TOKEN" | base64 | tr -d '\n')"
    AUTH_HEADER_FILE="$WORK_DIR/auth-header"
    (umask 077 && printf 'Authorization: %s %s\n' "$scheme" "$credentials" > "$AUTH_HEADER_FILE")
}

central_curl() {
    curl --fail-with-body --silent --show-error --proto '=https' -H "@$AUTH_HEADER_FILE" "$@"
}

# --- release -----------------------------------------------------------------------------------------------------------

import_signing_key() {
    GNUPG_DIR="$WORK_DIR/gnupg"
    (umask 077 && mkdir "$GNUPG_DIR")
    export GNUPGHOME="$GNUPG_DIR"
    printf '%s\n' "$GPG_PRIVATE_KEY" | gpg --batch --quiet --import || die "Could not import the signing key."
}

sign() {
    printf '%s\n' "$GPG_PASSPHRASE" \
        | gpg --batch --quiet --yes --pinentry-mode loopback --passphrase-fd 0 --armor --detach-sign \
            --output "$1.asc" "$1" \
        || die "Could not sign ${1##*/}."
}

write_checksums() {
    local file="$1"
    [[ -f "$file.md5" ]] || printf '%s' "$(md5sum < "$file" | cut -d ' ' -f 1)" > "$file.md5"
    [[ -f "$file.sha1" ]] || printf '%s' "$(sha1sum < "$file" | cut -d ' ' -f 1)" > "$file.sha1"
}

# zip when it's installed (CI runners), else the JDK's jar (Git Bash on Windows has no zip). Entries are relative
# to the bundle root, in Maven repository layout.
make_zip() {
    local src="$1" out="$2" jar_tool
    if command -v zip > /dev/null; then
        (cd "$src" && zip -q -r -X "$out" .)
    else
        jar_tool="$(command -v jar || true)"
        if [[ -z "$jar_tool" && -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then jar_tool="$JAVA_HOME/bin/jar"; fi
        [[ -n "$jar_tool" ]] || die "Neither zip nor a JDK jar tool is available to create the bundle."
        "$jar_tool" --create --no-manifest --file "$out" -C "$src" .
    fi
}

# Polls the deployment until Central has validated it (PUBLISHING or PUBLISHED), it fails, or the timeout passes.
wait_for_validation() {
    local id="$1" deadline=$((SECONDS + PUBLISH_TIMEOUT_SECONDS)) body state="" pause
    while :; do
        if body="$(central_curl -X POST "$CENTRAL_API/status?id=$id")"; then
            state="$(printf '%s\n' "$body" \
                | sed -n 's/.*"deploymentState"[[:space:]]*:[[:space:]]*"\([A-Z_]*\)".*/\1/p' | head -n 1)"
            echo "Deployment $id: ${state:-unknown state}"
            case "$state" in
                PUBLISHING | PUBLISHED)
                    echo "Central validated deployment $id and is publishing it."
                    return 0
                    ;;
                FAILED)
                    echo "::error::Central rejected deployment $id:" >&2
                    printf '%s\n' "$body" >&2
                    exit 1
                    ;;
            esac
        else
            echo "::warning::Status request for deployment $id failed: $body" >&2
        fi
        if ((SECONDS >= deadline)); then
            die "Timed out after ${PUBLISH_TIMEOUT_SECONDS}s waiting for Central to validate deployment $id (last state: ${state:-unknown})."
        fi
        pause=$((deadline - SECONDS))
        ((pause < POLL_INTERVAL_SECONDS)) || pause="$POLL_INTERVAL_SECONDS"
        sleep "$pause"
    done
}

release() {
    local dir="$1" version="$2" files rel name bundle zip deployment_id
    [[ "$version" =~ $RELEASE_VERSION_RE ]] || die "Not a release version: '$version'"
    files="$(validate "$dir" "$version")"
    require_env GPG_PRIVATE_KEY GPG_PASSPHRASE
    is_dry_run || require_env CENTRAL_USERNAME CENTRAL_TOKEN

    # Copy the artifacts (not Maven's checksums or metadata) to a bundle tree; signatures and checksums are made
    # there, so the staging directory is only ever read.
    bundle="$WORK_DIR/bundle"
    mkdir "$bundle"
    while IFS= read -r rel; do
        name="${rel##*/}"
        if [[ "$name" == maven-metadata.xml* || "$name" == *.asc ]] || is_checksum "$name"; then continue; fi
        mkdir -p "$bundle/${rel%/*}"
        cp -- "$dir/$rel" "$bundle/$rel"
    done <<< "$files"

    import_signing_key
    while IFS= read -r rel; do
        sign "$bundle/$rel"
    done < <(cd "$bundle" && find . -type f ! -name '*.asc' | sed 's|^\./||' | LC_ALL=C sort)
    while IFS= read -r rel; do
        write_checksums "$bundle/$rel"
    done < <(cd "$bundle" && find . -type f ! -name '*.md5' ! -name '*.sha1' | sed 's|^\./||' | LC_ALL=C sort)

    zip="$WORK_DIR/synapse-$version.zip"
    make_zip "$bundle" "$zip"
    echo "Bundle: $zip ($(find "$bundle" -type f | wc -l | tr -d ' ') files)"

    if is_dry_run; then
        KEEP_WORK_DIR=1
        echo "DRY_RUN=1: not uploading."
        return 0
    fi

    write_auth_header Bearer
    deployment_id="$(central_curl -X POST -F "bundle=@$zip;type=application/octet-stream" \
        "$CENTRAL_API/upload?publishingType=AUTOMATIC&name=synapse-$version")" \
        || die "Upload to Central failed: $deployment_id"
    [[ "$deployment_id" =~ ^[A-Za-z0-9-]+$ ]] || die "Unexpected upload response from Central: $deployment_id"
    echo "Uploaded synapse-$version as deployment $deployment_id; Central publishes it once validated."
    wait_for_validation "$deployment_id"
}

# --- snapshot ----------------------------------------------------------------------------------------------------------

# Upload order within each <artifactId>/<version>/ directory: artifacts, their checksums, then maven-metadata.xml and
# its checksums last, so the metadata never points at files that aren't there yet. The artifactId-level
# maven-metadata.xml is never uploaded: overwriting it would drop every other version from the listing.
upload_group() {
    if [[ "$1" == maven-metadata.xml* ]]; then
        echo metadata
    elif is_checksum "$1"; then
        echo checksums
    else
        echo artifacts
    fi
}

snapshot() {
    local dir="$1" version="$2" files version_dir rel group response
    [[ "$version" =~ $SNAPSHOT_VERSION_RE ]] || die "Not a snapshot version: '$version'"
    files="$(validate "$dir" "$version")"
    is_dry_run || require_env CENTRAL_USERNAME CENTRAL_TOKEN
    is_dry_run || write_auth_header Basic

    while IFS= read -r version_dir; do
        grep -qxF "$version_dir/maven-metadata.xml" <<< "$files" \
            || die "$version_dir has no maven-metadata.xml; a snapshot can't be resolved without it."
        for group in artifacts checksums metadata; do
            while IFS= read -r rel; do
                [[ "${rel%/*}" == "$version_dir" && "$(upload_group "${rel##*/}")" == "$group" ]] || continue
                if is_dry_run; then
                    echo "Would PUT $CENTRAL_SNAPSHOTS/$rel"
                    continue
                fi
                echo "PUT $CENTRAL_SNAPSHOTS/$rel"
                if ! response="$(central_curl --retry 3 -T "$dir/$rel" "$CENTRAL_SNAPSHOTS/$rel")"; then
                    die "Upload of $rel failed: $response"
                fi
            done <<< "$files"
        done
    done < <(awk '{ sub(/\/[^\/]*$/, ""); print }' <<< "$files" | LC_ALL=C sort -u)
    if is_dry_run; then
        echo "Would publish $version to $CENTRAL_SNAPSHOTS (DRY_RUN=1: nothing uploaded)."
    else
        echo "Published $version to $CENTRAL_SNAPSHOTS."
    fi
}

# --- main --------------------------------------------------------------------------------------------------------------

main() {
    local mode="${1:-}"
    case "$mode" in
        check-tag)
            [[ $# -eq 2 ]] || usage
            check_tag "$2"
            ;;
        validate | release | snapshot)
            [[ $# -eq 3 ]] || usage
            make_work_dir
            local dir="${2%/}"
            case "$mode" in
                validate)
                    # Two commands, not "validate ... && echo": errexit is off inside a command on the left of &&.
                    validate "$dir" "$3" > /dev/null
                    echo "$dir holds only ${GROUP_PATH} files for $3."
                    ;;
                release) release "$dir" "$3" ;;
                snapshot) snapshot "$dir" "$3" ;;
            esac
            ;;
        *) usage ;;
    esac
}

main "$@"
