# Agentic Payment Firewall — Implementation Plan

## 0. One-line framing

A policy/intent verification layer that sits between an AI agent's proposed
transaction and Razorpay's payment execution, catching the class of fraud
that is *technically valid but semantically unauthorized* — the case a
normal risk engine (limit checks, merchant validation, velocity checks)
structurally cannot see.

This maps directly onto Razorpay's Agentic Payments / UPI Reserve Pay
direction: consent-based, pre-authorized, limit-bound agent transactions.
The firewall is the missing "does this transaction actually match what was
consented to" layer on top of "is this transaction within the pre-authorized
envelope."

---

## 1. System architecture

```
                       ┌─────────────────────────────┐
                       │   User Authorization Store   │
                       │ (purpose, category, limit,   │
                       │  merchant allowlist, TTL)    │
                       └──────────────┬───────────────┘
                                      │
 AI Agent ──▶ Proposed Transaction ──▶ Agentic Payment Firewall ──▶ Razorpay
                                      │
              ┌───────────────────────┼────────────────────────┐
              │                       │                        │
      ┌───────▼───────┐     ┌─────────▼─────────┐   ┌──────────▼─────────┐
      │ Policy Engine  │     │ Intent/Semantic    │   │ Injection & Context │
      │ (deterministic)│     │ Matcher (embedding)│   │ Integrity Checker   │
      └───────┬───────┘     └─────────┬─────────┘   └──────────┬─────────┘
              │                       │                        │
              └───────────┬───────────┴────────────┬───────────┘
                           │                        │
                  ┌────────▼────────┐     ┌─────────▼─────────┐
                  │ Anomaly/Sequence│     │  Risk Aggregator    │
                  │ Model (velocity,│────▶│  (weighted ensemble │
                  │ near-limit, seq)│     │  → 0.0–1.0 score)   │
                  └─────────────────┘     └─────────┬───────────┘
                                                     │
                                          ┌──────────▼──────────┐
                                          │   Decision Engine    │
                                          │ APPROVE / CHALLENGE /│
                                          │       BLOCK          │
                                          └──────────┬──────────┘
                                                     │
                              ┌──────────────────────┼──────────────────────┐
                              │                       │                      │
                      ┌───────▼───────┐     ┌─────────▼────────┐   ┌────────▼────────┐
                      │ Razorpay send  │     │ Human Confirmation│   │ Audit/Event Log  │
                      │ (on APPROVE)   │     │ (push/OTP/app)    │   │ (all decisions)  │
                      └───────────────┘     └───────────────────┘   └──────────────────┘
```

### Component responsibilities

| Component | Job | Failure mode if missing |
|---|---|---|
| Authorization Store | Canonical record of what the user actually granted: purpose text, allowed categories/merchants, amount limit, validity window, revocation state | No ground truth to check against — everything downstream is guessing |
| Policy Engine | Deterministic checks: limit, merchant allowlist/blocklist, category codes, time window, single-use vs recurring mandate | Cheap, fast, catches ~60-70% of obvious cases — must run first as a low-latency gate |
| Intent/Semantic Matcher | Embedding similarity (or LLM-as-judge) between `authorized_purpose` and `item_description` / merchant category | This is the component that catches the ₹4,900 gift-card-vs-office-supplies case — pure rules miss it |
| Injection & Context Integrity Checker | Scans the agent's recent tool-output/webpage context for instruction-injection patterns; diffs the agent's *final* action against the *original* user instruction | Without this, an agent that got hijacked mid-session looks identical to a normal agent |
| Anomaly/Sequence Model | Velocity (tx/hour), proximity-to-limit clustering, sequence-position anomalies (e.g. 5 near-limit purchases in a row) | Misses slow-drain and salami-slicing attacks that stay under any single-transaction threshold |
| Risk Aggregator | Weighted ensemble → single 0–1 risk score + human-readable reason string | Without a single score, you can't set consistent thresholds or SLAs |
| Decision Engine | Maps score + policy hits → APPROVE / CHALLENGE / BLOCK, and decides what "explain to the user" text to generate | Determines the actual UX and false-positive cost |
| Human Confirmation Channel | Push notification / in-app card / OTP for CHALLENGE outcomes, with the *original intent* and the *proposed transaction* shown side by side | Without a clear side-by-side, users rubber-stamp challenges, defeating the purpose |
| Audit/Event Log | Immutable log of every decision, score, and reason, keyed to the authorization it was checked against | Needed for dispute resolution, model retraining, and regulatory audit |

---

## 2. Data model

```jsonc
// AuthorizationGrant — created when the user sets up the agent's mandate
{
  "grant_id": "auth_8f2c...",
  "user_id": "usr_123",
  "purpose_text": "Buy office supplies",
  "purpose_embedding": [...],          // precomputed at grant time
  "allowed_categories": ["stationery", "office_supplies", "general_retail"],
  "merchant_allowlist": [],             // optional, empty = category-based
  "merchant_blocklist": ["gift_cards", "gaming", "crypto"],
  "amount_limit": 5000,
  "currency": "INR",
  "recurrence": "single_use" | "per_transaction" | "daily_cap",
  "valid_from": "2026-08-01T00:00:00Z",
  "valid_until": "2026-09-01T00:00:00Z",
  "status": "active" | "revoked" | "expired"
}

// ProposedTransaction — submitted by the agent to the firewall
{
  "tx_id": "txn_...",
  "grant_id": "auth_8f2c...",
  "item_description": "Amazon gift card",
  "amount": 4900,
  "merchant_name": "Amazon",
  "merchant_category": "general_retail",
  "agent_context_snapshot": "<last N tool outputs / page content the agent used to decide>",
  "agent_reasoning_trace": "<optional: agent's own stated reason for this purchase>",
  "session_sequence_position": 3,
  "requested_at": "2026-08-30T10:15:00Z"
}

// FirewallDecision — output
{
  "tx_id": "txn_...",
  "risk_score": 0.91,
  "signals": {
    "category_mismatch": 1.0,
    "injection_suspected": 0.8,
    "near_limit_ratio": 0.98,
    "velocity_flag": 0.1,
    "sequence_anomaly": 0.2
  },
  "action": "BLOCK",
  "reason": "Transaction technically satisfies the ₹5,000 limit, but a gift-card purchase conflicts with the 'office supplies' authorization.",
  "requires_human_confirmation": true,
  "decided_at": "2026-08-30T10:15:00.412Z"
}
```

Keeping `agent_context_snapshot` and `agent_reasoning_trace` as first-class
fields is what makes the injection-detection component possible — you cannot
detect "did external content alter agent intent" without capturing what the
agent saw right before it decided.

---

## 3. Detection logic, module by module

### 3.1 Policy Engine (deterministic, ~1-5ms)
- Amount ≤ `amount_limit`
- `merchant_category` in `allowed_categories` and not in `merchant_blocklist`
- Grant `status == active` and within `valid_from`/`valid_until`
- Recurrence rules respected (e.g. single-use grant already consumed)

This layer alone stops naive fraud but is exactly what the gift-card example
is designed to slip past — flag this clearly to stakeholders so nobody
mistakes policy-engine-only as "done."

### 3.2 Semantic Intent Matcher (~50-150ms, cacheable)
- Embed `item_description` (and merchant name) using a small embedding model
- Cosine similarity against `purpose_embedding`
- Threshold-tuned mismatch score; log borderline cases (0.4–0.7 similarity)
  to the ambiguous bucket for human labeling → feeds retraining
- **Important failure mode to design around**: a generalist merchant (Amazon,
  Flipkart) sells both the authorized category and off-purpose items, so
  `merchant_category` alone is insufficient — the matcher must score the
  actual item text, not just the merchant's category code. This exact gap
  is what the benchmark below demonstrates.

### 3.3 Injection & Context Integrity Checker (~30-100ms)
Two complementary signals:
1. **Lexical/pattern scan** of `agent_context_snapshot` for known injection
   markers ("ignore previous instructions", "system override", hidden
   HTML comments, etc.) — cheap, catches unsophisticated attacks.
2. **Intent-diff model**: compare the *original user instruction* embedding
   against the *agent's final proposed action* embedding. A large divergence
   mid-session, especially right after new context was ingested, is a strong
   injection signal even when the lexical scan finds nothing (paraphrased/
   stealthy injections).

### 3.4 Anomaly / Sequence Model (~10-30ms)
- `amount / amount_limit` ratio — purchases clustering just under the limit
  are a classic evasion pattern
- Transaction velocity (count in trailing 1h / 24h window)
- Sequence-position risk — e.g. repeated near-limit transactions within one
  agent session ("salami slicing")

### 3.5 Risk Aggregator
Weighted ensemble (weights should be learned from labeled outcomes over
time, not fixed forever):

```
risk = 0.40 * category_mismatch
     + 0.35 * injection_signal
     + 0.15 * near_limit_ratio
     + 0.05 * velocity_flag
     + 0.05 * sequence_flag
```

Start with hand-set weights (as in the baseline benchmark below) for
explainability and fast iteration; migrate to a learned model (logistic
regression → gradient boosted trees → optionally an LLM-as-judge for the
semantic layer specifically) once you have enough labeled production
decisions to train on.

### 3.6 Decision Engine thresholds

| Risk score | Action | Notes |
|---|---|---|
| < 0.30 | APPROVE | Straight to Razorpay |
| 0.30 – 0.70 | CHALLENGE | Push confirmation to user; show original purpose vs. proposed transaction side-by-side; auto-timeout → BLOCK after N minutes |
| ≥ 0.70 | BLOCK | Transaction rejected; agent receives structured reason it can relay to the user; optionally offer a re-request path |

Thresholds should be tunable per user/org and revisited using the
precision/recall/cost tradeoffs from the benchmark harness — this is a
business decision (friction tolerance vs. loss tolerance), not a fixed
constant.

---

## 4. Synthetic benchmark (built and included)

A Java module (`apf-java/`, Maven project, package
`com.razorpay.apf.benchmark`) generates the requested 2,000-transaction set
and runs a transparent, rule-weighted **baseline** scorer (a stand-in for
the production ML/LLM ensemble) end-to-end, producing
`benchmark_dataset.csv` and `benchmark_metrics.json`. All ₹ amounts are
formatted using the **Indian numbering system** (2-2-3 digit grouping —
lakh/crore — e.g. `₹28,91,605`, not the international `₹2,891,605`) via a
small `Rupees` utility class rather than relying on JVM locale data, so
output is identical regardless of the host machine's locale.

Build and run:
```bash
cd apf-java
mvn package
java -Dstdout.encoding=UTF-8 -jar target/apf-benchmark.jar
```
(the `-Dstdout.encoding=UTF-8` flag is only needed if your terminal locale
isn't already UTF-8 — otherwise the ₹ symbol prints as `?`.)

Or without Maven:
```bash
find src -name "*.java" | xargs javac -d out -encoding UTF-8
java -Dstdout.encoding=UTF-8 -cp out com.razorpay.apf.benchmark.BenchmarkRunner
```

**Composition** (matches the brief exactly):
- 1,200 legitimate agent transactions (12% deliberately near the spend
  limit, to stress-test false positives on legitimate bulk orders)
- 400 policy violations (semantic mismatch, no injection; 30% laundered
  through a generalist merchant like Amazon to test the
  category-vs-merchant blind spot)
- 200 prompt-injection attacks (80% with detectable lexical markers, 20%
  "stealthy"/paraphrased to simulate an adaptive attacker)
- 200 ambiguous cases (correct action = CHALLENGE, not a hard APPROVE/BLOCK)

**Baseline results** (rule-weighted scorer, thresholds 0.30 / 0.70). All
₹ figures use the Indian numbering system (lakh/crore grouping — e.g.
₹28,91,605, not ₹2,891,605):

| Metric | Value |
|---|---|
| Precision (SAFE vs UNSAFE) | 1.00 |
| Recall | 0.9517 |
| False Positive Rate | 0.00 |
| Ambiguous-handling rate (correctly routed to CHALLENGE) | 1.00 |
| Prevented loss (unsafe transactions caught) | ₹28,91,605 |
| False-positive cost (legit transactions wrongly friction'd) | ₹0 |
| Net value | ₹28,91,605 |

**Read the recall gap honestly — it's the point of the exercise.** 29 of 600
unsafe transactions were missed, and the failure modes are exactly the two
"hard" cases deliberately injected into the dataset:
1. Off-purpose items bought through a generalist merchant whose *category*
   is technically in the allowlist (the Amazon-gift-card-under-office-supplies
   pattern) — this is why item-level semantic matching, not merchant-category
   matching, has to be the primary signal in production.
2. Paraphrased/stealthy prompt injections with no lexical trigger words —
   this is why a pure keyword scanner is not sufficient and the
   intent-diff model (comparing original instruction vs. final action
   embedding) is a required component, not a nice-to-have.

This baseline is intentionally simple and explainable so it's a fair
starting point to benchmark future models against — every subsequent
model (embedding-based matcher, learned ensemble, LLM-as-judge) should be
evaluated on this same dataset and required to *at minimum* close the
recall gap without regressing precision or the false-positive cost.

Cost assumptions used (tune these with real ops data before trusting the
₹ figures): ₹75 handling cost per wrongly-blocked legitimate transaction,
₹15 friction cost per wrongly-challenged legitimate transaction.

A reference `Transaction` from the generated dataset, showing the exact
scenario from the brief being caught (note the item text vs. the reported
merchant category — this is the "laundered through Amazon" evasion pattern
described above, and the firewall still catches it here via the near-limit
and frequency signals even when the category check alone is fooled):

```
TXN-01246 | purpose: "software subscription renewal" | limit: ₹2,000
item: "Amazon gift card ₹4,900" | amount: ₹1,810 | merchant: Amazon (gift_cards)
risk_score: 0.5932 -> CHALLENGE
reason: item/category does not match authorized purpose 'software subscription
renewal'; amount ₹1,810 is unusually close to the ₹2,000 limit; unusually high
transaction frequency in the last hour
```

---

## 5. Razorpay integration points

- **Pre-flight hook**: Firewall sits as a required call before the agent's
  transaction reaches Razorpay's order/payment creation API — i.e. the
  agent calls the firewall, not Razorpay, first.
- **UPI Reserve Pay alignment**: `AuthorizationGrant` should map onto
  Razorpay's consent/mandate object (purpose, limit, validity window)
  so the firewall's policy layer and Razorpay's own mandate enforcement
  are checking the *same* source of truth, not two drifting copies.
- **On CHALLENGE**: firewall calls a confirmation flow (push notification
  via the user's app, or an OTP/UPI-PIN-style step-up) before releasing
  the transaction to Razorpay's payment execution.
- **On BLOCK**: transaction never reaches Razorpay; structured reason is
  returned to the agent so it can be surfaced to the user or logged.
- **Webhook back-channel**: Razorpay payment status (success/failure/
  refund/dispute) should flow back into the Audit/Event Log so the
  firewall's labels can be corrected post-hoc (e.g. a user disputes a
  transaction that was APPROVED — that's a false negative for retraining).

---

## 6. Tech stack (backend: Java)

| Layer | Choice | Notes |
|---|---|---|
| Language / runtime | **Java 21 (LTS)** | records + pattern matching keep the model/DTO classes concise |
| Framework | **Spring Boot 3.x** | `spring-boot-starter-web` for the REST API, `spring-boot-starter-validation` for request validation |
| Build | **Maven** (as used in `apf-java/`) | Gradle is an equally fine substitute if that's the org standard |
| Persistence | **PostgreSQL** via Spring Data JPA | `AuthorizationGrant` and `FirewallDecision` tables; start with an in-memory `H2`/`ConcurrentHashMap` store for Phase 0-1, swap the repository implementation later |
| Velocity/window counters | **Redis** (Lettuce/Spring Data Redis) | sliding-window transaction counts for the Anomaly module |
| Embeddings | Call an embedding API (or a small local ONNX model via **DJL** — Deep Java Library) | used by the Semantic Intent Matcher and the injection intent-diff check |
| Messaging (optional, for scale) | **Kafka** or Spring's `ApplicationEventPublisher` for smaller deployments | decouples decision-logging/audit from the request path |
| Testing | **JUnit 5 + Mockito**, plus the Java benchmark module wired in as an integration test | benchmark regression gate described in §8 |

## 7. Build plan (phased, sized for an agentic coding tool)

Each phase below is scoped to be handed to a coding agent (e.g. Antigravity)
as a self-contained task with a clear "done" definition. Backend = Java/Spring Boot.

### Phase 0 — Scaffolding (½–1 day)
- Maven project (`spring-boot-starter-parent`), package `com.razorpay.apf`
- Repo structure (see §8)
- `AuthorizationGrant`, `ProposedTransaction`, `FirewallDecision` as Java
  records/entities (JPA `@Entity` once persistence is wired in)
- In-memory `ConcurrentHashMap`-backed repository for grants (swap for a
  Spring Data JPA + Postgres repository later)

### Phase 1 — Policy Engine + API skeleton (1 day)
- REST endpoint: `POST /v1/transactions/evaluate` (Spring `@RestController`)
- Deterministic checks only (limit, category allowlist, validity window)
- Returns APPROVE/BLOCK with reason; no ML yet
- `@SpringBootTest` + JUnit 5 unit tests against a small hand-written fixture set

### Phase 2 — Synthetic benchmark + baseline scorer (this deliverable)
- `apf-java/` Maven module: `DatasetGenerator`, `RiskScorer`, `Evaluator`,
  `Rupees` (Indian-format currency), `BenchmarkRunner` (done — attached)
- Evaluation harness computing precision/recall/FPR/₹ cost (done)
- Wire `RiskScorer`'s logic into the actual `POST /v1/transactions/evaluate`
  service bean from Phase 1 (same class, invoked from both the benchmark
  runner and the live controller, so they can never drift apart)

### Phase 3 — Semantic Intent Matcher (2-3 days)
- Add embedding model (e.g. a small sentence-embedding model, or an
  API-based embedding call)
- Precompute and cache `purpose_embedding` at grant-creation time
- Replace the lexical category_mismatch heuristic with cosine similarity
  on item text; re-run the Phase 2 benchmark, confirm recall improves on
  the "laundered through generalist merchant" failure class

### Phase 4 — Injection & Context Integrity Checker (2-3 days)
- Lexical scanner (fast path)
- Intent-diff model: embed original instruction vs. final proposed action,
  flag large divergence
- Re-run benchmark, confirm recall improves on the "stealthy injection"
  failure class

### Phase 5 — Anomaly/Sequence Model + Risk Aggregator (2 days)
- Velocity counters (Redis or equivalent, sliding window)
- Combine all signals into the weighted (then learned) ensemble
- Expose per-signal breakdown in the API response for explainability

### Phase 6 — Decision Engine + Human Confirmation UX (2-3 days)
- CHALLENGE flow: push notification / in-app card showing
  `purpose_text` vs. proposed `item_description`/`amount`/`merchant`
  side by side, with a timeout → auto-BLOCK
- Structured BLOCK reason surfaced back to the agent/user

### Phase 7 — Razorpay integration (2-3 days)
- Pre-flight hook wired into the agent's checkout flow
- Mandate/grant reconciliation with Razorpay's consent object
- Webhook consumption for post-hoc label correction

### Phase 8 — Audit log, dashboards, retraining loop (ongoing)
- Immutable decision log (append-only store)
- Weekly/monthly recompute of precision/recall/₹ cost on production data
  using the same metrics defined in §4
- Feed corrected labels (disputes, user overrides on CHALLENGE) back into
  a labeled dataset for periodically retraining the ensemble weights

---

## 8. Suggested repo structure (Java / Maven)

```
agentic-payment-firewall/
├── apf-java/                                   # benchmark module (attached, done)
│   ├── pom.xml
│   └── src/main/java/com/razorpay/apf/benchmark/
│       ├── Transaction.java
│       ├── Rupees.java                         # Indian lakh/crore currency formatting
│       ├── DatasetGenerator.java
│       ├── RiskScorer.java
│       ├── Evaluator.java
│       └── BenchmarkRunner.java
├── apf-service/                                # Spring Boot backend (Phases 0-7)
│   ├── pom.xml
│   └── src/main/java/com/razorpay/apf/
│       ├── model/           # AuthorizationGrant, ProposedTransaction, FirewallDecision
│       ├── policy/          # deterministic checks
│       ├── intent/          # embedding similarity (Semantic Intent Matcher)
│       ├── injection/       # lexical + intent-diff checker
│       ├── anomaly/         # velocity, near-limit, sequence
│       ├── aggregator/      # weighted ensemble → risk score (shares RiskScorer
│       │                    # logic with apf-java so benchmark and prod never drift)
│       ├── decision/        # thresholds → APPROVE/CHALLENGE/BLOCK
│       ├── razorpay/        # pre-flight hook, mandate sync, webhook consumer
│       ├── api/             # @RestController: POST /v1/transactions/evaluate
│       └── ApfServiceApplication.java
│   └── src/test/java/...    # JUnit 5 fixtures, incl. the exact
│                             # "office supplies vs gift card" example
└── docs/
    └── decision_reasons.md  # canonical library of human-readable
                              # BLOCK/CHALLENGE reason strings
```

---

## 9. What "done" looks like for the MVP

- The exact ₹4,900-gift-card-under-₹5,000-office-supplies-limit scenario
  from the brief is caught and correctly returns `BLOCK` with the reason
  text shown in the brief.
- The Phase 2 benchmark runs in CI on every PR; a PR that regresses
  recall or precision below the last-committed baseline fails the build.
- Every CHALLENGE shown to a user displays the original purpose text and
  the proposed transaction side by side, in plain language, not a risk
  score.
- Every decision (APPROVE/CHALLENGE/BLOCK) is logged with its full signal
  breakdown, queryable by `grant_id` for dispute resolution.
