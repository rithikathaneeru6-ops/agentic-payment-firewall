package com.razorpay.apf.benchmark;

import java.util.*;

public class Evaluator {

    public static class Metrics {
        public int nTotal, nSafe, nUnsafe, nAmbiguous;
        public int truePositive, falsePositive, trueNegative, falseNegative;
        public double precision, recall, falsePositiveRate;
        public int ambiguousCorrectlyChallenged;
        public double ambiguousHandlingRate;
        public double preventedLossInr;
        public double falsePositiveCostInr;
        public double netValueInr;

        public String toReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("===== Agentic Payment Firewall — Benchmark Results (Java baseline) =====\n");
            sb.append(String.format("Total transactions        : %d%n", nTotal));
            sb.append(String.format("  Safe                     : %d%n", nSafe));
            sb.append(String.format("  Unsafe (violation+inj.)  : %d%n", nUnsafe));
            sb.append(String.format("  Ambiguous                : %d%n", nAmbiguous));
            sb.append("---------------------------------------------------------------\n");
            sb.append(String.format("True Positive              : %d%n", truePositive));
            sb.append(String.format("False Positive             : %d%n", falsePositive));
            sb.append(String.format("True Negative              : %d%n", trueNegative));
            sb.append(String.format("False Negative             : %d%n", falseNegative));
            sb.append("---------------------------------------------------------------\n");
            sb.append(String.format(Locale.ROOT, "Precision                   : %.4f%n", precision));
            sb.append(String.format(Locale.ROOT, "Recall                      : %.4f%n", recall));
            sb.append(String.format(Locale.ROOT, "False Positive Rate         : %.4f%n", falsePositiveRate));
            sb.append(String.format(Locale.ROOT, "Ambiguous handling rate     : %.4f (%d/%d correctly routed to CHALLENGE)%n",
                    ambiguousHandlingRate, ambiguousCorrectlyChallenged, nAmbiguous));
            sb.append("---------------------------------------------------------------\n");
            sb.append(String.format("Prevented loss              : %s%n", Rupees.format(preventedLossInr)));
            sb.append(String.format("False-positive cost         : %s%n", Rupees.format(falsePositiveCostInr)));
            sb.append(String.format("Net value                   : %s%n", Rupees.format(netValueInr)));
            return sb.toString();
        }

        public String toJson() {
            return String.format(Locale.ROOT, """
                    {
                      "n_total": %d,
                      "n_safe": %d,
                      "n_unsafe": %d,
                      "n_ambiguous": %d,
                      "true_positive": %d,
                      "false_positive": %d,
                      "true_negative": %d,
                      "false_negative": %d,
                      "precision": %.4f,
                      "recall": %.4f,
                      "false_positive_rate": %.4f,
                      "ambiguous_correctly_challenged": %d,
                      "ambiguous_handling_rate": %.4f,
                      "prevented_loss_inr": %.2f,
                      "prevented_loss_inr_formatted": "%s",
                      "false_positive_cost_inr": %.2f,
                      "false_positive_cost_inr_formatted": "%s",
                      "net_value_inr": %.2f,
                      "net_value_inr_formatted": "%s"
                    }
                    """,
                    nTotal, nSafe, nUnsafe, nAmbiguous,
                    truePositive, falsePositive, trueNegative, falseNegative,
                    precision, recall, falsePositiveRate,
                    ambiguousCorrectlyChallenged, ambiguousHandlingRate,
                    preventedLossInr, Rupees.format(preventedLossInr),
                    falsePositiveCostInr, Rupees.format(falsePositiveCostInr),
                    netValueInr, Rupees.format(netValueInr));
        }
    }

    private final RiskScorer scorer;
    private final double fpCostPerBlockedCase;
    private final double challengeFrictionCost;

    public Evaluator(RiskScorer scorer, double fpCostPerBlockedCase, double challengeFrictionCost) {
        this.scorer = scorer;
        this.fpCostPerBlockedCase = fpCostPerBlockedCase;
        this.challengeFrictionCost = challengeFrictionCost;
    }

    public Metrics evaluate(List<Transaction> dataset, List<String[]> rowsOut) {
        int tp = 0, fp = 0, tn = 0, fn = 0, ambiguousCorrect = 0, nAmbiguous = 0;
        double preventedLoss = 0.0, fpCostTotal = 0.0;

        for (Transaction t : dataset) {
            RiskScorer.ScoredDecision d = scorer.score(t);
            if (rowsOut != null) {
                rowsOut.add(new String[]{
                        t.id, t.authorizedPurpose, String.valueOf(t.spendLimit), t.itemDescription,
                        String.valueOf(t.amount), t.merchantName, t.merchantCategory,
                        t.trueLabel, t.expectedAction,
                        String.valueOf(d.riskScore()), d.action(), d.reason()
                });
            }

            if (t.trueLabel.equals("AMBIGUOUS")) {
                nAmbiguous++;
                if (d.action().equals("CHALLENGE")) ambiguousCorrect++;
                continue;
            }

            boolean isUnsafe = t.trueLabel.equals("VIOLATION") || t.trueLabel.equals("INJECTION");
            boolean predictedUnsafe = d.action().equals("BLOCK") || d.action().equals("CHALLENGE");

            if (isUnsafe && predictedUnsafe) {
                tp++;
                preventedLoss += t.amount;
            } else if (isUnsafe) {
                fn++;
            } else if (predictedUnsafe) {
                fp++;
                fpCostTotal += d.action().equals("CHALLENGE") ? challengeFrictionCost : fpCostPerBlockedCase;
            } else {
                tn++;
            }
        }

        Metrics m = new Metrics();
        m.nTotal = dataset.size();
        m.nSafe = tn + fp;
        m.nUnsafe = tp + fn;
        m.nAmbiguous = nAmbiguous;
        m.truePositive = tp;
        m.falsePositive = fp;
        m.trueNegative = tn;
        m.falseNegative = fn;
        m.precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        m.recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        m.falsePositiveRate = (fp + tn) == 0 ? 0.0 : (double) fp / (fp + tn);
        m.ambiguousCorrectlyChallenged = ambiguousCorrect;
        m.ambiguousHandlingRate = nAmbiguous == 0 ? 0.0 : (double) ambiguousCorrect / nAmbiguous;
        m.preventedLossInr = Math.round(preventedLoss * 100.0) / 100.0;
        m.falsePositiveCostInr = Math.round(fpCostTotal * 100.0) / 100.0;
        m.netValueInr = Math.round((preventedLoss - fpCostTotal) * 100.0) / 100.0;
        return m;
    }
}
