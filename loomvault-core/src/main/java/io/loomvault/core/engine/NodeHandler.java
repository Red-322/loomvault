package io.loomvault.core.engine;

import io.loomvault.core.model.NodeKind;

/**
 * Extension point for node behaviour.
 *
 * <p>One handler per {@link NodeKind}. A handler receives everything it needs through the
 * {@link NodeInvocation} and must not touch the journal, the repository or the scheduler — the
 * engine owns durability. Keeping handlers pure is what allows the same handler to run in a live
 * execution, in a replay, and in a unit test with no database at all.</p>
 *
 * <p>Handlers may throw; the engine applies the node's retry policy and classifies the failure.
 * Throwing {@link io.loomvault.core.error.LlmException} with {@code retryable=false} (or any
 * exception carrying that flag via a wrapped cause) short-circuits retries — paying three times
 * for a 400 response is the kind of behaviour this engine exists to prevent.</p>
 */
public interface NodeHandler {

    /** The kind this handler serves. Exactly one handler per kind may be registered. */
    NodeKind kind();

    /**
     * Executes one attempt of a node.
     *
     * @param invocation the node, its rendered context and the attempt number
     * @return the node result; never {@code null}
     * @throws Exception any failure; the engine decides whether to retry
     */
    NodeResult execute(NodeInvocation invocation) throws Exception;
}
