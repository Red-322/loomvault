package io.loomvault.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level smoke tests over the real application context.
 *
 * <p>Runs against the {@code standalone} profile: H2 in memory, in-process response cache and the
 * deterministic mock provider, so no Docker is required. The demo auto-run is disabled so the
 * assertions observe only what this test triggers. Mock latency is turned right down to keep the
 * suite fast without faking the wiring.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "loomvault.workflows.run-demo-on-startup=false",
        "loomvault.llm.mock.min-latency=1ms",
        "loomvault.llm.mock.max-latency=5ms"
})
@ActiveProfiles("standalone")
class ApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // -----------------------------------------------------------------------------------------
    // System introspection
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/system/info reports the bundled workflows")
    @SuppressWarnings("unchecked")
    void shouldReportRegisteredWorkflows() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/system/info", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<String> registered = (List<String>) response.getBody().get("registeredWorkflows");
        assertThat(registered).contains("support-triage", "content-review");
    }

    @Test
    @DisplayName("GET /api/workflows returns a non-empty catalogue")
    void shouldListWorkflows() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/workflows", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isNotEmpty();
    }

    // -----------------------------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/workflows/validate accepts a sound definition")
    void shouldValidateValidDefinition() {
        Map<String, Object> definition = Map.of(
                "id", "validate-ok",
                "nodes", List.of(
                        toolNode("a"),
                        toolNode("b")),
                "edges", List.of(Map.of("from", "a", "to", "b")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/workflows/validate", definition, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("valid")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("POST /api/workflows/validate rejects a cyclic definition")
    void shouldRejectCyclicDefinition() {
        Map<String, Object> cyclic = Map.of(
                "id", "validate-cycle",
                "nodes", List.of(
                        toolNode("entry"),
                        toolNode("a"),
                        toolNode("b")),
                "edges", List.of(
                        Map.of("from", "entry", "to", "a"),
                        Map.of("from", "a", "to", "b"),
                        Map.of("from", "b", "to", "a")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/workflows/validate", cyclic, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("valid")).isEqualTo(Boolean.FALSE);
    }

    // -----------------------------------------------------------------------------------------
    // Execution lifecycle
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/executions?wait=true runs content-review to a settled state")
    void shouldRunContentReviewWorkflow() {
        Map<String, Object> request = Map.of(
                "workflowId", "content-review",
                "inputs", Map.of("draft",
                        "A short draft used by the integration test to exercise the review pipeline."));

        ResponseEntity<Map> response =
                restTemplate.postForEntity("/api/executions?wait=true", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isIn("SUCCEEDED", "WAITING_APPROVAL");
    }

    @Test
    @DisplayName("GET /api/executions/{id} returns 404 for an unknown execution")
    void shouldReturnNotFoundForUnknownExecution() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/executions/nope", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/executions for an unknown workflow returns a 4xx client error")
    void shouldReturnClientErrorForUnknownWorkflow() {
        Map<String, Object> request = Map.of("workflowId", "does-not-exist");

        ResponseEntity<Map> response =
                restTemplate.postForEntity("/api/executions?wait=true", request, Map.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    // -----------------------------------------------------------------------------------------

    private static Map<String, Object> toolNode(String id) {
        return Map.of("id", id, "kind", "TOOL",
                "config", Map.of("tool", "text_stats", "args", Map.of()));
    }
}
