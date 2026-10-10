package io.loomvault.server.tools;

import io.loomvault.core.engine.NodeContext;
import io.loomvault.core.engine.ToolRegistry;
import io.loomvault.core.json.LoomJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tool set the bundled example workflows are built from.
 *
 * <p>Deliberately split between pure and side-effecting tools, because that distinction is the
 * whole point of the engine's recovery story:</p>
 * <ul>
 *   <li>{@code fetch_ticket}, {@code lookup_customer}, {@code now}, {@code text_stats} are pure
 *       reads — replaying them is free, so they are marked idempotent.</li>
 *   <li>{@code notify} and {@code record_outcome} write to the outside world and are marked
 *       {@code idempotent = false}: if a crash interrupts one of them, the engine logs a recovery
 *       warning rather than quietly pretending the side effect may not have happened.</li>
 * </ul>
 *
 * <p>In a real deployment these would call an internal API or a database; the demo keeps them
 * in-process so that {@code docker compose up} needs nothing but the database.</p>
 */
@Component
public final class BuiltinTools {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTools.class);

    private final Map<String, Map<String, Object>> tickets;
    private final Map<String, Map<String, Object>> customers;

    /** Side-effect log, queryable through {@code /api/system/effects}. */
    private final Map<String, Map<String, Object>> notifications = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> outcomes = new ConcurrentHashMap<>();
    private final AtomicInteger effectSequence = new AtomicInteger();

    public BuiltinTools() {
        this.tickets = indexById(loadJson("data/tickets.json"), "id");
        this.customers = indexById(loadJson("data/customers.json"), "id");
        log.info("builtin.tools.dataset tickets={} customers={}", tickets.size(), customers.size());
    }

    /** Registers every tool into the given registry. Called once at startup. */
    public ToolRegistry registerInto(ToolRegistry registry) {
        registry.register("fetch_ticket", "Loads one support ticket from the bundled dataset", true,
                (args, ctx) -> fetchTicket(args));
        registry.register("lookup_customer", "Loads one customer record from the bundled dataset", true,
                (args, ctx) -> lookupCustomer(args));
        registry.register("now", "Returns the current instant in ISO-8601 form", true,
                (args, ctx) -> Map.of("now", Instant.now().toString(), "epochMs", Instant.now().toEpochMilli()));
        registry.register("text_stats", "Counts characters, words and lines of a text", true,
                (args, ctx) -> textStats(args));
        registry.register("notify", "Simulates sending a notification to a customer (side effect)", false,
                (args, ctx) -> notify(args, ctx));
        registry.register("record_outcome", "Records the workflow's final decision (side effect)", false,
                (args, ctx) -> recordOutcome(args, ctx));
        return registry;
    }

    // -----------------------------------------------------------------------------------------

    private Map<String, Object> fetchTicket(Map<String, Object> args) {
        String id = required(args, "ticketId");
        Map<String, Object> ticket = tickets.get(id);
        if (ticket == null) {
            // Fail loudly: a workflow that silently continues on a missing ticket produces
            // confident nonsense downstream.
            throw new IllegalArgumentException("unknown ticketId '" + id + "'; known ids: " + tickets.keySet());
        }
        Map<String, Object> result = new LinkedHashMap<>(ticket);
        result.put("found", true);
        return result;
    }

    private Map<String, Object> lookupCustomer(Map<String, Object> args) {
        String id = required(args, "customerId");
        Map<String, Object> customer = customers.get(id);
        if (customer == null) {
            throw new IllegalArgumentException("unknown customerId '" + id + "'");
        }
        int openTickets = (int) tickets.values().stream()
                .filter(t -> id.equals(String.valueOf(t.get("customerId"))))
                .count();
        Map<String, Object> result = new LinkedHashMap<>(customer);
        result.put("openTicketsInDataset", openTickets);
        return result;
    }

    private Map<String, Object> textStats(Map<String, Object> args) {
        String text = String.valueOf(args.getOrDefault("text", ""));
        int words = text.isBlank() ? 0 : text.strip().split("\\s+").length;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characters", text.length());
        result.put("words", words);
        result.put("lines", text.isEmpty() ? 0 : text.split("\\R", -1).length);
        return result;
    }

    private Map<String, Object> notify(Map<String, Object> args, NodeContext ctx) {
        String ticketId = String.valueOf(args.getOrDefault("ticketId", "unknown"));
        String channel = String.valueOf(args.getOrDefault("channel", "email"));
        String message = String.valueOf(args.getOrDefault("message", ""));
        String id = "NTF-" + effectSequence.incrementAndGet();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("ticketId", ticketId);
        record.put("channel", channel);
        record.put("message", message);
        record.put("executionId", ctx.executionId());
        record.put("nodeId", ctx.nodeId());
        record.put("sentAt", Instant.now().toString());
        notifications.put(id, record);

        log.info("tool.notify id={} ticket={} channel={} execution={}", id, ticketId, channel, ctx.executionId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("notificationId", id);
        result.put("delivered", true);
        result.put("channel", channel);
        return result;
    }

    private Map<String, Object> recordOutcome(Map<String, Object> args, NodeContext ctx) {
        String ticketId = String.valueOf(args.getOrDefault("ticketId", "unknown"));
        String decision = String.valueOf(args.getOrDefault("decision", "unknown"));
        String id = "OUT-" + effectSequence.incrementAndGet();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("ticketId", ticketId);
        record.put("decision", decision);
        record.put("notes", args.getOrDefault("notes", ""));
        record.put("executionId", ctx.executionId());
        record.put("recordedAt", Instant.now().toString());
        outcomes.put(id, record);

        log.info("tool.record_outcome id={} ticket={} decision={}", id, ticketId, decision);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcomeId", id);
        result.put("stored", true);
        return result;
    }

    // -----------------------------------------------------------------------------------------

    /** Everything the demo's side-effecting tools have written, newest last. */
    public List<Map<String, Object>> notifications() {
        return new ArrayList<>(notifications.values());
    }

    public List<Map<String, Object>> outcomes() {
        return new ArrayList<>(outcomes.values());
    }

    public Collection<Map<String, Object>> tickets() {
        return tickets.values();
    }

    public Collection<Map<String, Object>> customers() {
        return customers.values();
    }

    private static String required(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("tool argument '" + key + "' is required");
        }
        return String.valueOf(value);
    }

    private static Map<String, Map<String, Object>> indexById(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get(key);
            if (id != null) {
                indexed.put(String.valueOf(id), row);
            }
        }
        return indexed;
    }

    private static List<Map<String, Object>> loadJson(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return LoomJson.mapper().readValue(raw,
                    LoomJson.mapper().getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (IOException ex) {
            log.warn("builtin.tools.dataset.missing location={} reason={}", classpathLocation, ex.getMessage());
            return List.of();
        }
    }
}
