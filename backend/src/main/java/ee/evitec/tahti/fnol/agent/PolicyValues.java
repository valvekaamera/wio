package ee.evitec.tahti.fnol.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display helpers for tahti attribute values ("10||Täysarvo", "4390.51175049418"). */
final class PolicyValues {

    private PolicyValues() {}

    /** Code-list value {@code "10||Täysarvo"} -> {@code "Täysarvo"}; other values unchanged. */
    static String label(String raw) {
        if (raw == null) return null;
        int i = raw.indexOf("||");
        return i >= 0 ? raw.substring(i + 2) : raw;
    }

    /** Monetary value rounded to cents, e.g. {@code "4390.51 €"}; non-numeric input unchanged. */
    static String amount(String raw) {
        if (raw == null) return null;
        try {
            return new BigDecimal(raw.strip()).setScale(2, RoundingMode.HALF_UP).toPlainString() + " €";
        } catch (NumberFormatException e) {
            return raw;
        }
    }
}
