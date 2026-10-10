# Workflow Definition Specification

This is the authoritative reference for the LoomVault workflow definition format. It describes
every field the engine reads, the exact semantics it enforces, and the behaviour a definition
gets at runtime. When this document and the code disagree, the code wins — every field and rule
below is traceable to `loomvault-core`.

---

## 1. Format and loading

A definition is a single JSON object. **YAML is the recommended authoring format** (multi-line
prompts stay readable; JSON does not have a nice way to express them), but the two are
interchangeable — the loader deserializes both into the same immutable `WorkflowDefinition`.

Definitions are loaded from two places:

| Source | How | When |
|---|---|---|
| Classpath scan | `loomvault.workflows.location: classpath:workflows/*` | At startup (`load-samples: true`). Every `.yaml` / `.yml` / `.json` file in the location is registered. |
| HTTP upload | `POST /api/workflows` with the definition as the request body | Any time. Replaces any previous definition with the same `id`. |

A definition is **pure data**. The engine never mutates it, and it is archived next to the
journal, so replaying an old execution uses the exact graph that originally ran rather than the
latest version of the file.

Unknown properties are ignored (`fail-on-unknown-properties: false`), so a definition can carry
forward-compatible extras without breaking older engines. `null` fields are omitted on the way
out (`default-property-inclusion: non_null`).

---

## 2. Top-level fields

```yaml
id: support-triage               # required
name: Support Ticket Triage      # optional, defaults to id
version: "1"                     # optional, defaults to "1"
description: >                   # optional
  Reads a ticket, classifies it, drafts a reply, pauses for approval.
budget: { ... }                  # optional, defaults to unlimited
nodes: [ ... ]                   # optional, defaults to []
edges: [ ... ]                   # optional, defaults to []
metadata: { owner: support }     # optional, defaults to {}
```

| Field | Type | Required | Default | Semantics |
|---|---|---|---|---|
| `id` | string | **yes** | — | Workflow identifier. Must be non-blank (`IllegalArgumentException` otherwise). Used as the catalogue key and by `POST /api/executions` via `workflowId`. |
| `name` | string | no | the `id` | Human-readable name. Blank or missing falls back to `id`. |
| `version` | string | no | `"1"` | Free-form version label. Recorded in the journal's `EXECUTION_STARTED` entry so a run is tied to the definition version that produced it. |
| `description` | string | no | `null` | Free-form text. Surfaced in the catalogue and the UI. |
| `budget` | object | no | unlimited | Workflow-scoped budget policy (section 3). `null` becomes `BudgetPolicy.UNLIMITED`. |
| `nodes` | array | no | `[]` | The steps (section 4). A definition with zero nodes fails graph validation. |
| `edges` | array | no | `[]` | The directed connections between nodes (section 7). |
| `metadata` | object | no | `{}` | Arbitrary key/value annotations. Never interpreted by the engine. |
| `createdAt` | string (ISO-8601 instant) | no | now | Server-managed creation timestamp. Supply only if you are round-tripping a definition you exported. |

---

## 3. `budget`

A declarative spending policy. The same record is used at workflow level and at node level; the
two are enforced **independently** (a node budget does not subtract from, or override, the
workflow budget — each guards its own scope).

```yaml
budget:
  maxTotalTokens: 24000
  maxLlmCalls: 12
  maxCostUsd: "0.75"
  maxDurationMs: 600000
  onExceed: DEGRADE        # FAIL | DEGRADE | TRUNCATE | WARN
  warnThreshold: 0.75
```

| Field | Type | Default | Semantics |
|---|---|---|---|
| `maxTotalTokens` | integer | `0` | Hard cap on **input + output** tokens across the guarded scope. |
| `maxLlmCalls` | integer | `0` | Hard cap on the number of model calls. |
| `maxCostUsd` | decimal / string | `null` | Hard cap on estimated spend in USD. Quote it in YAML (`"0.75"`) to avoid float rounding. |
| `maxDurationMs` | integer | `0` | Wall-clock cap in **milliseconds**. |
| `onExceed` | enum | `WARN` | Strategy applied the moment a line is crossed. Unknown/`null` values become `WARN`. |
| `warnThreshold` | number | `0.8` | Fraction in the open interval `(0, 1)` at which a `BUDGET_WARNING` event is emitted. Values `<= 0` or `>= 1` are reset to `0.8`. |

**Unlimited convention.** A limit of `0` (for `maxTotalTokens`, `maxLlmCalls`, `maxDurationMs`) or
`null` / `0` (for `maxCostUsd`) means **not constrained**. `isUnlimited()` returns true only when
every one of the four limits is unset. Negative limits are rejected
(`IllegalArgumentException`). The default `BudgetPolicy.UNLIMITED` is
`{0, 0, null, 0, WARN, 0.8}`.

### 3.1 Strategies

| Strategy | Exact behaviour |
|---|---|
| `FAIL` | Abort the execution immediately and mark it `BUDGET_EXCEEDED`. The journal records a `BUDGET_EXCEEDED` entry carrying the reason. |
| `DEGRADE` | Do **not** stop. A degraded LLM node is served with its `fallbackModel` instead of `model`. If a node has no `fallbackModel`, the handler keeps the requested model and the budget guard decides the outcome — i.e. the strategy degenerates to `FAIL` for that node. |
| `TRUNCATE` | Do **not** stop. Shrink the prompt context (drop the oldest messages down to a floor) and retry the call. Intended for workloads where the input dominates the cost. |
| `WARN` | Do **not** stop. Record a `BUDGET_WARNING` event and continue with unchanged behaviour. Useful for observability-first rollouts before enforcement is switched on. |

Because the ledger is rebuilt from the journal on resume — including the original wall-clock start
time — a crash loop cannot escape a budget by dying often enough.

---

## 4. `nodes`

Each node is one step. The `config` map is intentionally untyped at the model level; each
`NodeHandler` reads the keys it understands, which keeps the format open to new node capabilities
without a schema change.

```yaml
nodes:
  - id: classify
    name: Classify ticket
    kind: LLM
    timeout: PT45S
    idempotent: true
    retry:
      maxAttempts: 3
      initialBackoff: PT0.3S
      multiplier: 2.0
      jitterRatio: 0.15
    config:
      model: mock-fast
      outputFormat: json
      prompt: Classify: ${nodes.fetch_ticket.subject}
```

### 4.1 Common fields

| Field | Type | Required | Default | Semantics |
|---|---|---|---|---|
| `id` | string | **yes** | — | Unique within the workflow. Must be non-blank. |
| `name` | string | no | the `id` | Display name. |
| `kind` | enum | **yes** | — | One of `LLM`, `TOOL`, `APPROVAL` (case-insensitive on input). Anything else is rejected with a message listing the supported values. |
| `config` | object | no | `{}` | Handler-specific configuration (sections 4.2 – 4.4). |
| `retry` | object | no | engine default (section 6) | Retry policy. `null` becomes `RetryPolicy.NO_RETRY`, which the engine then replaces with the engine-wide default policy — see the note in section 6. |
| `timeout` | string (ISO-8601 duration) | no | `PT60S` | Wall-clock limit for **a single attempt** (section 8). Must be strictly positive. |
| `budget` | object | no | unlimited | Node-scoped budget, enforced independently of the workflow budget. |
| `optional` | boolean | no | **`false`** | When `true`, this node's failure is recorded but does not abort the execution (section 4.5). |
| `idempotent` | boolean | no | **`false`** | Declares whether re-executing this node after a crash is safe (section 4.6). |

### 4.2 `kind: LLM`

Calls the configured model gateway with a rendered prompt.

| `config` key | Type | Required | Default | Semantics |
|---|---|---|---|---|
| `model` | string | no | `mock-fast` | Model to request. Also the cache/deprecation key. |
| `fallbackModel` | string | no | — | Model to use **only** when the workflow budget has degraded the run. Without it, degradation has no effect on this node. |
| `system` | string | no | — | System message. Templated (`${...}`). Omitted from the request when blank. |
| `prompt` | string | **yes** | — | User message. Templated. A missing/blank value is a hard error at execution time. |
| `temperature` | number | no | `0.2` | Sampling temperature. |
| `maxTokens` | integer | no | `0` | Requested output cap. `0` means "let the provider decide". |
| `outputFormat` | string | no | `text` | `text` or `json`. `json` requests JSON and triggers structured output handling (section 4.2.1). |

```yaml
- id: classify
  kind: LLM
  config:
    model: mock-fast
    fallbackModel: mock-fast
    temperature: 0
    outputFormat: json
    system: You are a precise support triage classifier. Answer with JSON only.
    prompt: |
      Classify the support ticket below.

      Reply with a JSON object with fields:
        category   one of billing | technical | account | shipping | other
        confidence integer 0-100
        urgent     boolean

      Subject: ${nodes.fetch_ticket.subject}
```

#### 4.2.1 LLM output structure

An LLM node always produces at least these three keys:

```json
{
  "text":   "the raw completion",
  "model":  "mock-fast",
  "cached": false
}
```

`model` is the model that **actually served** the call — after a `DEGRADE` it may differ from the
requested `model`. `cached` is `true` when the response came from the content-addressed cache.

When `outputFormat: json` is set, the handler additionally tries to parse the completion into a
JSON object and adds:

| Key | Present when | Meaning |
|---|---|---|
| `parsed` | always (for `outputFormat: json`) | `true` if a JSON object was extracted, `false` otherwise. A parse failure is **not** an error. |
| `json` | only when `parsed == true` | The parsed object. |
| *(flattened)* | only when `parsed == true` | Every **top-level** field of `json`, copied onto the node output with `putIfAbsent` (existing keys such as `text`/`model`/`cached` are not overwritten). |

Parsing is best-effort: the handler accepts a bare JSON object, a ```` ```json ... ``` ```` fenced
block, or the first balanced `{...}` region. When parsing fails, `text` still holds the raw
completion and `parsed` is `false`; downstream conditions simply observe absent fields (and, per
the null rules in section 9, evaluate to `null`/`false` rather than failing).

**Why the flattening exists.** It lets a condition or template read `nodes.classify.category`
directly instead of `nodes.classify.json.category`. Flattening a field named `text`, `model`,
`cached`, `json`, or `parsed` is a no-op because those keys are already set.

### 4.3 `kind: TOOL`

Invokes a deterministic function registered in the `ToolRegistry`.

| `config` key | Type | Required | Default | Semantics |
|---|---|---|---|---|
| `tool` | string | **yes** | — | Registered tool name. An unknown name fails the node (`NODE_NO_HANDLER`). |
| `args` | object | no | `{}` | Tool arguments. Rendered **recursively**: strings are template-expanded, and maps/lists are walked depth-first. |

```yaml
- id: notify_customer
  kind: TOOL
  idempotent: false
  config:
    tool: notify
    args:
      ticketId: ${inputs.ticketId}
      channel: email
      message: ${nodes.draft_reply.text}
```

Output structure: the tool result is nested under `result` **and** flattened onto the output, so
both `nodes.fetch_ticket.title` and `nodes.fetch_ticket.result.title` resolve to the same value.

```json
{
  "tool": "notify",
  "idempotent": false,
  "result": { "delivered": true },
  "delivered": true
}
```

> Note: the `idempotent` key in a TOOL node's **output** reflects the *tool's* declared
> idempotency (a property of the registered tool), which is independent of the node's own
> `idempotent` flag.

### 4.4 `kind: APPROVAL`

Parks the whole execution until a human decides. The handler does not block a thread — it returns
an "awaiting approval" result and the engine persists the parked state, so the process may die and
resume hours later.

| `config` key | Type | Required | Default | Semantics |
|---|---|---|---|---|
| `summary` | string | no | `Approval required for node '<id>'` | What the approver sees. Templated. |
| `details` | string | no | — | Optional longer context. Templated. Omitted from the request when blank. |
| `assignee` | string | no | — | Optional owner hint surfaced in the UI. **Not** templated — used literally. |

```yaml
- id: approve_reply
  kind: APPROVAL
  timeout: P7D
  config:
    summary: >
      Reply drafted for ${nodes.fetch_ticket.subject}
      — category ${nodes.classify.category},
      risk ${nodes.score_risk.overall}.
      Draft: ${nodes.draft_reply.text}
    details: Customer ${nodes.load_customer.name} is a ${nodes.load_customer.tier} account.
    assignee: support-lead
```

The approval request object the engine records is
`{ "summary": ..., "nodeId": ..., ["details": ...], ["assignee": ...] }`. On approval
(`APPROVAL_GRANTED`) the node completes with the approver's note as its output; on rejection
(`APPROVAL_REJECTED`) the execution terminates as failed.

### 4.5 `optional`

**Default `false`.** The flag controls what happens when the node **fails** (after retries are
exhausted, or on a non-retryable error).

| Value | Behaviour on failure |
|---|---|
| `false` (default) | The failure aborts the whole execution. The engine sets an abort reason (`node '<id>' failed: ...`) and stops the run. |
| `true` | The failure is recorded and logged, but the execution **continues**. The node's outgoing edges are released as **not taken**, so successors are skipped as though the branch never ran. |

The skip rule matters: when an optional node fails, its outgoing edges count as *settled* but not
*satisfied*. A successor with **only** that edge as input is therefore skipped; a successor that
also has another edge which **is** satisfied (e.g. a fan-in from a branch that succeeded) still
runs. See section 7.3.

### 4.6 `idempotent`

**Default `false`.** The flag declares whether the node can be re-executed safely after a crash.

On a cold resume, the engine finds nodes that have a `NODE_STARTED` entry with no terminal entry —
the crash window. It **cannot know** whether the side effect happened, so it always re-executes
them. The `idempotent` flag does not change that decision; it controls whether the risk is made
visible:

| Value | Behaviour on interrupted-resume |
|---|---|
| `false` (default) | The node is re-executed **and** a `RECOVERY_WARNING` journal entry is written (`reason: "non-idempotent node was interrupted and will be re-executed"`), plus a WARN log line. This is the flag's entire purpose: to flag exactly-once risks loudly. |
| `true` | The node is re-executed silently — you have declared that a repeat is safe (a read, a pure model call, a call guarded by an idempotency key). |

Exactly-once side effects are the application's problem. The engine gives you the journal and the
warning to build it with, and does not pretend the problem is solved for you.

---

## 5. Node kinds at a glance

| `kind` | Handler | Reads | Output |
|---|---|---|---|
| `LLM` | `LlmNodeHandler` | `config` above | `text`, `model`, `cached` (+ `parsed`, `json`, flattened fields for JSON) |
| `TOOL` | `ToolNodeHandler` | `config.tool`, `config.args` | `tool`, `idempotent`, `result` (+ flattened result fields) |
| `APPROVAL` | `ApprovalNodeHandler` | `config.summary`, `config.details`, `config.assignee` | the approver's note, once granted |

`TOOL` is also the extension point: anything needing custom behaviour should be a registered tool
rather than a new kind. The set of kinds is deliberately small and stable.

---

## 6. `retry`

A declarative retry policy with exponential backoff and optional jitter.

```yaml
retry:
  maxAttempts: 3
  initialBackoff: PT0.3S
  multiplier: 2.0
  jitterRatio: 0.15
```

| Field | Type | Default | Semantics |
|---|---|---|---|
| `maxAttempts` | integer | `1` | Total attempts **including the first**. Within a declared policy, `1` means "no retry". Must be `>= 1`. |
| `initialBackoff` | string (ISO-8601 duration) | `PT0S` | Delay before the **second** attempt. |
| `multiplier` | number | `1.0` | Growth factor per additional attempt. Values `< 1.0` are clamped to `1.0`. |
| `jitterRatio` | number | `0.0` | Randomisation in `[0, 1]` (clamped). `0` disables jitter. |

> **Engine default substitution — read this before relying on "no retry".** A declared policy is
> honoured only when it actually retries (`maxAttempts > 1`). When a node's policy does not
> (`maxAttempts <= 1`) — which includes a node that omits `retry` entirely — the engine does **not**
> run it exactly once. It substitutes the engine-wide default policy instead:
> `maxAttempts = loomvault.engine.default-max-attempts` (**default `2`**), with a fixed `200 ms`
> delay, multiplier `1.0`, and no jitter. So the practical out-of-the-box behaviour is *two*
> attempts per node, not one. To truly run a node without retries, set
> `loomvault.engine.default-max-attempts=1` (the engine's `EngineOptions.defaultMaxAttempts` must be
> `>= 1`). Then an explicit `maxAttempts > 1` re-enables retries for individual nodes.

### 6.1 Backoff computation

The delay applied after the *n*-th attempt failed (`backoffFor(n)`) grows from the **previous**
attempt number, so `backoffFor(1)` is the delay before attempt #2:

```
grown    = initialBackoff * multiplier^(n - 1)
jittered = grown * (1 - jitterRatio + 2 * jitterRatio * rand(0, 1))   # only if jitterRatio > 0
delay    = clamp(jittered, 0, 1 hour)                                  # overflow guard
```

`backoffFor` returns `0` when `n < 1` or `initialBackoff` is zero. `maxRetries()` is
`maxAttempts - 1`. Jitter prevents synchronised retry storms against a provider that is trying to
recover.

### 6.2 Journal sequence

Each attempt appends its own `NODE_FAILED` (payload: `attempt`, `error`, `errorType`,
`retryable`). A retry is scheduled **only** when the failure is considered retryable and attempts
remain; when the computed backoff is non-zero, a `NODE_RETRY_SCHEDULED` entry is written carrying
the delay in milliseconds (`{ "attempt": n, "delayMs": m }`), and the node enters the `RETRYING`
state. The next attempt opens a fresh `NODE_STARTED`. A run that fails twice and succeeds on the
third attempt (with a non-zero backoff) looks like:

```
NODE_SCHEDULED       <node>        # { "node": "<name>" }
NODE_STARTED         <node>        # { "attempt": 1 }
NODE_FAILED          <node>        # { "attempt": 1, "error": ..., "retryable": true }
NODE_RETRY_SCHEDULED <node>        # { "attempt": 1, "delayMs": 300 }
NODE_STARTED         <node>        # { "attempt": 2 }
NODE_FAILED          <node>        # { "attempt": 2, "error": ..., "retryable": true }
NODE_RETRY_SCHEDULED <node>        # { "attempt": 2, "delayMs": 600 }
NODE_STARTED         <node>        # { "attempt": 3 }
NODE_COMPLETED       <node>
```

When the backoff is zero (for example `initialBackoff: PT0S`), the `NODE_RETRY_SCHEDULED` entry is
omitted and the loop goes straight from `NODE_FAILED` back to `NODE_STARTED`. A non-retryable
failure never produces a further attempt, regardless of `maxAttempts`.

`NODE_STARTED` without a following terminal entry is the engine's crash marker: it means the
process died inside that node's side-effect window (section 4.6).

`loomvault.llm.mock.fail-first-calls=2` makes the mock provider fail its first two calls, which is
the easiest way to watch this sequence light up in the journal.

---

## 7. `edges`

A directed connection between two nodes, optionally guarded by a condition.

```yaml
edges:
  - from: prepare
    to: review_clarity
  - from: aggregate
    to: publish_ready
    condition: nodes.aggregate.pass == true
  - from: aggregate
    to: needs_revision
    condition: nodes.aggregate.pass == false
```

| Field | Type | Required | Default | Semantics |
|---|---|---|---|---|
| `from` | string | **yes** | — | Source node id. Must be non-blank and must exist. |
| `to` | string | **yes** | — | Target node id. Must be non-blank and must exist. |
| `condition` | string | no | `null` | Boolean expression; `null`/blank means "always taken". |

Self-loops (`from == to`) are rejected. Edges referencing an unknown node id are rejected at graph
construction (`GRAPH_UNKNOWN_EDGE_ENDPOINT`).

### 7.1 Unconditional edges = fan-out

An edge without a condition is always taken once its source completes. A node with three
unconditional outgoing edges therefore starts **three branches in parallel** — this is the whole
fan-out mechanism, with no dedicated "split" node.

### 7.2 Conditional edges = routing

A conditional edge is taken only when its condition evaluates truthy against the completed
execution context. Mutually exclusive conditions (`pass == true` / `pass == false`) guarantee
exactly one branch runs. Conditions are evaluated per **edge**, not per source node — two
conditional edges out of the same node are independent, and both, either, or neither may fire.

### 7.3 Skip propagation

The engine counts two numbers per **edge** (not per node):

- `settled[target]` — incremented for every incoming edge whose source has settled (whether the
  edge was taken or not).
- `satisfied[target]` — incremented for every incoming edge that was actually taken (source
  succeeded **and** the condition held).

A target becomes **ready** when *every* incoming edge has settled **and** *at least one* was
satisfied. When every incoming edge has settled and **none** was satisfied, the branch was not
taken: the node is **skipped**, recording a `NODE_SKIPPED` entry, and the skip propagates
recursively down its own outgoing edges.

This single rule yields all routing behaviours:

| Situation | Result |
|---|---|
| One predecessor, unconditional edge | Ordinary sequential step |
| Two unconditional outgoing edges | Fan-out — both targets run in parallel |
| Multiple incoming edges | Fan-in — the node waits for all, then reads merged context |
| Exclusive conditions | Exactly one branch runs; the other is skipped and the skip propagates |
| Optional node failed | Its successors are treated as "branch not taken" and skipped, unless another satisfied in-edge reaches them |

Without skip propagation, untaken branches would leave nodes `PENDING` forever and the run would
look like it hung instead of finishing. Any node still non-terminal when the schedule drains is
marked skipped with reason `not reached`.

---

## 8. `timeout`

`timeout` is an **ISO-8601 duration** applied to **a single attempt**, not to the whole retry
loop.

| Value | Meaning |
|---|---|
| `PT30S` | 30 seconds |
| `PT5M` | 5 minutes |
| `PT0.3S` | 300 milliseconds |
| `P7D` | 7 days |
| `PT60S` | the default when `timeout` is omitted |

The value must be strictly positive (zero or negative is rejected). A watchdog wraps the entire
retry loop sized as `timeout × attempts + backoff + slack` (the `loomvault.engine.hard-timeout-slack`
configuration), so a handler that ignores interruption still cannot pin a scheduler slot forever.
The trade-off is explicit: after the watchdog fires, the abandoned worker may still be running —
which is why handlers should pass their own deadlines to HTTP clients and treat the watchdog as a
backstop, not the primary mechanism. An approval node typically uses a long timeout such as `P7D`,
since a human may take days to respond.

---

## 9. Expression language

Edge conditions and `${...}` templates share one small, hand-written language — no SpEL, no
JavaScript, no `eval`. It is parsed once (and memoised) and evaluated in microseconds.

### 9.1 Syntax

```
literals     42, 3.14, "text", 'text', true, false, null
paths        inputs.ticket.id, nodes.score.overall, items[0].name, map["key"]
arithmetic   + - * / %
comparison   == != > >= < <=
logic        && || !
functions    len(x), contains(a, b), startsWith(s, p), lower(s), coalesce(a, b), ...
grouping     ( ... )
```

Operator precedence, loosest to tightest:

```
||  >  &&  >  == !=  >  >= <= > <  >  + -  >  * / %  >  unary ! -  >  postfix . []  >  primary
```

Paths resolve against a fixed set of root variables:

| Root | Available in | Value |
|---|---|---|
| `inputs` | conditions **and** templates | The execution's input map |
| `nodes` | conditions **and** templates | Map of node id → that node's output |
| `executionId` | templates (not edge conditions) | The current execution id |

Index access (`a[i]`) works on lists (numeric index) and maps (string key); a numeric index into a
list that is out of range yields `null`. `&&` and `||` short-circuit — the right-hand side is not
evaluated when the left already decides the outcome.

### 9.2 Three-valued logic

Evaluation is **total**: it never throws on missing or surprising data.

| Situation | Result |
|---|---|
| Any arithmetic op (`+ - * / %`) with a `null` operand | `null` |
| Division or modulo by zero | `null` |
| Missing field, or out-of-range list index | `null` |
| `==` / `!=` | **Never null** — null-aware equality (`null == null` → `true`, `null == 5` → `false`) |
| `>`, `>=`, `<`, `<=` with a `null` or incomparable operand | `false` (not `null`) |
| `null` in a boolean position | `false` |
| `&&` / `||` | Short-circuit, coercing each side to boolean |

Truthiness (`asBoolean`) follows these rules: `null` → false; booleans as-is; numbers → true when
non-zero; strings → false when empty, or equal (case-insensitively, trimmed) to `"false"`, `"0"`,
or `"no"`, else true; collections/maps → true when non-empty; any other object → true.

`==` compares numbers numerically (`2 == 2.0` is true), strings, booleans, and recursively
compares lists and maps. The engine never explodes at 3am: a condition that silently evaluates to
false is debuggable (the node shows as skipped, the journal explains why), whereas a condition
that throws would leave an execution half-scheduled.

Only **unknown functions** are rejected — and they are rejected at **parse time**, when the
workflow is loaded, not mid-run. So a typo fails at startup, not at 3am.

### 9.3 Built-in functions

The complete, closed set. There are 18 functions; any other name fails at parse time.

| Function | Signature | Behaviour | Example |
|---|---|---|---|
| `len` | `len(x) → number` | Length of a string, collection, map or array. `null` → `0`. Other values measured as their text length. | `len(nodes.doc.text) > 0` |
| `isEmpty` | `isEmpty(x) → boolean` | Empty string/collection/map → true; `null` → true; anything else → false. | `isEmpty(inputs.tags)` |
| `isNull` | `isNull(x) → boolean` | `x == null`. | `isNull(nodes.draft.error)` |
| `isNotNull` | `isNotNull(x) → boolean` | `x != null`. | `isNotNull(nodes.classify.category)` |
| `contains` | `contains(haystack, needle) → boolean` | String contains (needle stringified); collection contains an equal element; map contains the key. `null` in either argument → false. | `contains(nodes.classify.category, "billing")` |
| `startsWith` | `startsWith(s, p) → boolean` | Text prefix test. `null` in either argument → false. | `startsWith(inputs.id, "TCK-")` |
| `endsWith` | `endsWith(s, p) → boolean` | Text suffix test. `null` in either argument → false. | `endsWith(inputs.file, ".md")` |
| `lower` | `lower(s) → string` | Lower-cased text. `null` → `null`. | `lower(inputs.priority) == "p1"` |
| `upper` | `upper(s) → string` | Upper-cased text. `null` → `null`. | `upper(inputs.region) == "EU"` |
| `trim` | `trim(s) → string` | Trimmed text. `null` → `null`. | `len(trim(inputs.note)) > 0` |
| `abs` | `abs(x) → number` | Absolute value. Non-numeric / `null` → `null`. | `abs(nodes.delta.value) < 5` |
| `min` | `min(a, b, ...) → number` | Smallest of its numeric arguments; non-numeric arguments are ignored; all non-numeric → `null`. | `min(nodes.a.score, nodes.b.score) >= 60` |
| `max` | `max(a, b, ...) → number` | Largest numeric argument, same ignore rules. | `max(nodes.a.score, nodes.b.score) > 80` |
| `round` | `round(x [, digits]) → number` | Round `x` to `digits` decimals (default `0`), half-up. Non-numeric / `null` → `null`. | `round(nodes.metrics.ratio, 2)` |
| `coalesce` | `coalesce(a, b, ...) → any` | First argument that is not `null`; `null` when all are. | `coalesce(nodes.a.tag, inputs.defaultTag, "none")` |
| `toNumber` | `toNumber(x) → number \| null` | Best-effort numeric conversion; unparsable / `null` → `null`. | `toNumber(nodes.metrics.count) > 10` |
| `toText` | `toText(x) → string \| null` | Renders any value as text; `null` → `null`. | `toText(nodes.score.overall) + " / 100"` |
| `json` | `json(x) → string` | Serialises the value to compact JSON. `null` → the literal string `"null"`. | `json(nodes.aggregate)` |

Function calls evaluate all their arguments (no lazy evaluation except the operators `&&` and
`||`). Missing arguments are `null`: `isNull()` with no argument is `true`, `min()` with no
numeric argument is `null`.

---

## 10. Templates

Any string passed through templating is expanded with `${ ... }` placeholders, where the inside is
a full expression from section 9.

```yaml
config:
  prompt: |
    Summarise the ticket: ${inputs.ticket.title} (priority ${inputs.ticket.priority}).
    Reviewer score: ${nodes.review.score}.
```

| Rule | Behaviour |
|---|---|
| Interpolation | `${expr}` is replaced by the rendered value of `expr`. Non-string values are serialised (numbers/booleans as text, objects/maps as JSON). |
| Escaping | A literal `${` is written `\${`; the backslash is dropped and the `${...}` emitted verbatim, without evaluation. |
| Unknown path | An expression that evaluates to `null` renders as the **empty string**. |
| Malformed placeholder | An unterminated `${` or an expression that fails to parse/validate raises `TEMPLATE_RENDER_ERROR` (config bug, fails fast). |
| Nesting | Not supported — one level of `${...}` only. Braces inside string literals within the placeholder are handled correctly. |

Templates are rendered against the same roots as conditions (`inputs`, `nodes`), plus
`executionId` (section 9.1). Leading/trailing whitespace inside `${ ... }` is trimmed.

---

## 11. Validation and linting

`POST /api/workflows/validate` runs the full structural check **without registering** the
definition. It returns a structured report.

**On success:**

```json
{
  "valid": true,
  "workflowId": "support-triage",
  "nodes": ["fetch_ticket", "load_customer", "classify", "..."],
  "roots": ["fetch_ticket"],
  "topologicalOrder": ["fetch_ticket", "load_customer", "classify", "score_risk", "..."],
  "warnings": []
}
```

| Key | Meaning |
|---|---|
| `valid` | `true` if the graph compiles. |
| `workflowId` | The definition's `id`. |
| `nodes` | All node ids, in declaration order. |
| `roots` | Nodes with no incoming edges — the scheduler's initial ready set. |
| `topologicalOrder` | A valid scheduling order (Kahn's algorithm). |
| `warnings` | Non-fatal lint findings (below). |

**On failure:**

```json
{
  "valid": false,
  "error": "workflow contains a cycle: a -> b -> c -> a",
  "code": "GRAPH_CYCLE_DETECTED",
  "offenders": ["a", "b", "c", "a"]
}
```

`code` is present only when the failure is a graph-validation error, and `offenders` carries the
offending node ids or the concrete cycle path.

### 11.1 Errors that reject a definition (HTTP 400 on `POST /api/workflows`)

Registration compiles the graph and **rejects** a definition that violates any of these, so a bad
workflow fails at deploy time rather than at 3am.

| `code` | Cause |
|---|---|
| `INVALID_WORKFLOW` | No nodes (empty graph), or the definition is otherwise structurally unusable. |
| `GRAPH_DUPLICATE_NODE` | Two nodes share an `id`. `offenders` lists the duplicates. |
| `GRAPH_UNKNOWN_EDGE_ENDPOINT` | An edge's `from` or `to` names a node that does not exist. `offenders` lists the unknown ids. |
| `GRAPH_NO_ENTRY_POINT` | Every node has at least one incoming edge, so nothing can ever start. |
| `GRAPH_CYCLE_DETECTED` | The graph contains a directed cycle. `offenders` (and `context.cycle`) carry one concrete path, e.g. `a -> b -> c -> a`. |

Self-loops (`from == to`) are rejected earlier, when the edge is constructed.

### 11.2 Lint warnings (never block registration)

| Warning | Trigger |
|---|---|
| A `TOOL` node has no `tool` config key | The node declares `kind: TOOL` but no `config.tool`. |
| An `LLM` node has no `prompt` | The node declares `kind: LLM` but `config.prompt` is missing or blank. |
| A prompt references an unknown node | A `${nodes.X...}` placeholder in a prompt names a node `X` that is not in the graph. |
| The workflow has no sink node | Every node has an outgoing edge, so there is no terminal node. |

Linting is intentionally shallow — a linter that cries wolf gets ignored.

---

## 12. Minimal complete example

```yaml
id: hello
name: Hello
version: "1"
budget:
  maxTotalTokens: 4000
  onExceed: WARN

nodes:
  - id: greet
    kind: LLM
    timeout: PT30S
    retry:
      maxAttempts: 2
      initialBackoff: PT0.5S
      multiplier: 2.0
      jitterRatio: 0.1
    config:
      model: mock-fast
      prompt: Say hello to ${inputs.name} in one sentence.

edges: []
```

A single node with `edges: []` is a valid workflow: the one node is the sole root and the sole
sink, the execution runs it once, and `SUCCEEDED` carries the node output under `final`.
