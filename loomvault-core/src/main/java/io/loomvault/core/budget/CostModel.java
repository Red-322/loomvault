package io.loomvault.core.budget;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts token counts into money.
 *
 * <p>Lookup is exact-first, then by longest prefix: providers version their models
 * ({@code gpt-4o-2024-08-06}), and operators do not want to enumerate every snapshot in a config
 * file. Unknown models cost {@link BigDecimal#ZERO} and are reported as such — silently inventing
 * a price would make the budget guard lie.</p>
 *
 * <p>The bundled table is a snapshot of published list prices and is meant to be overridden from
 * configuration. Local models (Ollama, vLLM) are priced at zero on purpose: their marginal cost
 * is electricity, not tokens.</p>
 */
public final class CostModel {

    private final Map<String, ModelPricing> table;

    private CostModel(Map<String, ModelPricing> table) {
        this.table = Collections.unmodifiableMap(new LinkedHashMap<>(table));
    }

    /** Prices in USD per million tokens, as of the 0.1.0 release. Override in configuration. */
    public static CostModel defaults() {
        Map<String, ModelPricing> table = new LinkedHashMap<>();
        // Mock models carry synthetic prices so that budget governance can be demonstrated
        // offline — the demo must not be free, or the guard has nothing to guard.
        table.put("mock-fast", ModelPricing.of("mock-fast", "0.10", "0.30"));
        table.put("mock-smart", ModelPricing.of("mock-smart", "1.00", "3.00"));
        table.put("mock-frontier", ModelPricing.of("mock-frontier", "5.00", "15.00"));

        table.put("gpt-4o-mini", ModelPricing.of("gpt-4o-mini", "0.15", "0.60"));
        table.put("gpt-4o", ModelPricing.of("gpt-4o", "2.50", "10.00"));
        table.put("gpt-4.1-mini", ModelPricing.of("gpt-4.1-mini", "0.40", "1.60"));
        table.put("gpt-4.1", ModelPricing.of("gpt-4.1", "2.00", "8.00"));
        table.put("o3-mini", ModelPricing.of("o3-mini", "1.10", "4.40"));
        table.put("claude-3-5-haiku", ModelPricing.of("claude-3-5-haiku", "0.80", "4.00"));
        table.put("claude-3-5-sonnet", ModelPricing.of("claude-3-5-sonnet", "3.00", "15.00"));
        table.put("claude-sonnet-4", ModelPricing.of("claude-sonnet-4", "3.00", "15.00"));
        table.put("deepseek-chat", ModelPricing.of("deepseek-chat", "0.27", "1.10"));
        table.put("deepseek-reasoner", ModelPricing.of("deepseek-reasoner", "0.55", "2.19"));
        table.put("qwen-max", ModelPricing.of("qwen-max", "1.60", "6.40"));
        return new CostModel(table);
    }

    public static CostModel of(Map<String, ModelPricing> explicit) {
        return new CostModel(explicit == null ? Map.of() : explicit);
    }

    /** Merges overrides on top of the defaults; overrides win. */
    public CostModel mergedWith(Map<String, ModelPricing> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        Map<String, ModelPricing> merged = new LinkedHashMap<>(table);
        merged.putAll(overrides);
        return new CostModel(merged);
    }

    public Optional<ModelPricing> lookup(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        ModelPricing exact = table.get(model);
        if (exact != null) {
            return Optional.of(exact);
        }
        // Longest-prefix match: "gpt-4o-2024-08-06" -> "gpt-4o".
        return table.entrySet().stream()
                .filter(e -> model.startsWith(e.getKey()))
                .max(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(String::length)))
                .map(Map.Entry::getValue);
    }

    /** Estimated USD cost of a call; zero for unknown or unpriced models. */
    public BigDecimal estimate(String model, long inputTokens, long outputTokens) {
        return lookup(model).map(p -> p.cost(inputTokens, outputTokens)).orElse(BigDecimal.ZERO);
    }

    public boolean isKnown(String model) {
        return lookup(model).isPresent();
    }

    public Set<String> knownModels() {
        return table.keySet();
    }

    public Map<String, ModelPricing> asMap() {
        return table;
    }
}
