#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
API_PORT="${API_PORT:-8080}"

echo "Starting PostgreSQL, Redpanda, API, worker, and example receiver..."
docker compose up --build --detach --wait

registration="$(
  curl --fail --silent --show-error \
    --request POST "http://localhost:${API_PORT}/v1/endpoints" \
    --header 'Content-Type: application/json' \
    --data '{
      "url": "http://receiver:8080/webhook",
      "description": "Local signature-verifying demo receiver",
      "signingSecret": "local-demo-signing-secret-32-characters-minimum"
    }'
)"
endpoint_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["endpoint"]["id"])' <<<"$registration")"

accepted="$(
  curl --fail --silent --show-error \
    --request POST "http://localhost:${API_PORT}/v1/endpoints/${endpoint_id}/events" \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: demo-$(date +%s)" \
    --header 'X-Correlation-ID: local-demo' \
    --data '{"type":"demo.created","message":"hello from Webhook Relay"}'
)"
delivery_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' <<<"$accepted")"

echo "Accepted delivery ${delivery_id}; waiting for receiver acknowledgement..."
for _ in $(seq 1 30); do
  delivery="$(curl --fail --silent --show-error "http://localhost:${API_PORT}/v1/deliveries/${delivery_id}")"
  status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$delivery")"
  if [[ "$status" == "DELIVERED" ]]; then
    echo "$delivery" | python3 -m json.tool
    echo
    echo "Verified receiver output:"
    docker compose logs --no-log-prefix receiver
    exit 0
  fi
  if [[ "$status" == "DEAD_LETTERED" ]]; then
    echo "$delivery" | python3 -m json.tool
    echo "Delivery was dead-lettered" >&2
    exit 1
  fi
  sleep 1
done

echo "Timed out waiting for delivery ${delivery_id}" >&2
exit 1
