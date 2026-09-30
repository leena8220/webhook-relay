# ADR 0002: PostgreSQL transactional outbox

- Status: Accepted
- Date: 2026-08-06

## Context

Writing an event to PostgreSQL and publishing directly to Kafka creates a
dual-write failure window: either side can succeed while the other fails.

## Decision

Event, delivery, and outbox rows are inserted in one PostgreSQL transaction.
The worker locks unpublished outbox rows with `FOR UPDATE SKIP LOCKED`,
publishes synchronously, and then marks them published. Retry scheduling and
dead-letter replay also create a new outbox dispatch in their state-change
transaction.

Publishing and marking cannot be atomic, so a crash can publish the same
outbox message more than once. The delivery state machine makes those
duplicates safe.

## Consequences

- API acceptance is independent of temporary broker availability.
- Database commit is the acceptance boundary.
- Outbox lag is observable and can be queried operationally.
- A high-throughput deployment would likely need partitioning, retention, and
  a more specialized publisher; this implementation intentionally keeps a
  bounded polling batch.
