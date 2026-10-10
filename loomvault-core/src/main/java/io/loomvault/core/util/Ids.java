package io.loomvault.core.util;

import java.time.Instant;
import java.util.UUID;

/**
 * Identifier generation.
 *
 * <p>Execution ids are time-sortable and prefixed ({@code exe_}) so that a glance at a log line
 * tells you what kind of entity it refers to. Sortability matters more than it sounds: "show me
 * the last ten runs" should be a plain ORDER BY, not a join on timestamps.</p>
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {
    }

    public static String executionId() {
        return "exe_" + sortableToken();
    }

    public static String nodeExecutionId(String executionId, String nodeId) {
        return executionId + ":" + nodeId;
    }

    /**
     * 26-character token: 8 characters of millisecond timestamp in base-36 plus 18 random
     * base-36 characters. Lexicographic order equals chronological order within one process, and
     * the birthday-collision risk is nil for the volumes this engine targets.
     */
    private static String sortableToken() {
        long millis = Instant.now().toEpochMilli();
        StringBuilder sb = new StringBuilder(26);
        for (int shift = 28; shift >= 0; shift -= 4) {
            sb.append(ALPHABET[(int) ((millis >>> shift) & 0x0F)]);
        }
        UUID random = UUID.randomUUID();
        long hi = random.getMostSignificantBits();
        long lo = random.getLeastSignificantBits();
        for (int i = 0; i < 9; i++) {
            sb.append(ALPHABET[(int) ((hi >>> (i * 4)) & 0x1F) % ALPHABET.length]);
        }
        for (int i = 0; i < 9; i++) {
            sb.append(ALPHABET[(int) ((lo >>> (i * 4)) & 0x1F) % ALPHABET.length]);
        }
        return sb.toString();
    }
}
