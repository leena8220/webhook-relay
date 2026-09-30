# ADR 0003: Bounded retries with full jitter

- Status: Accepted
- Date: 2026-08-06

## Context

Immediate retries amplify receiver or network incidents. Retrying permanent
client errors wastes capacity, while never retrying transient failures loses
deliveries.

## Decision

I/O failures, HTTP 408, 425, 429, and 5xx responses are retryable. Other
non-2xx responses are terminal. Retry delay uses capped exponential backoff
with full jitter:

`random(0, min(maxDelay, baseDelay * 2^(attempt - 1)))`

After the configured maximum attempt count, the delivery and its reason are
persisted as dead-lettered. Replay resets the attempt budget for the same
delivery ID and emits a new outbox dispatch.

## Consequences

- Jitter prevents synchronized retry storms.
- Receiver redirects are not followed and are terminal.
- `Retry-After` is not currently interpreted.
- Operators can inspect and explicitly replay dead letters without mutating
  the original event payload.
