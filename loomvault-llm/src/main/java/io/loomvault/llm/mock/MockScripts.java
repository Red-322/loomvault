package io.loomvault.llm.mock;

import io.loomvault.core.llm.ChatRequest;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic response generator for the offline mock provider.
 *
 * <p>Every response is a pure function of the prompt text: the same request always yields the
 * same completion, across processes and machines. That property is what makes the mock usable in
 * CI — a replayed execution produces byte-identical journals regardless of when it runs.</p>
 *
 * <p>Responses are shaped like the output of a real model for the four prompt archetypes the
 * bundled example workflows use (classification, scoring, summarisation, rewriting) so that the
 * demo shows plausible data rather than {@code "lorem ipsum"}.</p>
 */
final class MockScripts {

    private static final String[] SUPPORT_CATEGORIES = {
            "billing", "technical", "account", "shipping", "other"
    };

    private static final String[] SENTIMENTS = {"negative", "neutral", "positive"};

    private MockScripts() {
    }

    /** Entry point: inspects the prompt and picks a response archetype. */
    static String respond(ChatRequest request) {
        String prompt = lastUserContent(request);
        String lower = prompt.toLowerCase(Locale.ROOT);
        long seed = stableSeed(prompt);

        if (mentions(lower, "classify", "categor", "triage", "intent", "label")) {
            return classification(prompt, seed);
        }
        if (mentions(lower, "score", "rate", "grade", "assess", "rank", "evaluate")) {
            return scoring(prompt, seed);
        }
        if (mentions(lower, "summar", "tl;dr", "condense", "brief")) {
            return summarise(prompt, seed);
        }
        if (mentions(lower, "rewrite", "polish", "improve", "rephrase", "edit")) {
            return rewrite(prompt, seed);
        }
        if (mentions(lower, "extract", "parse", "fields", "entities")) {
            return extraction(prompt, seed);
        }
        if (mentions(lower, "translate")) {
            return translate(prompt, seed);
        }
        return narrative(prompt, request, seed);
    }

    // =========================================================================================
    // Archetypes
    // =========================================================================================

    private static String classification(String prompt, long seed) {
        String category = pick(SUPPORT_CATEGORIES, seed);
        String sentiment = pick(SENTIMENTS, seed >> 8);
        int confidence = 72 + (int) Math.floorMod(seed >> 16, 27); // 72..98
        String reason = "Signals in the ticket text point to " + category
                + " and an overall " + sentiment + " tone.";
        return "{\n"
                + "  \"category\": \"" + category + "\",\n"
                + "  \"sentiment\": \"" + sentiment + "\",\n"
                + "  \"confidence\": " + confidence + ",\n"
                + "  \"urgent\": " + (confidence > 90 ? "true" : "false") + ",\n"
                + "  \"reason\": \"" + reason + "\"\n"
                + "}";
    }

    private static String scoring(String prompt, long seed) {
        double overall = 40 + Math.floorMod(seed, 61);            // 40..100
        double clarity = 40 + Math.floorMod(seed >> 7, 61);
        double depth = 40 + Math.floorMod(seed >> 13, 61);
        String verdict = overall >= 75 ? "strong" : overall >= 55 ? "adequate" : "weak";
        String subject = subjectOf(prompt);
        return "{\n"
                + "  \"overall\": " + overall + ",\n"
                + "  \"clarity\": " + clarity + ",\n"
                + "  \"depth\": " + depth + ",\n"
                + "  \"pass\": " + (overall >= 60 ? "true" : "false") + ",\n"
                + "  \"verdict\": \"" + verdict + "\",\n"
                + "  \"rationale\": \"Scored the submission against clarity, depth and "
                + "actionability; the dominant factor was " + (overall >= 60 ? "clear structure" : "missing detail")
                + ". Subject: " + escape(subject) + "\"\n"
                + "}";
    }

    private static String summarise(String prompt, long seed) {
        String subject = subjectOf(prompt);
        int words = Math.min(48, Math.max(12, subject.length() / 12));
        String tone = pick(new String[]{"operational", "analytical", "neutral"}, seed);
        return "[" + tone + " summary, ~" + words + " words] " + subject
                + (subject.endsWith(".") ? "" : ".")
                + " Key points: the request concerns " + pick(SUPPORT_CATEGORIES, seed >> 5)
                + "; the reporter expects a concrete resolution rather than a general answer;"
                + " no blocking dependency was identified.";
    }

    private static String rewrite(String prompt, long seed) {
        String subject = subjectOf(prompt);
        String opener = pick(new String[]{"In short,", "Put plainly,", "To summarise,", "Concisely:"}, seed);
        return opener + " " + subject
                + "\n\nSuggested edit: tighten the opening sentence, move the concrete ask to the "
                + "front, and drop the redundant background paragraph.";
    }

    private static String extraction(String prompt, long seed) {
        String subject = subjectOf(prompt);
        return "{\n"
                + "  \"entities\": [\n"
                + "    {\"type\": \"subject\", \"value\": \"" + escape(subject) + "\"},\n"
                + "    {\"type\": \"priority\", \"value\": \"P" + (1 + Math.floorMod(seed, 3)) + "\"}\n"
                + "  ],\n"
                + "  \"fieldCount\": 2\n"
                + "}";
    }

    private static String translate(String prompt, long seed) {
        String subject = subjectOf(prompt);
        return "[translated] " + subject + " (deterministic mock translation, seed=" + Math.floorMod(seed, 1000) + ")";
    }

    private static String narrative(String prompt, ChatRequest request, long seed) {
        String subject = subjectOf(prompt);
        return "Model '" + request.model() + "' answering deterministically (seed="
                + Math.floorMod(seed, 100000) + ").\n"
                + "Regarding: " + subject + "\n"
                + "Assessment: the request is well-scoped and can be answered without further tool "
                + "calls. Recommended next step: proceed with the outlined plan and record the "
                + "outcome for replay.";
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    /**
     * Extracts the most substantial line of the prompt — in practice the interpolated payload,
     * since prompt templates put the variable part on its own line.
     */
    static String subjectOf(String prompt) {
        String best = "";
        for (String line : prompt.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.length() > best.length() && !trimmed.endsWith(":")) {
                best = trimmed;
            }
        }
        if (best.isEmpty()) {
            best = prompt.strip();
        }
        if (best.length() > 240) {
            best = best.substring(0, 237) + "...";
        }
        return best;
    }

    static String lastUserContent(ChatRequest request) {
        List<io.loomvault.core.llm.ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!io.loomvault.core.llm.ChatMessage.ROLE_SYSTEM.equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return messages.isEmpty() ? "" : messages.get(0).content();
    }

    /** FNV-1a style hash, stable across JVM versions (unlike {@code String.hashCode} guarantees). */
    static long stableSeed(String text) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash & Long.MAX_VALUE;
    }

    private static String pick(String[] options, long seed) {
        return options[(int) Math.floorMod(seed, options.length)];
    }

    private static boolean mentions(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
