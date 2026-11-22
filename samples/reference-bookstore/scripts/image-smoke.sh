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
# Smoke test of the reference bookstore's container image, as a platform runs it: PostgreSQL and the image on one
# Docker network, the image in the dev environment (ENV=dev) with the books store's connection in the variables a
# platform sets (SYNAPSE_DATA_STORES_BOOKS_URL, _USERNAME, _PASSWORD). It waits until /health is UP, checks that dev is
# the one active profile, creates a book with POST /books, and removes everything it started, also when it fails.
# With a build log, it first prints the build's training run lines, and fails without the line of the cache the JVM
# wrote (Java 25), or, for a Paketo build on an earlier Java (CDS), without Paketo's training run line.
#
# Usage, from anywhere (Docker on PATH; in Git Bash on Windows, Docker Desktop's bin directory is found by itself):
#   samples/reference-bookstore/scripts/image-smoke.sh <image> [build-log]
#     image      the image to run, for example service-bookstore:2.0.0-SNAPSHOT, the name ./mvnw -Pimage ... package
#                gives it (synapse-parent's image profile), or the tag of docker build
#     build-log  the output of that build, to print the training lines from
#
# Environment: SMOKE_JAVA_TOOL_OPTIONS, when set, is passed to the image as JAVA_TOOL_OPTIONS: -XX:AOTMode=on (Java 25)
# or -Xshare:on (Java 21, CDS), with which a JVM that can't use its cache or archive exits instead of starting
# without it. SMOKE_DOCKER_OPTIONS, when set, adds docker run options for the image, split on spaces, for example
# "--memory=512m --cpus=1 -e BPL_JVM_THREAD_COUNT=50" to run it as a small pod.

set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "Usage: $0 <image> [build-log]" >&2
    exit 2
fi
IMAGE="$1"
BUILD_LOG="${2:-}"

# Git Bash on Windows: Docker Desktop's CLI is often not on PATH, and MSYS would rewrite the container paths.
if ! command -v docker > /dev/null 2>&1; then
    for dir in "/c/Program Files/Docker/Docker/resources/bin" \
        "${LOCALAPPDATA:+$(cygpath -u "$LOCALAPPDATA" 2> /dev/null || true)/Programs/DockerDesktop/resources/bin}"; do
        if [ -n "$dir" ] && [ -d "$dir" ]; then
            PATH="$dir:$PATH"
            break
        fi
    done
fi
export MSYS_NO_PATHCONV=1

# The PostgreSQL image of compose.yaml and the tests (SynapseContainers.POSTGRES_IMAGE), by digest.
POSTGRES_IMAGE="postgres:18.6-alpine@sha256:123b17998ccaf47e53b502e8c6a1e79afd8f579c6f3eec83c563da026f957395"
RUN_ID="bookstore-smoke-$$"
NETWORK="$RUN_ID"
DB="$RUN_ID-db"
APP="$RUN_ID-app"
# A throwaway password for this run's database, which no other container can reach.
DB_PASSWORD="smoke-$RANDOM$RANDOM"

cleanup() {
    docker rm -f -v "$APP" "$DB" > /dev/null 2>&1 || true
    docker network rm "$NETWORK" > /dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "image-smoke: $*" >&2
    if docker container inspect "$APP" > /dev/null 2>&1; then
        echo "--- $APP logs ---" >&2
        docker logs "$APP" >&2 2>&1 || true
    fi
    exit 1
}

if [ -n "$BUILD_LOG" ]; then
    # Paketo logs the start of its training run and the Java version it installs. From Java 25 on the training run
    # records an AOT cache, and the JVM logs the cache it wrote, as it does for the Dockerfile's training run (which
    # has no Paketo line); before Java 25 Paketo records a CDS archive, for which the JVM logs no line.
    training=$(grep -E "Training run will use|AOTCache creation is complete" "$BUILD_LOG" || true)
    java_version=$(grep -oE "Using Java version [0-9]+" "$BUILD_LOG" | head -n 1 | grep -oE "[0-9]+$" || true)
    if [ -n "$java_version" ] && [ "$java_version" -lt 25 ]; then
        required="Training run will use"
    else
        required="AOTCache creation is complete"
    fi
    grep -F "$required" "$BUILD_LOG" > /dev/null || fail "no '$required' line in $BUILD_LOG"
    echo "Training, from $BUILD_LOG:"
    echo "$training"
fi

docker image inspect "$IMAGE" > /dev/null 2>&1 || fail "no image $IMAGE: build it first"

docker network create "$NETWORK" > /dev/null
docker run -d --name "$DB" --network "$NETWORK" \
    -e POSTGRES_DB=bookstore -e POSTGRES_USER=bookstore -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    "$POSTGRES_IMAGE" > /dev/null

# Over TCP: the image's first-start initialization runs a server on the Unix socket only, then restarts it.
for _ in $(seq 1 60); do
    if docker exec "$DB" pg_isready -h 127.0.0.1 -U bookstore -d bookstore > /dev/null 2>&1; then
        break
    fi
    sleep 1
done
docker exec "$DB" pg_isready -h 127.0.0.1 -U bookstore -d bookstore > /dev/null || fail "PostgreSQL didn't start"

app_env=(-e ENV=dev
    -e SYNAPSE_DATA_STORES_BOOKS_URL="jdbc:postgresql://$DB:5432/bookstore"
    -e SYNAPSE_DATA_STORES_BOOKS_USERNAME=bookstore
    -e SYNAPSE_DATA_STORES_BOOKS_PASSWORD="$DB_PASSWORD")
if [ -n "${SMOKE_JAVA_TOOL_OPTIONS:-}" ]; then
    app_env+=(-e JAVA_TOOL_OPTIONS="$SMOKE_JAVA_TOOL_OPTIONS")
fi
# The REST port on a free port of the loopback interface only.
read -r -a docker_options <<< "${SMOKE_DOCKER_OPTIONS:-}"
docker run -d --name "$APP" --network "$NETWORK" -p 127.0.0.1::8080 ${docker_options[@]+"${docker_options[@]}"} "${app_env[@]}" "$IMAGE" > /dev/null
# A container that exits at once (a JVM that rejects its options or its cache) has no port: fail with its logs, which
# the cleanup would otherwise delete with the container.
PORT=$(docker port "$APP" 8080/tcp 2> /dev/null | head -n 1 | sed 's/.*://') || PORT=""
[ -n "$PORT" ] || fail "the image exited before it published port 8080"
BASE="http://127.0.0.1:$PORT"

health=""
for _ in $(seq 1 120); do
    [ "$(docker inspect -f '{{.State.Running}}' "$APP")" = "true" ] || fail "the image exited before /health was UP"
    health=$(curl -s --max-time 5 "$BASE/health" || true)
    case "$health" in
        *'"status":"UP"'*) break ;;
    esac
    sleep 1
done
case "$health" in
    *'"status":"UP"'*) echo "GET /health: $health" ;;
    *) fail "/health was not UP within 120 s: $health" ;;
esac

# The environment is ENV's, and nothing of the build's training run remains: dev is the one active profile.
logs=$(docker logs "$APP" 2>&1)
echo "$logs" | grep -F 'The following 1 profile is active: \"dev\"' > /dev/null \
    || fail "dev is not the one active profile"
if echo "$logs" | grep -F '\"build\"' > /dev/null; then
    fail "the build environment is active at run time"
fi
echo "Active profiles: dev only"

isbn="978-$(printf '%010d' $((RANDOM * 32768 + RANDOM)))"
response=$(curl -s --max-time 10 -w '\n%{http_code}' -X POST "$BASE/books" -H 'Content-Type: application/json' \
    -d "{\"isbn\": \"$isbn\", \"title\": \"The Dispossessed\", \"author\": \"Ursula K. Le Guin\", \"publishedYear\": 1974}")
status=${response##*$'\n'}
body=${response%$'\n'*}
[ "$status" = "201" ] || fail "POST /books answered $status: $body"
echo "POST /books: $status $body"
echo "image-smoke: $IMAGE passed"
