package com.razorpay.apf.benchmark;

/**
 * A single synthetic (or real) proposed agent transaction, plus its
 * ground-truth label for benchmarking purposes.
 *
 * true_label: SAFE | VIOLATION | INJECTION | AMBIGUOUS
 * expectedAction: APPROVE | BLOCK | CHALLENGE
 */
public class Transaction {
    public String id;
    public String authorizedPurpose;
    public double spendLimit;
    public String itemDescription;
    public double amount;
    public String merchantName;
    public String merchantCategory;
    public String contextLog;
    public int sequencePosition;
    public int txCountLastHour;
    public String trueLabel;
    public String expectedAction;

    public Transaction(String id, String authorizedPurpose, double spendLimit,
                        String itemDescription, double amount, String merchantName,
                        String merchantCategory, String contextLog, int sequencePosition,
                        int txCountLastHour, String trueLabel, String expectedAction) {
        this.id = id;
        this.authorizedPurpose = authorizedPurpose;
        this.spendLimit = spendLimit;
        this.itemDescription = itemDescription;
        this.amount = amount;
        this.merchantName = merchantName;
        this.merchantCategory = merchantCategory;
        this.contextLog = contextLog;
        this.sequencePosition = sequencePosition;
        this.txCountLastHour = txCountLastHour;
        this.trueLabel = trueLabel;
        this.expectedAction = expectedAction;
    }

    public String toCsvRow() {
        return String.join(",",
                esc(id), esc(authorizedPurpose), String.valueOf(spendLimit),
                esc(itemDescription), String.valueOf(amount), esc(merchantName),
                esc(merchantCategory), esc(contextLog), String.valueOf(sequencePosition),
                String.valueOf(txCountLastHour), esc(trueLabel), esc(expectedAction));
    }

    private String esc(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    public static String csvHeader() {
        return "id,authorized_purpose,spend_limit,item_description,amount,merchant_name," +
                "merchant_category,context_log,sequence_position,tx_count_last_hour," +
                "true_label,expected_action";
    }
}
