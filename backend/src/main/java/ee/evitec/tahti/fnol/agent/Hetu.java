package ee.evitec.tahti.fnol.agent;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finnish personal identity code (henkilötunnus) validation. Speech-to-text routinely drops
 * or merges a digit, so the format and the check character are verified deterministically
 * before a hetu is ever sent to the policy API: a mis-heard hetu must trigger a follow-up
 * question, not an empty "no policies" answer.
 */
public final class Hetu {

    private static final Pattern FORMAT = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})([-+ABCDEFYXWVU])(\\d{3})([0-9A-Z])$");
    private static final String CHECK_CHARS = "0123456789ABCDEFHJKLMNPRSTUVWXY";

    private Hetu() {}

    /** Upper-cases and strips whitespace/punctuation the model or STT may have inserted. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s.:,]", "");
        // "−" or "–" heard as a dash
        return s.replace('\u2212', '-').replace('\u2013', '-');
    }

    /** @return the normalised hetu if format, date part and check character are all valid */
    public static Optional<String> validate(String raw) {
        String s = normalize(raw);
        return isValid(s) ? Optional.of(s) : Optional.empty();
    }

    public static boolean isValid(String hetu) {
        if (hetu == null) return false;
        Matcher m = FORMAT.matcher(hetu);
        if (!m.matches()) return false;
        int day = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        if (day < 1 || day > 31 || month < 1 || month > 12) return false;
        String digits = m.group(1) + m.group(2) + m.group(3) + m.group(5);
        int index = (int) (Long.parseLong(digits) % 31);
        return CHECK_CHARS.charAt(index) == m.group(6).charAt(0);
    }

    /** Human-readable reason for the advisor log; empty when valid. */
    public static String problem(String raw) {
        String s = normalize(raw);
        if (s.isEmpty()) return "puuttuu";
        if (!FORMAT.matcher(s).matches()) {
            return "muoto virheellinen (odotettu PPKKVV-NNNT, kuultu '" + s + "')";
        }
        return isValid(s) ? "" : "tarkistusmerkki ei täsmää (kuultu '" + s + "')";
    }
}
