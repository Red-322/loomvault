package io.loomvault.core.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-model token pricing.
 *
 * @param model            model id this price applies to
 * @param inputPerMillion  USD per 1,000,000 prompt tokens
 * @param outputPerMillion USD per 1,000,000 completion tokens
 */
public record ModelPricing(String model, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    public ModelPricing {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        inputPerMillion = inputPerMillion == null ? BigDecimal.ZERO : inputPerMillion;
        outputPerMillion = outputPerMillion == null ? BigDecimal.ZERO : outputPerMillion;
    }

    public static ModelPricing of(String model, String inputPerMillion, String outputPerMillion) {
        return new ModelPricing(model, new BigDecimal(inputPerMillion), new BigDecimal(outputPerMillion));
    }

    public static ModelPricing free(String model) {
        return new ModelPricing(model, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Cost of a single call, rounded to 8 decimals so cent-level sums stay exact enough. */
    public BigDecimal cost(long inputTokens, long outputTokens) {
        return inputPerMillion.multiply(BigDecimal.valueOf(Math.max(0L, inputTokens)))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(Math.max(0L, outputTokens))))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
    }

    public boolean isFree() {
        return inputPerMillion.signum() == 0 && outputPerMillion.signum() == 0;
    }
}
