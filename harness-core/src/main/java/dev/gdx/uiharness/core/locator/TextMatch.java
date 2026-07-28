package dev.gdx.uiharness.core.locator;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable text-matching rule applied to Unicode-whitespace-normalized node text. */
public final class TextMatch {
    private static final int MAX_PATTERN_LENGTH = 16_384;

    /** Supported text comparison modes. */
    public enum Mode {
        /** Exact, case-sensitive comparison. */
        EXACT,
        /** Exact comparison that ignores Unicode-aware Java case differences. */
        CASE_INSENSITIVE_EXACT,
        /** Case-sensitive substring comparison. */
        SUBSTRING,
        /** Regular-expression search. */
        REGEX
    }

    private final Mode mode;
    private final String source;
    private final String normalizedSource;
    private final Pattern pattern;

    private TextMatch(Mode mode, String source) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.source = requireBounded(source, "text pattern");
        normalizedSource = mode == Mode.REGEX ? source : normalize(source);
        pattern = mode == Mode.REGEX ? Pattern.compile(source) : null;
    }

    /** Creates an exact, case-sensitive matcher. */
    public static TextMatch exact(String expected) {
        return new TextMatch(Mode.EXACT, expected);
    }

    /** Creates an exact matcher that ignores case. */
    public static TextMatch caseInsensitiveExact(String expected) {
        return new TextMatch(Mode.CASE_INSENSITIVE_EXACT, expected);
    }

    /** Creates a case-sensitive substring matcher. */
    public static TextMatch substring(String expected) {
        return new TextMatch(Mode.SUBSTRING, expected);
    }

    /** Creates a regular-expression matcher and compiles its pattern immediately. */
    public static TextMatch regex(String expression) {
        return new TextMatch(Mode.REGEX, expression);
    }

    /** Returns the comparison mode. */
    public Mode mode() {
        return mode;
    }

    /** Returns the caller-supplied pattern text. */
    public String source() {
        return source;
    }

    boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalizedCandidate = normalize(candidate);
        return switch (mode) {
            case EXACT -> normalizedCandidate.equals(normalizedSource);
            case CASE_INSENSITIVE_EXACT ->
                    normalizedCandidate.equalsIgnoreCase(normalizedSource);
            case SUBSTRING -> normalizedCandidate.contains(normalizedSource);
            case REGEX -> pattern.matcher(normalizedCandidate).find();
        };
    }

    static String requireBounded(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName + " exceeds " + MAX_PATTERN_LENGTH + " characters");
        }
        return value;
    }

    static String normalize(String value) {
        Objects.requireNonNull(value, "value");
        if (!needsNormalization(value)) {
            return value;
        }

        var normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnicodeWhitespace(codePoint)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.appendCodePoint(codePoint);
            }
        }
        return normalized.toString();
    }

    private static boolean needsNormalization(String value) {
        boolean previousWhitespace = true;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean whitespace = isUnicodeWhitespace(codePoint);
            if (whitespace && (codePoint != ' ' || previousWhitespace || offset == value.length())) {
                return true;
            }
            previousWhitespace = whitespace;
        }
        return false;
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof TextMatch that
                        && mode == that.mode
                        && source.equals(that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, source);
    }

    @Override
    public String toString() {
        return mode + "(\"" + source + "\")";
    }
}
