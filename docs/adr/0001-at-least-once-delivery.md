# ADR 0001: At-least-once delivery

- Status: Accepted
- Date: 2026-08-06

## Context

The relay spans a database transaction, Kafka-compatible transport, and an
untrusted HTTP receiver. No atomic transaction can include all three systems.

## Decision

The platform provides at-least-once delivery. A delivery is claimed with a
database lease and processing token. Duplicate Kafka records are harmless
while a delivery is terminal or actively leased. Kafka offsets are
acknowledged only after the outcome is persisted.

The HTTP request includes stable delivery and event IDs so receivers can
deduplicate. A worker crash after the receiver accepts a request but before
the database update can cause the same request to be sent again.

## Consequences

- No accepted event is intentionally dropped after it is transactionally
  stored.
- Receivers must be idempotent by `X-Webhook-Id`.
- Exactly-once HTTP side effects are explicitly not promised.
- Lease expiry recovers abandoned work but can produce a duplicate after a
  long-running receiver request.
