# AkmAI service behavior contracts

This directory is the canonical documentation index for service-level behavior in AkmAI.

Every documented service/process must use the same compact contract:

1. **Process** — what the service/process does and where it starts/ends.
2. **Business Rules** — invariants, eligibility rules, authority/ownership rules, limits, ordering, transaction and concurrency requirements.
3. **Positive Cases** — expected successful scenarios.
4. **Negative Cases** — validation failures, stale authority, conflicts, timeouts, partial-failure prevention and other rejected scenarios.
5. **Tests** — concrete unit/integration/failure-injection tests that prove the rules above.

## Documentation rule

A change that modifies business behavior is incomplete until the corresponding service contract is updated together with tests.

For stateful/concurrent processes, document additionally:

- transaction boundary;
- locking/order guarantees;
- fencing/authority semantics;
- timeout semantics;
- idempotency/retry behavior;
- partial-write prevention and rollback expectations.

## Inventory

The inventory will be maintained in `service-inventory.md` and expanded service-by-service from the current codebase.

Use `service-template.md` as the required structure for each service contract.
