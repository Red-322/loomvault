package io.loomvault.server.api;

import io.loomvault.core.engine.WorkflowRegistry;
import io.loomvault.core.graph.WorkflowGraph;
import io.loomvault.core.model.WorkflowDefinition;
import io.loomvault.core.port.ExecutionRepository;
import io.loomvault.core.engine.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow catalogue: list, inspect, upload, validate, remove.
 *
 * <p>Validation is exposed as its own endpoint ({@code POST /api/workflows/validate}) because
 * graph checks — cycles, dangling edges, unknown tool references — are exactly what a workflow
 * author wants to run repeatedly while editing, without changing what is deployed.</p>
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowRegistry workflows;
    private final ExecutionRepository repository;
    private final ExecutionEngine engine;

    public WorkflowController(WorkflowRegistry workflows, ExecutionRepository repository,
                              ExecutionEngine engine) {
        this.workflows = workflows;
        this.repository = repository;
        this.engine = engine;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return workflows.all().stream().map(this::summarise).toList();
    }

    @GetMapping("/{id}")
    public WorkflowDefinition get(@PathVariable String id) {
        return workflows.require(id);
    }

    /** Uploads (or replaces) a definition. Rejecting invalid graphs here is the whole point. */
    @PostMapping
    public ResponseEntity<WorkflowDefinition> register(@RequestBody WorkflowDefinition definition) {
        workflows.register(definition);
        log.info("api.workflow.registered id={} nodes={} edges={}",
                definition.id(), definition.nodes().size(), definition.edges().size());
        return ResponseEntity.status(HttpStatus.CREATED).body(workflows.require(definition.id()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> unregister(@PathVariable String id) {
        boolean removed = workflows.unregister(id).isPresent();
        return Map.of("workflowId", id, "removed", removed);
    }

    /**
     * Validates without registering. Returns a structured report: {@code valid}, the compiled
     * node order when the graph is sound, and per-issue detail when it is not.
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody WorkflowDefinition definition) {
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            WorkflowGraph graph = WorkflowGraph.of(definition);
            report.put("valid", true);
            report.put("workflowId", definition.id());
            report.put("nodes", graph.nodeIds());
            report.put("roots", graph.roots());
            report.put("topologicalOrder", graph.topologicalOrder());
            report.put("warnings", lint(definition));
            return report;
        } catch (RuntimeException ex) {
            report.put("valid", false);
            report.put("error", ex.getMessage());
            if (ex instanceof io.loomvault.core.error.GraphValidationException graphEx) {
                report.put("code", graphEx.code().name());
                report.put("offenders", graphEx.offenders());
            }
            return report;
        }
    }

    /**
     * Cheap static checks that are warnings rather than errors: unreachable nodes, positions that
     * reference things that do not exist. Kept intentionally shallow — a linter that cries wolf
     * gets ignored.
     */
    private List<String> lint(WorkflowDefinition definition) {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, io.loomvault.core.model.NodeDefinition> byId = new LinkedHashMap<>();
        definition.nodes().forEach(node -> byId.put(node.id(), node));

        for (io.loomvault.core.model.NodeDefinition node : definition.nodes()) {
            if (node.kind() == io.loomvault.core.model.NodeKind.TOOL) {
                String toolName = node.stringConfig("tool", null);
                if (toolName == null) {
                    warnings.add("node '" + node.id() + "' is a TOOL node without a 'tool' config key");
                }
            }
            if (node.kind() == io.loomvault.core.model.NodeKind.LLM) {
                String prompt = node.stringConfig("prompt", null);
                if (prompt == null || prompt.isBlank()) {
                    warnings.add("node '" + node.id() + "' is an LLM node without a 'prompt'");
                    continue;
                }
                for (String path : io.loomvault.core.expr.StringTemplate.referencedPaths(prompt)) {
                    String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                    if (head.startsWith("nodes.")) {
                        String referenced = head.substring("nodes.".length());
                        int dot = referenced.indexOf('.');
                        String referencedNode = dot > 0 ? referenced.substring(0, dot) : referenced;
                        if (!byId.containsKey(referencedNode)) {
                            warnings.add("node '" + node.id() + "' references unknown node '"
                                    + referencedNode + "' in its prompt");
                        }
                    }
                }
            }
        }
        boolean hasSink = definition.nodes().stream().anyMatch(node ->
                definition.edges().stream().noneMatch(edge -> edge.from().equals(node.id())));
        if (!hasSink) {
            warnings.add("workflow has no sink node: every node has an outgoing edge");
        }
        return warnings;
    }

    private Map<String, Object> summarise(WorkflowDefinition definition) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", definition.id());
        summary.put("name", definition.name());
        summary.put("version", definition.version());
        summary.put("description", definition.description());
        summary.put("nodes", definition.nodes().size());
        summary.put("edges", definition.edges().size());
        summary.put("budget", definition.budget().asMap());
        summary.put("definition", definition);
        return summary;
    }

    /** Convenience for the dashboard header: how many runs each workflow has produced. */
    @GetMapping("/{id}/usage")
    public Map<String, Object> usage(@PathVariable String id) {
        WorkflowDefinition definition = workflows.require(id);
        Optional<Instant> newest = repository.findRecent(200).stream()
                .filter(execution -> execution.workflowId().equals(id))
                .map(io.loomvault.core.model.Execution::createdAt)
                .findFirst();
        return Map.of(
                "workflowId", definition.id(),
                "totalExecutions", repository.count(),
                "lastStartedAt", newest.map(Instant::toString).orElse("never"),
                "currentlyActive", engine.activeExecutionIds().size());
    }
}
