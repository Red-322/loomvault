# Architecture

This document explains *why* LoomVault is built the way it is. The README tells you what it does;
this one tells you what would break if a given decision were made differently.

---

## 1. Design invariants

Four properties are treated as non-negotiable. Every other decision follows from them.

| Invariant | Consequence |
|---|---|
| **Completed work is never re-executed.** | Node outputs are journalled, and replay reads the journal instead of the handler. |
| **The journal is the source of truth.** | Events are written *before* being published. Live state (the execution row, node states) is a projection that can always be rebuilt. |
| **A budget cannot be escaped by dying.** | The ledger is rebuilt from the journal on resume, including the original wall-clock start. |
| **The engine never blocks on a human.** | Approval is a *persisted state*, not a waiting thread. |

A useful test for any proposed change: *does it let two observers disagree about what happened, or
let a restart change the cost of a run?* If yes, the change is wrong regardless of how convenient
it is.

---

## 2. Module boundaries

```
loomvault-core      ← no Spring, no JDBC, no HTTP
   ▲      ▲      ▲
   │      │      │
  llm   store   (anything else you write)
   ▲      ▲
   └──┬───┘
      │
loomvault-server    ← the only module that knows all implementations
```

`loomvault-core` declares **ports** (`ExecutionRepository`, `JournalStore`, `LlmGateway`,
`ResponseCache`, `EventPublisher`) and contains every scheduling decision. Outer modules are
adapters. Two practical consequences:

- the whole engine can be tested in-memory, in milliseconds, with no container
- swapping PostgreSQL for something else, or the mock provider for a real one, never touches the
  scheduler

The `Clock` dependency is the JDK's `java.time.Clock`, not a bespoke interface — it already has the
right shape, and re-inventing it would be noise.

---

## 3. The scheduler

### 3.1 One owner thread

`ExecutionEngine` drives each execution from exactly one thread. That thread owns:

- the ready queue
- predecessor counters (`settled` / `satisfied`)
- the node-state map
- the in-flight map
- the current `Execution` record

Worker threads receive an **immutable `NodeContext` snapshot** and can only communicate back by
pushing a `NodeCompletion` onto a `BlockingQueue`. `nodeStates` is the one shared structure
(a `ConcurrentHashMap`) because workers write attempt-lifecycle states directly so that a crash
mid-attempt leaves a trace.

Why this matters: parallel branches that finish concurrently are the normal case, and the
alternative — shared maps with per-key locking — is where agent frameworks develop their most
entertaining production bugs. Here, the only cross-thread mutable field in the scheduler is a
`volatile boolean cancelled`.

### 3.2 Readiness, fan-out and fan-in

```
pendingPredecessors / satisfiedPredecessors   (both counted per EDGE, not per node)
```

When a node settles, every outgoing edge contributes one to `settled[target]`, and — if its
condition holds — one to `satisfied[target]`. The target becomes ready when **every** incoming
edge has settled and **at least one** was satisfied.

This single rule produces all three routing behaviours without a special "router" node type:

| Situation | Result |
|---|---|
| One predecessor, unconditional edge | Ordinary sequential step |
| Two unconditional outgoing edges | Fan-out: both targets become ready and run in parallel |
| Multiple incoming edges | Fan-in: the node waits for all of them, then reads merged context |
| Exclusive conditions (`pass == true` / `pass == false`) | Exactly one branch runs; the other is skipped and the skip propagates |

Skip propagation matters more than it sounds: without it, untaken branches would leave nodes
`PENDING` forever and the run would appear to hang instead of finishing.

### 3.3 Parallelism cap

`maxParallelNodes` bounds *in-flight* work rather than rejecting work after the fact. A rate
limiter that returns 429s converts a provider blip into a retry storm; a concurrency cap simply
never issues the requests. For a fan-out of eight nodes on a provider with a five-request quota,
this is the difference between a slow run and a broken one.

### 3.4 Timeouts

`NodeDefinition.timeout` applies to **one attempt**. The watchdog wraps the whole retry loop in
`CompletableFuture.orTimeout` sized as `timeout × attempts + backoff + slack`, so a handler that
ignores interruption still cannot pin a slot forever. The trade-off is explicit: after a watchdog
fires, the abandoned worker may still be running. That is why handlers are expected to pass their
own deadlines to HTTP clients — the watchdog is a floor, not the primary mechanism.

---

## 4. Replay and recovery

### 4.1 What is recorded

| Event | Why it exists |
|---|---|
| `NODE_STARTED` | Opens a side-effect window. Its presence without a terminal event *is* the crash marker. |
| `NODE_COMPLETED` | Carries the output that replay will feed downstream nodes, plus usage. |
| `NODE_FAILED` | One per attempt, so `/journal` shows the whole retry history. |
| `APPROVAL_REQUESTED` / `_GRANTED` / `_REJECTED` | Makes a parked run self-describing. |
| `EXECUTION_RESUMED` | Auditable proof that a restart happened, and how much was replayed. |

### 4.2 The resume algorithm

```
resume(executionId):
    entries ← journal.readAll(executionId)
    replay  ← ReplayIndex.from(entries)              # fold events into a read model
    ledger  ← BudgetLedger.resuming(replay.usage, originalStart)

    for nodeId in graph.topologicalOrder():          # single pass — predecessors settle first
        if replay.isCompleted(nodeId):
            restore output; prime counters; continue  # ← no handler call, ever
        if replay.wasRejected(nodeId):    mark FAILED
        if replay.pendingApprovals:       mark WAITING_APPROVAL
        else:                             enqueue if its predecessors allow

    drive()                                          # continue scheduling from here
```

The topological order is what makes this a single pass: by the time a node is visited, every
predecessor has already been classified, so its counters are final.

### 4.3 The interrupted-node decision

A node with a `NODE_STARTED` and no terminal event was interrupted **inside its side-effect
window**. Three options exist, and none of them is free:

1. **Assume it did not happen, re-execute.** Chosen default. Correct for model calls and reads,
   which is the overwhelming majority of nodes.
2. **Assume it happened, skip.** Silently drops legitimate work.
3. **Refuse to decide, require a human.** Correct for money movements, unusable as a default.

LoomVault takes option 1 and makes the risk *visible*: a `RECOVERY_WARNING` event is written for
nodes declared `idempotent: false`. The honest framing is that exactly-once side effects are the
application's problem — the engine gives you the journal and the warning to build it with, and
never pretends the problem is solved.

---

## 5. Budget governance

A `BudgetLedger` accumulates an immutable `Usage` via a CAS loop, so parallel branches finishing
simultaneously cannot lose a counter update. Evaluation is separated from accumulation: handlers
record, the scheduler evaluates at decision points.

```
record(usage)                     # lock-free, hot path
evaluate() → OK | WARNING | EXCEEDED   # cold path, called between completions
```

`BudgetLedger.resuming(...)` takes both the replayed usage **and the original start time**. That
second argument is the subtle one: without it, a crash loop would reset the wall-clock budget on
every restart, and "die often enough" would become a way to run forever.

Cost is derived from a `CostModel` with exact-then-longest-prefix lookup (`gpt-4o-2024-08-06` →
`gpt-4o`). **Unknown models are priced at zero, not at a guess** — a budget guard that invents a
price is worse than one that admits ignorance.

---

## 6. The expression engine

`ExpressionParser` is a hand-written lexer plus recursive-descent parser (~250 lines). The reason
isn't performance, it's **totality**.

Workflow conditions are evaluated against data produced by a language model. That data is not
guaranteed to have the shape anyone intended. So:

- missing paths, out-of-range indexes and division by zero yield `null`
- `null` in boolean position is `false`
- only *unknown functions* are rejected — and they are rejected at **parse time**, when the
  workflow is loaded

The result is a condition that cannot throw at 3am. A condition that silently evaluates to false
is debuggable (the node shows as skipped, the journal shows why); a condition that throws mid-run
leaves an execution half-scheduled.

Using SpEL or a JavaScript engine would have been fewer lines and a larger attack surface —
prompt content reaching an evaluator with reflection or `eval` semantics is a genuinely bad idea.

---

## 7. Known limitations

Stated plainly, because a README that only lists strengths is a sales document.

| Limitation | Impact | Workaround / plan |
|---|---|---|
| **No streaming** | A node's output materialises only when the model finishes. Long generations show as `RUNNING` with no partial output. | Journalled incremental events are on the roadmap. |
| **Non-idempotent replay** | Re-executed nodes may double a side effect if the process died inside their window. | Inject idempotency keys in the tool; the engine warns loudly. |
| **Single-instance scheduler** | `maxParallelNodes` is per process. Two replicas give you 2× the fan-out, not coordination. | `revision` optimistic locking prevents *double-driving* the same execution; a distributed lease is on the roadmap. |
| **Journal growth** | An append-only log on a hot execution grows without bound. | Retention/purge is roadmap; the columns are already indexed by `(execution_id, entry_seq)` for cheap pruning. |
| **Abandoned workers** | After a watchdog timeout the underlying call may still complete. | Handlers should set their own IO timeouts; the watchdog is a backstop. |
| **No authoring UI** | Workflows are YAML in your repo. | Deliberate — reviewed in pull requests, diffable, unit-testable. |

---

## 8. Reading order for new contributors

1. `ExecutionEngine.driveLoop` — the whole scheduler in about forty lines
2. `ExecutionEngine.rehydrate` — the whole recovery algorithm in about forty lines
3. `ReplayIndex.from` — how events become a read model
4. `BudgetLedger.evaluate` — the four limits and their precedence
5. `EvaluationTests` in `ExpressionEngineTest` — the semantics that keep routing total

If those five make sense, the rest of the codebase is vocabulary.
