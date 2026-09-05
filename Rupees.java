package com.razorpay.apf.benchmark;

/**
 * Formats amounts using the Indian numbering system (2,2,3 digit grouping —
 * lakh / crore) instead of the international 3-3-3 grouping, e.g.:
 *
 *   2890553.22   ->  ₹28,90,553.22   (NOT ₹2,890,553.22)
 *   100000       ->  ₹1,00,000       (1 lakh)
 *   10000000     ->  ₹1,00,00,000    (1 crore)
 *
 * Implemented manually (no locale/ICU dependency) so it behaves identically
 * on any JVM/OS regardless of installed locale data.
 */
public final class Rupees {

    private Rupees() {}

    public static String format(double amount) {
        boolean negative = amount < 0;
        amount = Math.abs(amount);

        long rupees = (long) amount;
        int paise = (int) Math.round((amount - rupees) * 100);
        if (paise == 100) { // rounding edge case
            rupees += 1;
            paise = 0;
        }

        String grouped = groupIndian(String.valueOf(rupees));
        String result = "\u20B9" + grouped + "." + String.format("%02d", paise);
        return negative ? "-" + result : result;
    }

    /** Same as {@link #format(double)} but without paise, for whole-rupee figures. */
    public static String formatWhole(double amount) {
        boolean negative = amount < 0;
        long rupees = Math.round(Math.abs(amount));
        String grouped = groupIndian(String.valueOf(rupees));
        return (negative ? "-" : "") + "\u20B9" + grouped;
    }

    private static String groupIndian(String digits) {
        if (digits.length() <= 3) return digits;

        String last3 = digits.substring(digits.length() - 3);
        String remainder = digits.substring(0, digits.length() - 3);

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = remainder.length() - 1; i >= 0; i--) {
            sb.append(remainder.charAt(i));
            count++;
            if (count % 2 == 0 && i != 0) {
                sb.append(',');
            }
        }
        return sb.reverse().toString() + "," + last3;
    }
}
