#!/usr/bin/env bash
# Creates a local .env (gitignored) with a random DB password and a random API key; only the key's SHA-256 goes to the gateway.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then echo ".env already exists; leaving it untouched."; exit 0; fi
rand() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
API_KEY="$(rand 40)"
{
  echo "POSTGRES_PASSWORD=$(rand 32)"
  echo "GATEWAY_API_KEY=$API_KEY"
  echo "GATEWAY_API_KEY_SHA256=$(printf '%s' "$API_KEY" | shasum -a 256 | cut -d' ' -f1)"
} > .env
chmod 600 .env
echo "Created .env (local only, never commit it). Your API key is in .env as GATEWAY_API_KEY."
