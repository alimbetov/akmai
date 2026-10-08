# <Service / Process name>

## Purpose

Short description of the business responsibility and its boundary.

## Entry points

- Public/API/event/scheduled entry points.
- Main application/service classes.
- Persistence or external dependencies.

## Process

Describe the process step-by-step from input to terminal state.

## Business Rules

| ID | Rule | Enforcement |
|---|---|---|
| BR-01 | <Invariant / eligibility / authority rule> | <class/method/db constraint> |

For stateful or concurrent processes, explicitly cover transaction boundaries, lock ordering, fencing/authority, timeouts, retries/idempotency, and rollback/partial-write guarantees.

## Positive Cases

| ID | Preconditions | Action | Expected result | Tests |
|---|---|---|---|---|
| POS-01 | ... | ... | ... | `TestClass#testName` |

## Negative Cases

| ID | Preconditions / fault | Action | Expected rejection / rollback | Tests |
|---|---|---|---|---|
| NEG-01 | ... | ... | ... | `TestClass#testName` |

## Tests

### Unit

- `...`

### Integration

- `...`

### Failure injection / concurrency

- `...`

## Observability

- Metrics:
- Logs:
- Alerts / operational signals:

## Known gaps

List only confirmed gaps. Do not mix proposed features with current behavior.

## Change rule

Any PR that changes a Business Rule, Positive/Negative Case, transaction/concurrency behavior, or failure semantics must update this contract and its tests in the same PR.
