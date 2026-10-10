package io.loomvault.core.graph;

import io.loomvault.core.error.ErrorCode;
import io.loomvault.core.error.GraphValidationException;
import io.loomvault.core.model.NodeDefinition;
import io.loomvault.core.model.WorkflowDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural validation and queries of {@link WorkflowGraph}.
 *
 * <p>Graph construction is the one place where an illegal definition is allowed to fail loudly —
 * and it must fail with enough detail (offending ids, cycle path) for an author to fix the YAML
 * without bisecting it.</p>
 */
class WorkflowGraphTest {

    private static NodeDefinition tool(String id) {
        return NodeDefinition.tool(id, "noop", Map.of());
    }

    private static WorkflowDefinition diamond() {
        return WorkflowDefinition.builder()
                .id("diamond")
                .node(tool("a"))
                .node(tool("b"))
                .node(tool("c"))
                .node(tool("d"))
                .edge("a", "b")
                .edge("a", "c")
                .edge("b", "d")
                .edge("c", "d")
                .build();
    }

    // -----------------------------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("computes a valid topological order for a linear graph")
    void shouldReturnTopologicalOrderForLinearGraph() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("linear")
                .node(tool("a")).node(tool("b")).node(tool("c"))
                .edge("a", "b").edge("b", "c")
                .build();

        WorkflowGraph graph = WorkflowGraph.of(definition);

        assertThat(graph.topologicalOrder()).containsExactly("a", "b", "c");
        assertThat(graph.roots()).containsExactly("a");
    }

    @Test
    @DisplayName("reports roots, successors and predecessor counts of a diamond graph")
    void shouldExposeAdjacencyQueries() {
        WorkflowGraph graph = WorkflowGraph.of(diamond());

        assertThat(graph.roots()).containsExactly("a");
        assertThat(graph.successors("a")).containsExactlyInAnyOrder("b", "c");
        assertThat(graph.successors("d")).isEmpty();
        assertThat(graph.predecessors("d")).containsExactlyInAnyOrder("b", "c");
        assertThat(graph.predecessorCount("d")).isEqualTo(2);
        assertThat(graph.predecessorCount("a")).isZero();
        assertThat(graph.topologicalOrder()).hasSize(4).startsWith("a").endsWith("d");
    }

    // -----------------------------------------------------------------------------------------
    // Rejections
    // -----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("invalid definitions")
    class InvalidDefinitions {

        @Test
        @DisplayName("rejects a null definition")
        void shouldRejectNullDefinition() {
            assertThatThrownBy(() -> WorkflowGraph.of(null))
                    .isInstanceOf(GraphValidationException.class);
        }

        @Test
        @DisplayName("rejects a workflow with no nodes")
        void shouldRejectEmptyWorkflow() {
            WorkflowDefinition empty = WorkflowDefinition.builder().id("empty").build();
            assertThatThrownBy(() -> WorkflowGraph.of(empty))
                    .isInstanceOfSatisfying(GraphValidationException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_WORKFLOW));
        }

        @Test
        @DisplayName("rejects duplicate node ids and names the offenders")
        void shouldRejectDuplicateNodeIds() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .id("dup")
                    .node(tool("a")).node(tool("a")).node(tool("b"))
                    .edge("a", "b")
                    .build();

            assertThatThrownBy(() -> WorkflowGraph.of(definition))
                    .isInstanceOfSatisfying(GraphValidationException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(ErrorCode.GRAPH_DUPLICATE_NODE);
                        assertThat(ex.offenders()).contains("a");
                    });
        }

        @Test
        @DisplayName("rejects edges that reference an unknown node and names the endpoint")
        void shouldRejectUnknownEdgeEndpoint() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .id("dangling")
                    .node(tool("a"))
                    .edge("a", "ghost")
                    .build();

            assertThatThrownBy(() -> WorkflowGraph.of(definition))
                    .isInstanceOfSatisfying(GraphValidationException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(ErrorCode.GRAPH_UNKNOWN_EDGE_ENDPOINT);
                        assertThat(ex.offenders()).contains("ghost");
                    });
        }

        @Test
        @DisplayName("rejects a fully cyclic graph for having no entry point")
        void shouldRejectGraphWithoutEntryPoint() {
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .id("no-entry")
                    .node(tool("a")).node(tool("b"))
                    .edge("a", "b").edge("b", "a")
                    .build();

            assertThatThrownBy(() -> WorkflowGraph.of(definition))
                    .isInstanceOfSatisfying(GraphValidationException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.GRAPH_NO_ENTRY_POINT));
        }

        @Test
        @DisplayName("detects a cycle reachable from an entry point and reports the offending path")
        void shouldDetectCycleAndReportOffenders() {
            // entry is a legitimate root, but a <-> b is a cycle the scheduler can never drain.
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .id("cyclic")
                    .node(tool("entry")).node(tool("a")).node(tool("b"))
                    .edge("entry", "a")
                    .edge("a", "b")
                    .edge("b", "a")
                    .build();

            assertThatThrownBy(() -> WorkflowGraph.of(definition))
                    .isInstanceOfSatisfying(GraphValidationException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(ErrorCode.GRAPH_CYCLE_DETECTED);
                        assertThat(ex.offenders()).contains("a", "b");
                        assertThat(ex.getMessage()).contains("->");
                    });
        }
    }

    // -----------------------------------------------------------------------------------------
    // unreachableFrom
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("propagates unreachability through a chain after a skipped node")
    void shouldPropagateUnreachability() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("chain")
                .node(tool("a")).node(tool("c")).node(tool("d"))
                .edge("a", "c").edge("c", "d")
                .build();
        WorkflowGraph graph = WorkflowGraph.of(definition);

        // Nothing settled: skipping 'a' makes the whole chain unreachable.
        assertThat(graph.unreachableFrom(Set.of("a"), Set.of()))
                .containsExactlyInAnyOrder("a", "c", "d");
    }

    @Test
    @DisplayName("stops propagating unreachability at a settled node")
    void shouldStopPropagationAtSettledNode() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id("chain")
                .node(tool("a")).node(tool("c")).node(tool("d"))
                .edge("a", "c").edge("c", "d")
                .build();
        WorkflowGraph graph = WorkflowGraph.of(definition);

        // 'a' settled, so it is not unreachable; the skip starts at 'c' and reaches 'd'.
        Set<String> unreachable = graph.unreachableFrom(Set.of("c"), Set.of("a"));
        assertThat(unreachable).containsExactlyInAnyOrder("c", "d");
        assertThat(unreachable).doesNotContain("a");
    }

    @Test
    @DisplayName("keeps a node reachable while at least one predecessor is live")
    void shouldKeepJoinReachableWhenOneBranchSurvives() {
        WorkflowGraph graph = WorkflowGraph.of(diamond());

        // Only the b->d branch is skipped; c still feeds d, so d stays reachable.
        Set<String> unreachable = graph.unreachableFrom(Set.of("b"), Set.of("a", "c"));
        assertThat(unreachable).containsExactly("b");
    }

    @Test
    @DisplayName("exposes node definitions and membership by id")
    void shouldExposeNodesById() {
        WorkflowGraph graph = WorkflowGraph.of(diamond());
        assertThat(graph.contains("a")).isTrue();
        assertThat(graph.contains("nope")).isFalse();
        assertThat(graph.nodeIds()).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(List.copyOf(graph.nodes())).hasSize(4);
    }
}
