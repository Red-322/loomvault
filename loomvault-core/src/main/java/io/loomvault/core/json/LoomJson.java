package io.loomvault.core.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single {@link ObjectMapper} used across LoomVault.
 *
 * <p>Keeping one configured mapper avoids the classic bug where a payload round-trips through
 * two differently configured mappers and silently changes shape — which, for an engine whose
 * durability guarantee is "the journal is the truth", would be fatal. Time values are written as
 * ISO-8601 strings so that journals stay human-readable and database-portable.</p>
 */
public final class LoomJson {

    private static final ObjectMapper MAPPER = configure(new ObjectMapper());

    private LoomJson() {
    }

    private static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    /** Shared mapper. Callers must not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Serialises a value to compact JSON. Never returns {@code null}. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialise " + describe(value), ex);
        }
    }

    /** Serialises a value to indented JSON; used by the docs generator and API debug output. */
    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialise " + describe(value), ex);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to deserialise into " + type.getSimpleName(), ex);
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return raw == null ? Map.of() : raw;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to deserialise JSON object", ex);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse JSON", ex);
        }
    }

    /**
     * Normalises any object into a string-keyed map.
     *
     * <p>Node handlers return plain maps, but callers occasionally hand back typed records; this
     * keeps the engine's contract ("node output is a JSON object") enforceable in one place.</p>
     */
    public static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalised = new LinkedHashMap<>();
            map.forEach((k, v) -> normalised.put(String.valueOf(k), v));
            return normalised;
        }
        try {
            Map<String, Object> converted = MAPPER.convertValue(value,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return converted == null ? Map.of() : converted;
        } catch (Exception ex) {
            // Last resort: wrap the scalar so downstream code always sees a map.
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("value", value);
            return single;
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
