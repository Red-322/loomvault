package io.loomvault.core.journal;

import io.loomvault.core.model.Usage;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-model built from the journal that answers exactly one question fast:
 * <em>"given this history, what does the engine already know?"</em>
 *
 * <p>The recovery planner and the replay path both consume this index. Because it is derived
 * purely from append-only entries, replaying a journal is deterministic: the same entries always
 * produce the same index, hence the same downstream node inputs, hence (for temperature-0 models
 * and cached responses) the same outputs.</p>
 */
public final class ReplayIndex {

    private final Map<String, Map<String, Object>> completedOutputs;
    private final Map<String, Usage> nodeUsage;
    private final Set<String> interruptedNodes;
    private final Set<String> failedNodes;
    private final Map<String, Map<String, Object>> approvals;
    private final Set<String> rejectedApprovals;
    private final Set<String> pendingApprovals;
    private final Map<String, Integer> attempts;
    private final Usage recordedUsage;
    private final int entryCount;

    private ReplayIndex(Map<String, Map<String, Object>> completedOutputs,
                        Map<String, Usage> nodeUsage,
                        Set<String> interruptedNodes,
                        Set<String> failedNodes,
                        Map<String, Map<String, Object>> approvals,
                        Set<String> rejectedApprovals,
                        Set<String> pendingApprovals,
                        Map<String, Integer> attempts,
                        Usage recordedUsage,
                        int entryCount) {
        this.completedOutputs = completedOutputs;
        this.nodeUsage = nodeUsage;
        this.interruptedNodes = interruptedNodes;
        this.failedNodes = failedNodes;
        this.approvals = approvals;
        this.rejectedApprovals = rejectedApprovals;
        this.pendingApprovals = pendingApprovals;
        this.attempts = attempts;
        this.recordedUsage = recordedUsage;
        this.entryCount = entryCount;
    }

    public static ReplayIndex empty() {
        return new ReplayIndex(Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Set.of(), Set.of(),
                Map.of(), Usage.ZERO, 0);
    }

    /**
     * Folds a journal into the replay view.
     *
     * <p>Order matters: entries are applied strictly by sequence, and later entries win. A node
     * that started, failed twice and finally completed ends up {@code COMPLETED} — exactly what
     * the live execution saw.</p>
     */
    public static ReplayIndex from(List<JournalEntry> entries) {
        Map<String, Map<String, Object>> completed = new LinkedHashMap<>();
        Map<String, Usage> usage = new LinkedHashMap<>();
        Set<String> interrupted = new java.util.LinkedHashSet<>();
        Set<String> failed = new java.util.LinkedHashSet<>();
        Map<String, Map<String, Object>> approvals = new LinkedHashMap<>();
        Set<String> rejected = new java.util.LinkedHashSet<>();
        Set<String> pending = new java.util.LinkedHashSet<>();
        Map<String, Integer> attempts = new LinkedHashMap<>();
        long inputTokens = 0;
        long outputTokens = 0;
        long llmCalls = 0;
        long cacheHits = 0;
        BigDecimal cost = BigDecimal.ZERO;

        for (JournalEntry entry : entries) {
            String nodeId = entry.nodeId();
            switch (entry.type()) {
                case NODE_STARTED -> {
                    if (nodeId != null) {
                        attempts.merge(nodeId, 1, Integer::sum);
                        interrupted.add(nodeId);
                    }
                }
                case NODE_COMPLETED -> {
                    if (nodeId != null) {
                        interrupted.remove(nodeId);
                        failed.remove(nodeId);
                        Map<String, Object> output = entry.payloadMap("output");
                        completed.put(nodeId, output);
                        Usage nodeUse = readUsage(entry.payloadMap("usage"));
                        usage.put(nodeId, nodeUse);
                        inputTokens += nodeUse.inputTokens();
                        outputTokens += nodeUse.outputTokens();
                        llmCalls += nodeUse.llmCalls();
                        cacheHits += nodeUse.cacheHits();
                        cost = cost.add(nodeUse.costUsd());
                    }
                }
                case NODE_FAILED -> {
                    if (nodeId != null && !completed.containsKey(nodeId)) {
                        failed.add(nodeId);
                    }
                }
                case NODE_SKIPPED -> {
                    if (nodeId != null) {
                        interrupted.remove(nodeId);
                    }
                }
                case APPROVAL_REQUESTED -> {
                    if (nodeId != null) {
                        pending.add(nodeId);
                        interrupted.remove(nodeId);
                    }
                }
                case APPROVAL_GRANTED -> {
                    if (nodeId != null) {
                        pending.remove(nodeId);
                        interrupted.remove(nodeId);
                        rejected.remove(nodeId);
                        approvals.put(nodeId, entry.payloadMap("output"));
                    }
                }
                case APPROVAL_REJECTED -> {
                    if (nodeId != null) {
                        pending.remove(nodeId);
                        interrupted.remove(nodeId);
                        rejected.add(nodeId);
                    }
                }
                default -> {
                    // Execution-level bookkeeping does not affect the replay view.
                }
            }
        }

        // An approval that was granted also counts as a completed node for scheduling purposes.
        approvals.forEach((nodeId, output) -> completed.putIfAbsent(nodeId, output));

        Usage total = new Usage(inputTokens, outputTokens, llmCalls, cacheHits, cost);
        return new ReplayIndex(completed, usage, interrupted, failed, approvals, rejected, pending,
                attempts, total, entries.size());
    }

    private static Usage readUsage(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Usage.ZERO;
        }
        return new Usage(
                asLong(map.get("inputTokens")),
                asLong(map.get("outputTokens")),
                asLong(map.get("llmCalls")),
                asLong(map.get("cacheHits")),
                map.get("costUsd") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(map.get("costUsd"))));
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    // ---- Queries used by the engine ---------------------------------------------------------

    /** True when the journal proves this node already produced its output. */
    public boolean isCompleted(String nodeId) {
        return completedOutputs.containsKey(nodeId);
    }

    /** Recorded output of a completed node, or an empty map. */
    public Map<String, Object> outputOf(String nodeId) {
        return completedOutputs.getOrDefault(nodeId, Map.of());
    }

    /** All nodes recovered from the journal, in first-completion order. */
    public Map<String, Map<String, Object>> completedOutputs() {
        return Map.copyOf(completedOutputs);
    }

    /** Nodes that had a NODE_STARTED but never settled — the crash window. */
    public Set<String> interruptedNodes() {
        return Set.copyOf(interruptedNodes);
    }

    /** Nodes whose last recorded attempt failed and which have no later success. */
    public Set<String> failedNodes() {
        return Set.copyOf(failedNodes);
    }

    public boolean wasApproved(String nodeId) {
        return approvals.containsKey(nodeId);
    }

    public boolean wasRejected(String nodeId) {
        return rejectedApprovals.contains(nodeId);
    }

    /** Nodes that asked for an approval and never got one — the execution is still parked on them. */
    public Set<String> pendingApprovals() {
        return Set.copyOf(pendingApprovals);
    }

    /** First approval node still waiting for a human, in journal order; empty when none. */
    public java.util.Optional<String> firstPendingApproval() {
        return pendingApprovals.stream().findFirst();
    }

    public Map<String, Object> approvalOutput(String nodeId) {
        return approvals.getOrDefault(nodeId, Map.of());
    }

    /** Number of attempts recorded per node — feeds the retry accountant on resume. */
    public Map<String, Integer> attempts() {
        return Map.copyOf(attempts);
    }

    public int attemptOf(String nodeId) {
        return attempts.getOrDefault(nodeId, 0);
    }

    /** Usage already billed for this execution, replayed from the journal. */
    public Usage recordedUsage() {
        return recordedUsage;
    }

    /** Usage recorded for a single node, or {@link Usage#ZERO} when unknown. */
    public Usage nodeUsageOf(String nodeId) {
        return nodeUsage.getOrDefault(nodeId, Usage.ZERO);
    }

    /** Per-node usage as recorded in the journal. */
    public Map<String, Usage> nodeUsages() {
        return Map.copyOf(nodeUsage);
    }

    public int entryCount() {
        return entryCount;
    }
}
