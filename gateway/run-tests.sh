#!/usr/bin/env bash
# Runs all gateway tests in Docker: PostgreSQL 16, the real C++ engine image, and Maven on one temporary network.
# Usage (from the repo root): gateway/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."
NET=is-gw-test
cleanup() { docker rm -f is-gw-pg is-gw-engine >/dev/null 2>&1 || true; docker network rm $NET >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network create $NET >/dev/null
docker build -q -f engine/Dockerfile --target runtime -t is-engine . >/dev/null
docker run -d --name is-gw-pg --network $NET -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=inferencestack_test postgres:16 >/dev/null
docker run -d --name is-gw-engine --network $NET is-engine >/dev/null
for i in $(seq 1 30); do docker exec is-gw-pg pg_isready -U postgres -q && break; sleep 1; done
docker run --rm --network $NET -v "$PWD":/repo -v is-m2:/root/.m2 -w /repo/gateway \
  -e TEST_DB_URL=jdbc:postgresql://is-gw-pg:5432/inferencestack_test -e TEST_DB_USER=postgres -e TEST_DB_PASSWORD=postgres \
  -e REAL_ENGINE_URL=http://is-gw-engine:8081 -e MODEL_TEST_DATA=/repo/model/test_data \
  maven:3.9-eclipse-temurin-21 mvn -B verify -Dsurefire.failIfNoSpecifiedTests=false
