# Flash Sale Queue

A worked implementation of a flash-sale admission queue — the pattern behind
"everyone wants the same 500 units in the same 100ms." Built on Spring Boot,
using [Palang](https://github.com/RRMADON26/palang) for the two concerns at
the checkout boundary: duplicate-request suppression and rate limiting.

**Status: planning.** No code yet — this repo currently holds the design and
a card-by-card build order. See the architecture study for the full
reasoning before any of the cards below:

📄 **[Architecture study](https://claude.ai/code/artifact/764e4b3f-0c21-48a4-8387-66650e1e0ad8)** — the problem, why a naive stock decrement races, the
waiting-room pattern, the atomic claim, and where Palang plugs in.

📄 **[Stack & deployment](https://claude.ai/code/artifact/18567724-3fd6-40bb-b686-f6ce1984a423)** — the exact dependency list, why each one and not an
alternative, the package layout, and the horizontal-scaling story.

## The shape of it

```
Client → Admission Gate → Queue (Redis ZSET) → Release Controller
                                                        ↓
                                              Reservation Store (atomic claim)
                                                        ↓
                                    Checkout (palang-idempotency + palang-ratelimit)
                                                        ↓
                                                    Order DB
```

## Build order

Tracked as GitHub issues under four milestones, in dependency order — each
should be provable before the next is worth starting:

1. **Reservation Store** — the correctness core. An atomic Redis claim,
   proven under real concurrency, then load-tested directly against Redis to
   find the real single-key throughput ceiling (see the stack doc's capacity
   section) — before anything else is built around it.
2. **Checkout + Palang** — the endpoint that talks to money, guarded by
   idempotency and rate limiting from the start.
3. **Admission Gate + Queue** — where the thundering herd actually lands,
   kept cheap and separate from checkout.
4. **Release Controller** — calibrates admission rate against checkout's
   real success/error rate. Built last since it's a tuning problem, not a
   binary correctness proof, and needs the first three to be real before it
   can be measured against anything.
5. **Demo UI (queue visualizer)** — a standalone, animated, simulated
   visualization of the mechanics: the thundering herd, the naive race
   condition actually overselling, and the atomic gate correctly rejecting
   the second request in real time. Deliberately *not* wired to the real
   backend, so it teaches the concept without waiting on milestones 1–4;
   wiring it to the real service afterward is its own stretch card.
6. **Production readiness** — Redis HA, a kill switch, real observability
   and alerting, a stock/order reconciliation job, chaos-testing the
   documented fail-open/fail-closed behaviour against a real dying Redis,
   and resolving user identity at checkout (surfaced while fixing a real
   reservation-token vulnerability in the architecture doc — see #2).

See the [project board](https://github.com/users/RRMADON26/projects/6) for the live card-by-card status.

## Licence

[Apache 2.0](LICENSE)
