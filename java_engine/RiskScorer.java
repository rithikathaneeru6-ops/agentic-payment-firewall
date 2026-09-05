package com.razorpay.apf.benchmark;

import java.util.*;

/**
 * Transparent, rule-weighted baseline risk scorer.
 *
 * This is a stand-in for the production ensemble described in
 * implementation_plan.md Section 4:
 *   - categoryMismatch should come from an embedding-similarity model over
 *     the actual item text, not a merchant-category lookup (this baseline
 *     uses the lookup deliberately, to expose the "laundered through a
 *     generalist merchant" blind spot in the benchmark results).
 *   - injectionFlag should come from a dedicated prompt-injection /
 *     intent-diff classifier, not a keyword scan (this baseline uses a
 *     keyword scan deliberately, to expose the "stealthy injection"
 *     blind spot in the benchmark results).
 */
public class RiskScorer {

    public record ScoredDecision(double riskScore, String action, String reason,
                                  double categoryMismatch, double injectionFlag,
                                  double nearLimitRatio, double frequencyFlag,
                                  double sequenceFlag) {}

    private static final Map<String, String[]> PURPOSE_CATEGORIES = new HashMap<>();
    static {
        PURPOSE_CATEGORIES.put("office supplies", new String[]{"stationery", "office_supplies", "general_retail"});
        PURPOSE_CATEGORIES.put("team lunch", new String[]{"food_delivery", "restaurant", "catering"});
        PURPOSE_CATEGORIES.put("software subscription renewal", new String[]{"saas", "software"});
        PURPOSE_CATEGORIES.put("travel booking (local)", new String[]{"transport", "mobility"});
    }

    private static final String[] INJECTION_KEYWORDS = {
            "ignore previous", "system override", "disregard prior", "hidden instruction",
            "does not apply", "new instruction from user", "important update", "assistant, disregard"
    };

    // Ensemble weights -- learned/tunable in production, hand-set here for explainability.
    private static final double W_CATEGORY = 0.40;
    private static final double W_INJECTION = 0.35;
    private static final double W_NEAR_LIMIT = 0.15;
    private static final double W_FREQUENCY = 0.05;
    private static final double W_SEQUENCE = 0.05;

    private static final double BLOCK_THRESHOLD = 0.70;
    private static final double CHALLENGE_THRESHOLD = 0.30;

    public double categoryMismatch(String purpose, String merchantCategory) {
        String[] allowed = PURPOSE_CATEGORIES.get(purpose);
        if (allowed != null && Arrays.asList(allowed).contains(merchantCategory)) return 0.0;
        if (merchantCategory != null && merchantCategory.contains("_or_")) return 0.5;
        return 1.0;
    }

    public double injectionFlag(String contextLog) {
        String log = contextLog == null ? "" : contextLog.toLowerCase(Locale.ROOT);
        for (String kw : INJECTION_KEYWORDS) {
            if (log.contains(kw)) return 1.0;
        }
        return 0.0;
    }

    public double nearLimitRatio(double amount, double limit) {
        return limit <= 0 ? 0.0 : amount / limit;
    }

    public double frequencyFlag(int txCountLastHour) {
        return Math.min(txCountLastHour / 4.0, 1.0);
    }

    public double sequenceFlag(int sequencePosition) {
        return Math.min(sequencePosition / 5.0, 1.0);
    }

    public ScoredDecision score(Transaction t) {
        double cm = categoryMismatch(t.authorizedPurpose, t.merchantCategory);
        double inj = injectionFlag(t.contextLog);
        double nl = nearLimitRatio(t.amount, t.spendLimit);
        double fr = frequencyFlag(t.txCountLastHour);
        double sq = sequenceFlag(t.sequencePosition);

        double risk = W_CATEGORY * cm + W_INJECTION * inj + W_NEAR_LIMIT * nl
                + W_FREQUENCY * fr + W_SEQUENCE * sq;
        risk = Math.min(risk, 1.0);
        risk = Math.round(risk * 10000.0) / 10000.0;

        String action;
        if (risk >= BLOCK_THRESHOLD) action = "BLOCK";
        else if (risk >= CHALLENGE_THRESHOLD) action = "CHALLENGE";
        else action = "APPROVE";

        List<String> reasons = new ArrayList<>();
        if (cm >= 1.0) {
            reasons.add("item/category does not match authorized purpose '" + t.authorizedPurpose + "'");
        } else if (cm == 0.5) {
            reasons.add("item category is ambiguous relative to authorized purpose");
        }
        if (inj == 1.0) {
            reasons.add("external content in agent context resembles a prompt-injection pattern");
        }
        if (nl >= 0.85) {
            reasons.add(String.format(Locale.ROOT,
                    "amount %s is unusually close to the %s limit",
                    Rupees.formatWhole(t.amount), Rupees.formatWhole(t.spendLimit)));
        }
        if (fr >= 0.75) {
            reasons.add("unusually high transaction frequency in the last hour");
        }
        String reason = reasons.isEmpty() ? "no risk signals triggered" : String.join("; ", reasons);

        return new ScoredDecision(risk, action, reason, cm, inj, nl, fr, sq);
    }
}
