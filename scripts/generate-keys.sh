#!/usr/bin/env bash
# Regenerates the RS256 key pair used to sign/verify JWTs.
#
# The repo ships with a placeholder dev key pair so `mvn verify` and
# `docker compose up` work out of the box. Run this script whenever you
# want a fresh key pair (e.g. before any real deployment) -- never reuse
# the committed dev keys outside your own machine.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AUTH_KEYS_DIR="$ROOT_DIR/auth/src/main/resources/keys"
GATEWAY_KEYS_DIR="$ROOT_DIR/gateway/src/main/resources/keys"
INVENTORY_KEYS_DIR="$ROOT_DIR/inventory/src/main/resources/keys"
INVENTORY_TEST_KEYS_DIR="$ROOT_DIR/inventory/src/test/resources/keys"
ORDER_KEYS_DIR="$ROOT_DIR/order/src/main/resources/keys"
ORDER_TEST_KEYS_DIR="$ROOT_DIR/order/src/test/resources/keys"

mkdir -p "$AUTH_KEYS_DIR" "$GATEWAY_KEYS_DIR" "$INVENTORY_KEYS_DIR" "$INVENTORY_TEST_KEYS_DIR" "$ORDER_KEYS_DIR" "$ORDER_TEST_KEYS_DIR"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TMP_DIR/private_key.pem"
openssl rsa -pubout -in "$TMP_DIR/private_key.pem" -out "$TMP_DIR/public_key.pem"

cp "$TMP_DIR/private_key.pem" "$AUTH_KEYS_DIR/private_key.pem"
cp "$TMP_DIR/public_key.pem" "$AUTH_KEYS_DIR/public_key.pem"
cp "$TMP_DIR/public_key.pem" "$GATEWAY_KEYS_DIR/public_key.pem"
cp "$TMP_DIR/public_key.pem" "$INVENTORY_KEYS_DIR/public_key.pem"
cp "$TMP_DIR/public_key.pem" "$ORDER_KEYS_DIR/public_key.pem"
# Test-only keys let a service's tests mint fixture JWTs without running the
# real auth service. Never copy the private key into a service's main resources.
cp "$TMP_DIR/private_key.pem" "$INVENTORY_TEST_KEYS_DIR/private_key.pem"
cp "$TMP_DIR/public_key.pem" "$INVENTORY_TEST_KEYS_DIR/public_key.pem"
cp "$TMP_DIR/private_key.pem" "$ORDER_TEST_KEYS_DIR/private_key.pem"
cp "$TMP_DIR/public_key.pem" "$ORDER_TEST_KEYS_DIR/public_key.pem"

echo "New RSA key pair written to:"
echo "  $AUTH_KEYS_DIR/private_key.pem  (auth service only -- never share this)"
echo "  $AUTH_KEYS_DIR/public_key.pem"
echo "  $GATEWAY_KEYS_DIR/public_key.pem"
echo "  $INVENTORY_KEYS_DIR/public_key.pem"
echo "  $ORDER_KEYS_DIR/public_key.pem"
echo "  $INVENTORY_TEST_KEYS_DIR/*.pem (test fixtures only)"
echo "  $ORDER_TEST_KEYS_DIR/*.pem (test fixtures only)"
