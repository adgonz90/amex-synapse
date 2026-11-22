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
# Tests for central-publish.sh. Fake curl, gpg and gpgconf on PATH log their arguments and return canned
# responses, so nothing ever reaches Central. Needs bash, git, unzip, md5sum and sha1sum; zip, or else a JDK's jar on
# PATH or at JAVA_HOME/bin/jar (Git Bash on Windows has no zip); and a real gpg for case 9.
#
#   bash .github/scripts/test-central-publish.sh                          run every case
#   bash .github/scripts/test-central-publish.sh stage-check DIR VERSION  sign DIR (a real Maven staging deploy) with a
#                                                                         throwaway key in DRY_RUN and check the bundle
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$SCRIPT_DIR/central-publish.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/central-publish-test.XXXXXX")"
REAL_GPG_HOMES=()

cleanup() {
    local home
    for home in "${REAL_GPG_HOMES[@]+"${REAL_GPG_HOMES[@]}"}"; do
        gpgconf --homedir "$home" --kill all > /dev/null 2>&1 || true
    done
    rm -rf -- "$TMP_ROOT"
}
trap cleanup EXIT

GROUP="io/americanexpress/synapse"
USER_NAME="central-user"
USER_TOKEN="central-token"

# ---------------------------------------------------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------------------------------------------------

# new_case NAME: a fresh directory with fakes on bin/, logs in log/ and a RUNNER_TEMP in temp/.
new_case() {
    CASE_DIR="$TMP_ROOT/$1"
    mkdir -p "$CASE_DIR/bin" "$CASE_DIR/log" "$CASE_DIR/temp"
    : > "$CASE_DIR/log/curl.log"
    : > "$CASE_DIR/log/puts.log"
    : > "$CASE_DIR/log/gpg.log"
    printf 'PUBLISHED\n' > "$CASE_DIR/log/states"
    write_fakes "$CASE_DIR/bin"
}

write_fakes() {
    local bin="$1"
    cat > "$bin/curl" << 'EOF'
#!/usr/bin/env bash
# Fake curl: logs every argument (and the contents of -H @file), answers upload, status and PUT requests.
log="$FAKE_LOG_DIR/curl.log"
args=("$@")
url="" put_file=""
echo "--- curl" >> "$log"
for ((i = 0; i < ${#args[@]}; i++)); do
    a="${args[i]}"
    echo "arg: $a" >> "$log"
    case "$a" in
        -H) next="${args[i + 1]:-}"
            if [[ "$next" == @* ]]; then echo "header-file: $(cat "${next#@}")" >> "$log"; fi ;;
        -T) put_file="${args[i + 1]:-}" ;;
        http://* | https://*) url="$a" ;;
    esac
done
case "$url" in
    */upload\?*)
        echo "upload: $url" >> "$log"
        printf 'dep-0001'
        ;;
    */status\?id=*)
        count_file="$FAKE_LOG_DIR/status-count"
        n="$(cat "$count_file" 2> /dev/null || echo 0)"
        n=$((n + 1))
        echo "$n" > "$count_file"
        state="$(sed -n "${n}p" "$FAKE_LOG_DIR/states")"
        [ -n "$state" ] || state="$(tail -n 1 "$FAKE_LOG_DIR/states")"
        echo "status: $url $state" >> "$log"
        if [ "$state" = FAILED ]; then
            printf '{"deploymentId":"dep-0001","deploymentState":"FAILED","errors":{"pkg:maven/io.americanexpress.synapse/synapse-core@2.0.0-M1":["Invalid signature for file: synapse-core-2.0.0-M1.jar"]}}'
        else
            printf '{\n  "deploymentId" : "dep-0001",\n  "deploymentState" : "%s"\n}\n' "$state"
        fi
        ;;
    *)
        if [ -n "$put_file" ]; then
            echo "PUT $url" >> "$FAKE_LOG_DIR/puts.log"
        else
            echo "unexpected request: $url" >> "$log"
            exit 22
        fi
        ;;
esac
EOF
    cat > "$bin/gpg" << 'EOF'
#!/usr/bin/env bash
# Fake gpg: logs its arguments and GNUPGHOME, imports by copying stdin, signs by writing a fake signature.
log="$FAKE_LOG_DIR/gpg.log"
echo "--- gpg GNUPGHOME=${GNUPGHOME:-}" >> "$log"
printf 'arg: %s\n' "$@" >> "$log"
out="" mode="" prev=""
for a in "$@"; do
    [ "$prev" = --output ] && out="$a"
    case "$a" in --import) mode=import ;; --detach-sign) mode=sign ;; esac
    prev="$a"
done
input="${!#}"
case "$mode" in
    import) cat > "$GNUPGHOME/fake-keyring" ;;
    sign)
        echo "passphrase: $(cat)" >> "$log"
        printf -- '-----BEGIN PGP SIGNATURE-----\nfake %s\n-----END PGP SIGNATURE-----\n' "${input##*/}" > "$out"
        ;;
esac
EOF
    cat > "$bin/gpgconf" << 'EOF'
#!/usr/bin/env bash
echo "--- gpgconf $*" >> "$FAKE_LOG_DIR/gpg.log"
EOF
    chmod +x "$bin/curl" "$bin/gpg" "$bin/gpgconf"
}

# put_file PATH [CONTENT]: create a staged file with its Maven-style checksums.
put_file() {
    local path="$1" content="${2:-content of ${1##*/}}"
    mkdir -p "$(dirname "$path")"
    printf '%s\n' "$content" > "$path"
    printf '%s' "$(md5sum < "$path" | cut -d ' ' -f 1)" > "$path.md5"
    printf '%s' "$(sha1sum < "$path" | cut -d ' ' -f 1)" > "$path.sha1"
}

# release_tree DIR VERSION: what "mvn deploy -DaltDeploymentRepository=staging::file:DIR" leaves for a release.
release_tree() {
    local dir="$1" v="$2"
    put_file "$dir/$GROUP/synapse/$v/synapse-$v.pom"
    put_file "$dir/$GROUP/synapse/$v/synapse-$v-cyclonedx.json" '{"bomFormat":"CycloneDX"}'
    put_file "$dir/$GROUP/synapse/maven-metadata.xml" "<metadata><version>$v</version></metadata>"
    put_file "$dir/$GROUP/synapse-bom/$v/synapse-bom-$v.pom"
    put_file "$dir/$GROUP/synapse-bom/maven-metadata.xml" "<metadata><version>$v</version></metadata>"
    put_file "$dir/$GROUP/synapse-core/$v/synapse-core-$v.jar"
    put_file "$dir/$GROUP/synapse-core/$v/synapse-core-$v-sources.jar"
    put_file "$dir/$GROUP/synapse-core/$v/synapse-core-$v-javadoc.jar"
    put_file "$dir/$GROUP/synapse-core/$v/synapse-core-$v.pom"
    put_file "$dir/$GROUP/synapse-core/maven-metadata.xml" "<metadata><version>$v</version></metadata>"
}

# snapshot_tree DIR VERSION: the same for a SNAPSHOT (timestamped files plus version-level metadata).
snapshot_tree() {
    local dir="$1" v="$2" base="${2%-SNAPSHOT}" stamp="20261101.120000-1" aid
    for aid in synapse synapse-core; do
        put_file "$dir/$GROUP/$aid/$v/$aid-$base-$stamp.pom"
        if [ "$aid" = synapse-core ]; then
            put_file "$dir/$GROUP/$aid/$v/$aid-$base-$stamp.jar"
            put_file "$dir/$GROUP/$aid/$v/$aid-$base-$stamp-sources.jar"
        fi
        put_file "$dir/$GROUP/$aid/$v/maven-metadata.xml" "<metadata><version>$v</version></metadata>"
        put_file "$dir/$GROUP/$aid/maven-metadata.xml" "<metadata><versions><version>$v</version></versions></metadata>"
    done
}

# run_publish ARGS...: run the script with the fakes first on PATH; output in $OUT, exit status in $RC.
run_publish() {
    OUT="$CASE_DIR/out.txt"
    env PATH="$CASE_DIR/bin:$PATH" \
        FAKE_LOG_DIR="$CASE_DIR/log" \
        RUNNER_TEMP="$CASE_DIR/temp" \
        CENTRAL_API="https://central.invalid/api/v1/publisher" \
        CENTRAL_SNAPSHOTS="https://central.invalid/repository/maven-snapshots/" \
        CENTRAL_USERNAME="$USER_NAME" CENTRAL_TOKEN="$USER_TOKEN" \
        GPG_PRIVATE_KEY="-----BEGIN PGP PRIVATE KEY BLOCK-----fake" GPG_PASSPHRASE="s3cret pass" \
        POLL_INTERVAL_SECONDS=0 \
        "${EXTRA_ENV[@]+"${EXTRA_ENV[@]}"}" \
        bash "$SCRIPT" "$@" > "$OUT" 2>&1
    RC=$?
}

# ---------------------------------------------------------------------------------------------------------------------
# Assertions: each prints why it failed and returns 1.
# ---------------------------------------------------------------------------------------------------------------------

fail() {
    echo "    $*"
    if [ -n "${OUT:-}" ] && [ -f "$OUT" ]; then sed 's/^/      | /' "$OUT"; fi
    return 1
}
assert_rc() { [ "$RC" -eq "$1" ] || fail "expected exit status $1, got $RC"; }
assert_rc_nonzero() { [ "$RC" -ne 0 ] || fail "expected a non-zero exit status"; }
assert_output_contains() { grep -qF -- "$1" "$OUT" || fail "output lacks: $1"; }
assert_file_contains() { grep -qF -- "$2" "$1" || fail "${1##*/} lacks: $2"; }
assert_no_http() {
    [ ! -s "$CASE_DIR/log/curl.log" ] || fail "curl was called: $(head -n 5 "$CASE_DIR/log/curl.log")"
}
assert_gnupghome_removed() {
    local leftover
    leftover="$(find "$CASE_DIR/temp" -type d -name gnupg)"
    [ -z "$leftover" ] || fail "GNUPGHOME left behind: $leftover" || return 1
    grep -q "GNUPGHOME=$CASE_DIR/temp/" "$CASE_DIR/log/gpg.log" || fail "gpg did not run with a GNUPGHOME under RUNNER_TEMP"
}

# check_bundle ZIP: every primary file has .asc, .md5 and .sha1 (with correct sums), and nothing else is in it.
check_bundle() {
    local zip="$1" entries dir entry primary=0 sum
    [ -f "$zip" ] || fail "no bundle at $zip" || return 1
    entries="$(unzip -Z1 "$zip" | grep -v '/$')"
    if grep -q 'maven-metadata\.xml' <<< "$entries"; then fail "bundle contains maven-metadata.xml"; return 1; fi
    if grep -v "^$GROUP/" <<< "$entries" | grep -q .; then fail "bundle has entries outside $GROUP"; return 1; fi
    dir="$(mktemp -d "$TMP_ROOT/unzip.XXXXXX")"
    unzip -q "$zip" -d "$dir" || fail "cannot unzip $zip" || return 1
    while IFS= read -r entry; do
        case "$entry" in *.asc | *.md5 | *.sha1) continue ;; esac
        primary=$((primary + 1))
        for ext in asc md5 sha1; do
            grep -qxF -- "$entry.$ext" <<< "$entries" || { fail "missing $entry.$ext"; return 1; }
        done
        sum="$(md5sum < "$dir/$entry" | cut -d ' ' -f 1)"
        [ "$(cat "$dir/$entry.md5")" = "$sum" ] || { fail "wrong md5 for $entry"; return 1; }
        sum="$(sha1sum < "$dir/$entry" | cut -d ' ' -f 1)"
        [ "$(cat "$dir/$entry.sha1")" = "$sum" ] || { fail "wrong sha1 for $entry"; return 1; }
    done <<< "$entries"
    grep -q '\.jar$' <<< "$entries" || { fail "bundle has no jar"; return 1; }
    grep -q '\.pom$' <<< "$entries" || { fail "bundle has no pom"; return 1; }
    [ "$primary" -gt 0 ] || fail "bundle is empty"
}

bundle_path() { find "$CASE_DIR/temp" -name "synapse-$1.zip" -type f | head -n 1; }

# ---------------------------------------------------------------------------------------------------------------------
# Cases
# ---------------------------------------------------------------------------------------------------------------------

case_1_validate_accepts_release_tree() {
    new_case case1
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    run_publish validate "$CASE_DIR/staging" 2.0.0-M1
    assert_rc 0 && assert_no_http
}

case_2_validate_rejects_foreign_files() {
    local mode
    new_case case2-evil
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    put_file "$CASE_DIR/staging/org/evil/payload/1.0/payload-1.0.jar"
    for mode in validate release; do
        run_publish "$mode" "$CASE_DIR/staging" 2.0.0-M1
        assert_rc_nonzero || return 1
        assert_output_contains "org/evil/payload/1.0/payload-1.0.jar" || return 1
    done
    snapshot_tree "$CASE_DIR/snapshot" 2.0.0-SNAPSHOT
    put_file "$CASE_DIR/snapshot/org/evil/payload/1.0/payload-1.0.jar"
    run_publish snapshot "$CASE_DIR/snapshot" 2.0.0-SNAPSHOT
    assert_rc_nonzero || return 1
    assert_output_contains "org/evil/payload/1.0/payload-1.0.jar" || return 1
    assert_no_http || return 1
    [ ! -s "$CASE_DIR/log/gpg.log" ] || fail "gpg ran before validation finished" || return 1

    new_case case2-version
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    put_file "$CASE_DIR/staging/$GROUP/synapse-core/2.0.0-M2/synapse-core-2.0.0-M2.jar"
    for mode in validate release; do
        run_publish "$mode" "$CASE_DIR/staging" 2.0.0-M1
        assert_rc_nonzero || return 1
        assert_output_contains "synapse-core/2.0.0-M2/synapse-core-2.0.0-M2.jar" || return 1
    done
    # The directory alone decides, whatever the file is called.
    new_case case2-directory
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    put_file "$CASE_DIR/staging/$GROUP/synapse-core/2.0.0-M2/synapse-core-2.0.0-M1.jar"
    run_publish validate "$CASE_DIR/staging" 2.0.0-M1
    assert_rc_nonzero || return 1
    assert_output_contains "synapse-core/2.0.0-M2/synapse-core-2.0.0-M1.jar" || return 1
    assert_no_http
}

case_3_release_dry_run_bundle() {
    new_case case3
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    EXTRA_ENV=(DRY_RUN=1 CENTRAL_USERNAME= CENTRAL_TOKEN=)
    run_publish release "$CASE_DIR/staging" 2.0.0-M1
    EXTRA_ENV=()
    assert_rc 0 || return 1
    assert_no_http || return 1
    check_bundle "$(bundle_path 2.0.0-M1)" || return 1
    assert_file_contains "$CASE_DIR/log/gpg.log" "--pinentry-mode" || return 1
    assert_file_contains "$CASE_DIR/log/gpg.log" "passphrase: s3cret pass" || return 1
    assert_gnupghome_removed || return 1
    # The staging directory itself is left untouched.
    [ -z "$(find "$CASE_DIR/staging" -name '*.asc')" ] || fail "signatures were written into the staging directory"
}

case_4_release_uploads_and_polls() {
    local auth
    new_case case4
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    printf 'PENDING\nVALIDATING\nPUBLISHING\n' > "$CASE_DIR/log/states"
    run_publish release "$CASE_DIR/staging" 2.0.0-M1
    assert_rc 0 || return 1
    auth="$(printf '%s:%s' "$USER_NAME" "$USER_TOKEN" | base64 | tr -d '\n')"
    assert_file_contains "$CASE_DIR/log/curl.log" \
        "upload: https://central.invalid/api/v1/publisher/upload?publishingType=AUTOMATIC&name=synapse-2.0.0-M1" || return 1
    assert_file_contains "$CASE_DIR/log/curl.log" "header-file: Authorization: Bearer $auth" || return 1
    assert_file_contains "$CASE_DIR/log/curl.log" "arg: POST" || return 1
    grep -q "^arg: bundle=@.*synapse-2.0.0-M1.zip;type=application/octet-stream$" "$CASE_DIR/log/curl.log" \
        || fail "upload has no multipart part named bundle" || return 1
    [ "$(grep -c '^status: .*status?id=dep-0001' "$CASE_DIR/log/curl.log")" -eq 3 ] \
        || fail "expected 3 status polls" || return 1
    # Secrets never appear on a command line.
    if grep -E "^arg: .*($USER_TOKEN|$auth)" "$CASE_DIR/log/curl.log"; then fail "credentials passed as arguments"; return 1; fi
    if grep -F "arg: s3cret" "$CASE_DIR/log/gpg.log"; then fail "passphrase passed as an argument"; return 1; fi
    [ -z "$(find "$CASE_DIR/temp" -name 'auth-header*')" ] || fail "auth header file left behind" || return 1
    assert_gnupghome_removed
}

case_5_release_failed_deployment() {
    new_case case5
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    printf 'PENDING\nFAILED\n' > "$CASE_DIR/log/states"
    run_publish release "$CASE_DIR/staging" 2.0.0-M1
    assert_rc 1 || return 1
    assert_output_contains '"errors"' || return 1
    assert_output_contains "Invalid signature for file: synapse-core-2.0.0-M1.jar" || return 1
    assert_gnupghome_removed
}

case_6_release_timeout() {
    new_case case6
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    printf 'VALIDATING\n' > "$CASE_DIR/log/states"
    EXTRA_ENV=(PUBLISH_TIMEOUT_SECONDS=2 POLL_INTERVAL_SECONDS=1)
    run_publish release "$CASE_DIR/staging" 2.0.0-M1
    EXTRA_ENV=()
    assert_rc 1 || return 1
    assert_output_contains "Timed out"
}

case_7_check_tag_requires_main() {
    local repo="$TMP_ROOT/case7-repo" on_main off_main
    new_case case7
    g() { git -C "$repo" -c user.name=Test -c user.email=test@example.invalid -c commit.gpgsign=false \
        -c tag.gpgsign=false -c init.defaultBranch=main "$@"; }
    mkdir -p "$repo"
    g init -q || return 1
    g commit -q --allow-empty -m "on main" || return 1
    on_main="$(g rev-parse HEAD)"
    g update-ref refs/remotes/origin/main "$on_main"
    g switch -q -c side
    g commit -q --allow-empty -m "not on main"
    off_main="$(g rev-parse HEAD)"
    g tag v2.0.0-M1 "$off_main"
    g tag v2.0.0-M2 "$on_main"

    g switch -q --detach v2.0.0-M1
    OUT="$CASE_DIR/out.txt"
    (cd "$repo" && bash "$SCRIPT" check-tag v2.0.0-M1) > "$OUT" 2>&1
    RC=$?
    assert_rc_nonzero || return 1
    assert_output_contains "not on main" || return 1

    g switch -q --detach v2.0.0-M2
    (cd "$repo" && bash "$SCRIPT" check-tag v2.0.0-M2) > "$OUT" 2>&1
    RC=$?
    assert_rc 0 || return 1

    # The checked-out commit must be the tag's.
    (cd "$repo" && bash "$SCRIPT" check-tag v2.0.0-M1) > "$OUT" 2>&1
    RC=$?
    assert_rc_nonzero || return 1

    (cd "$repo" && bash "$SCRIPT" check-tag v2.0.0-beta) > "$OUT" 2>&1
    RC=$?
    assert_rc_nonzero || return 1
    assert_output_contains "is not a release tag"
}

case_8_snapshot_puts_version_files() {
    local base="https://central.invalid/repository/maven-snapshots/$GROUP" puts auth line n last_meta first_meta
    new_case case8
    snapshot_tree "$CASE_DIR/staging" 2.0.0-SNAPSHOT
    run_publish snapshot "$CASE_DIR/staging" 2.0.0-SNAPSHOT
    assert_rc 0 || return 1
    puts="$CASE_DIR/log/puts.log"
    # Every version-level file, and nothing else.
    [ "$(wc -l < "$puts")" -eq "$(find "$CASE_DIR/staging" -path '*/2.0.0-SNAPSHOT/*' -type f | wc -l)" ] \
        || fail "PUT count differs from the version-level file count: $(cat "$puts")" || return 1
    if grep -E "^PUT $base/[^/]+/maven-metadata\.xml" "$puts"; then fail "artifactId-level metadata uploaded"; return 1; fi
    assert_file_contains "$puts" "PUT $base/synapse-core/2.0.0-SNAPSHOT/synapse-core-2.0.0-20261101.120000-1.jar" || return 1
    assert_file_contains "$puts" "PUT $base/synapse-core/2.0.0-SNAPSHOT/synapse-core-2.0.0-20261101.120000-1.jar.sha1" || return 1
    # Per artifactId: artifacts, then checksums, then maven-metadata.xml (and its checksums) last.
    for aid in synapse synapse-core; do
        grep "^PUT $base/$aid/" "$puts" > "$CASE_DIR/aid.log"
        n="$(wc -l < "$CASE_DIR/aid.log")"
        first_meta="$(grep -n 'maven-metadata\.xml' "$CASE_DIR/aid.log" | head -n 1 | cut -d: -f1)"
        last_meta="$(grep -c 'maven-metadata\.xml' "$CASE_DIR/aid.log")"
        [ -n "$first_meta" ] && [ $((n - first_meta + 1)) -eq "$last_meta" ] \
            || fail "$aid: maven-metadata.xml is not uploaded last: $(cat "$CASE_DIR/aid.log")" || return 1
        sed -n "${first_meta}p" "$CASE_DIR/aid.log" | grep -q '/maven-metadata\.xml$' \
            || fail "$aid: metadata checksums before maven-metadata.xml" || return 1
        line="$(grep -n -E '\.(md5|sha1)$' "$CASE_DIR/aid.log" | grep -v maven-metadata | head -n 1 | cut -d: -f1)"
        if grep -n -E '\.(jar|pom)$' "$CASE_DIR/aid.log" | cut -d: -f1 | awk -v l="$line" '$1 > l { found = 1 } END { exit !found }'; then
            fail "$aid: an artifact is uploaded after a checksum: $(cat "$CASE_DIR/aid.log")"
            return 1
        fi
    done
    auth="$(printf '%s:%s' "$USER_NAME" "$USER_TOKEN" | base64 | tr -d '\n')"
    assert_file_contains "$CASE_DIR/log/curl.log" "header-file: Authorization: Basic $auth" || return 1
    if grep -F "arg: $USER_TOKEN" "$CASE_DIR/log/curl.log"; then fail "token passed as an argument"; return 1; fi
    # A release version is refused in snapshot mode.
    new_case case8-release
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    run_publish snapshot "$CASE_DIR/staging" 2.0.0-M1
    assert_rc_nonzero && assert_no_http
}

# Real GnuPG: generate a throwaway key and sign a staging directory in DRY_RUN; every signature must verify.
# Sets KEY_HOME, KEY_ARMOR and KEY_PASS.
make_test_key() {
    KEY_HOME="$(mktemp -d "$TMP_ROOT/key.XXXXXX")"
    REAL_GPG_HOMES+=("$KEY_HOME")
    KEY_PASS="test passphrase $$"
    chmod 700 "$KEY_HOME"
    printf '%s' "$KEY_PASS" | GNUPGHOME="$KEY_HOME" gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 0 \
        --quick-gen-key "Synapse Test <test@example.invalid>" ed25519 sign never || return 1
    KEY_ARMOR="$(printf '%s' "$KEY_PASS" | GNUPGHOME="$KEY_HOME" gpg --batch --pinentry-mode loopback \
        --passphrase-fd 0 --armor --export-secret-keys)" || return 1
}

sign_with_real_key() {
    local staging="$1" version="$2" zip dir asc
    command -v gpg > /dev/null || { fail "gpg is not installed"; return 1; }
    make_test_key || { fail "could not generate a test key"; return 1; }
    rm -f "$CASE_DIR/bin/gpg" "$CASE_DIR/bin/gpgconf"
    EXTRA_ENV=(DRY_RUN=1 CENTRAL_USERNAME= CENTRAL_TOKEN= "GPG_PRIVATE_KEY=$KEY_ARMOR" "GPG_PASSPHRASE=$KEY_PASS")
    run_publish release "$staging" "$version"
    EXTRA_ENV=()
    assert_rc 0 || return 1
    assert_no_http || return 1
    zip="$(bundle_path "$version")"
    check_bundle "$zip" || return 1
    dir="$(mktemp -d "$TMP_ROOT/verify.XXXXXX")"
    unzip -q "$zip" -d "$dir"
    while IFS= read -r asc; do
        GNUPGHOME="$KEY_HOME" gpg --batch --quiet --verify "$asc" "${asc%.asc}" 2> /dev/null \
            || { fail "bad signature: ${asc#"$dir"/}"; return 1; }
    done < <(find "$dir" -name '*.asc')
    [ -z "$(find "$CASE_DIR/temp" -type d -name gnupg)" ] || fail "GNUPGHOME left behind"
}

case_9_release_signs_with_real_key() {
    new_case case9
    release_tree "$CASE_DIR/staging" 2.0.0-M1
    sign_with_real_key "$CASE_DIR/staging" 2.0.0-M1
}

# After a milestone, release-please sets main to <next patch>-M<n>-SNAPSHOT; pull request builds stamp it further.
case_10_snapshot_after_milestone() {
    local base="https://central.invalid/repository/maven-snapshots/$GROUP" v
    for v in 2.0.1-M1-SNAPSHOT 2.0.1-M1-pr12-SNAPSHOT 2.0.0-RC1-SNAPSHOT; do
        new_case "case10-$v"
        snapshot_tree "$CASE_DIR/staging" "$v"
        run_publish snapshot "$CASE_DIR/staging" "$v"
        assert_rc 0 || return 1
        assert_file_contains "$CASE_DIR/log/puts.log" \
            "PUT $base/synapse-core/$v/synapse-core-${v%-SNAPSHOT}-20261101.120000-1.jar" || return 1
    done
    # Still strict: the qualifier comes before the pull request number, and only M<n> or RC<n>.
    for v in 2.0.1-pr12-M1-SNAPSHOT 2.0.1-beta1-SNAPSHOT 2.0.1-M-SNAPSHOT 2.0.1-M1-RC1-SNAPSHOT; do
        new_case "case10-bad-$v"
        snapshot_tree "$CASE_DIR/staging" "$v"
        run_publish snapshot "$CASE_DIR/staging" "$v"
        assert_rc_nonzero || return 1
        assert_no_http || return 1
    done
}

# DRY_RUN says what it would do, never that it published.
case_11_snapshot_dry_run() {
    new_case case11
    snapshot_tree "$CASE_DIR/staging" 2.0.0-SNAPSHOT
    EXTRA_ENV=(DRY_RUN=1 CENTRAL_USERNAME= CENTRAL_TOKEN=)
    run_publish snapshot "$CASE_DIR/staging" 2.0.0-SNAPSHOT
    EXTRA_ENV=()
    assert_rc 0 || return 1
    assert_no_http || return 1
    assert_output_contains "Would PUT https://central.invalid/repository/maven-snapshots/$GROUP/synapse-core/" || return 1
    assert_output_contains "Would publish 2.0.0-SNAPSHOT" || return 1
    if grep -q "^Published" "$OUT"; then fail "a dry run claims it published"; return 1; fi
}

# A find that fails part-way (for example on an unreadable directory) must fail the run, not shorten the file list.
case_12_find_failure_fails_the_run() {
    local real_find mode version
    real_find="$(command -v find)"
    for mode in validate release snapshot; do
        new_case "case12-$mode"
        cat > "$CASE_DIR/bin/find" << EOF
#!/usr/bin/env bash
"$real_find" "\$@"
echo "find: '\$1/unreadable': Permission denied" >&2
exit 1
EOF
        chmod +x "$CASE_DIR/bin/find"
        version=2.0.0-M1
        if [ "$mode" = snapshot ]; then
            version=2.0.0-SNAPSHOT
            snapshot_tree "$CASE_DIR/staging" "$version"
        else
            release_tree "$CASE_DIR/staging" "$version"
        fi
        run_publish "$mode" "$CASE_DIR/staging" "$version"
        assert_rc_nonzero || return 1
        assert_no_http || return 1
        [ ! -s "$CASE_DIR/log/gpg.log" ] || fail "gpg ran after find failed" || return 1
    done
}

case_13_release_requires_sources_and_javadoc_jars() {
    local mode missing
    for missing in sources javadoc; do
        new_case "case13-$missing"
        release_tree "$CASE_DIR/staging" 2.0.0-M1
        rm -f "$CASE_DIR/staging/$GROUP/synapse-core/2.0.0-M1/synapse-core-2.0.0-M1-$missing.jar"*
        for mode in validate release; do
            run_publish "$mode" "$CASE_DIR/staging" 2.0.0-M1
            assert_rc_nonzero || return 1
            assert_output_contains "Missing for $GROUP/synapse-core/2.0.0-M1/synapse-core-2.0.0-M1.jar: $GROUP/synapse-core/2.0.0-M1/synapse-core-2.0.0-M1-$missing.jar" || return 1
        done
        assert_no_http || return 1
        [ ! -s "$CASE_DIR/log/gpg.log" ] || fail "gpg ran before validation finished" || return 1
    done
    # A snapshot isn't validated by Central, and the snapshot build doesn't attach javadoc.
    new_case case13-snapshot
    snapshot_tree "$CASE_DIR/staging" 2.0.0-SNAPSHOT
    run_publish validate "$CASE_DIR/staging" 2.0.0-SNAPSHOT
    assert_rc 0
}

# ---------------------------------------------------------------------------------------------------------------------

if [ "${1:-}" = stage-check ]; then
    [ $# -eq 3 ] || { echo "usage: $0 stage-check STAGING_DIR VERSION" >&2; exit 2; }
    new_case stage-check
    staging="$(cd "$2" && pwd)"
    if sign_with_real_key "$staging" "$3"; then
        echo "PASS stage-check: $(unzip -Z1 "$(bundle_path "$3")" | grep -vc '/$') entries in the bundle"
        unzip -Z1 "$(bundle_path "$3")" | grep -v '/$' | sed 's/^/  /'
        exit 0
    fi
    echo "FAIL stage-check"
    exit 1
fi

[ -f "$SCRIPT" ] || echo "note: $SCRIPT does not exist"
EXTRA_ENV=()
passed=0 failed=0
for test_case in $(declare -F | awk '{print $3}' | grep '^case_' | sort -t_ -k2,2n); do
    if ("$test_case"); then
        echo "PASS $test_case"
        passed=$((passed + 1))
    else
        echo "FAIL $test_case"
        failed=$((failed + 1))
    fi
done
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
