package com.rotation.report;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Minimal streaming JSON builder (no external dependency). Tracks comma placement
 * for nested objects/arrays; NaN and infinite numbers are written as {@code null}.
 * The output is safe to embed inside an HTML {@code <script>} element.
 */
public final class JsonWriter {

    private final StringBuilder out;
    private final Deque<Boolean> firstInScope = new ArrayDeque<>();
    private boolean afterName;

    public JsonWriter() {
        this(new StringBuilder(1 << 16));
    }

    public JsonWriter(StringBuilder out) {
        this.out = out;
    }

    public JsonWriter beginObject() {
        separator();
        out.append('{');
        firstInScope.push(true);
        return this;
    }

    public JsonWriter endObject() {
        firstInScope.pop();
        out.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        separator();
        out.append('[');
        firstInScope.push(true);
        return this;
    }

    public JsonWriter endArray() {
        firstInScope.pop();
        out.append(']');
        return this;
    }

    public JsonWriter name(String name) {
        separator();
        appendString(name);
        out.append(':');
        afterName = true;
        return this;
    }

    public JsonWriter value(String value) {
        separator();
        if (value == null) {
            out.append("null");
        } else {
            appendString(value);
        }
        return this;
    }

    public JsonWriter value(LocalDate value) {
        return value(value == null ? null : value.toString());
    }

    public JsonWriter value(boolean value) {
        separator();
        out.append(value);
        return this;
    }

    public JsonWriter value(long value) {
        separator();
        out.append(value);
        return this;
    }

    /** Fixed-decimal number with trailing zeros trimmed; null for NaN/Infinity. */
    public JsonWriter value(double value, int decimals) {
        separator();
        appendNumber(value, decimals);
        return this;
    }

    public JsonWriter value(Double value, int decimals) {
        if (value == null) {
            separator();
            out.append("null");
            return this;
        }
        return value(value.doubleValue(), decimals);
    }

    public JsonWriter nullValue() {
        separator();
        out.append("null");
        return this;
    }

    /** Append pre-serialized JSON (e.g. a nested document) as the next value. */
    public JsonWriter raw(String json) {
        separator();
        out.append(json);
        return this;
    }

    public JsonWriter field(String name, String value) {
        return name(name).value(value);
    }

    public JsonWriter field(String name, LocalDate value) {
        return name(name).value(value);
    }

    public JsonWriter field(String name, boolean value) {
        return name(name).value(value);
    }

    public JsonWriter field(String name, long value) {
        return name(name).value(value);
    }

    public JsonWriter field(String name, double value, int decimals) {
        return name(name).value(value, decimals);
    }

    public JsonWriter field(String name, Double value, int decimals) {
        return name(name).value(value, decimals);
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void separator() {
        if (afterName) {
            afterName = false;
            return;
        }
        if (!firstInScope.isEmpty()) {
            if (firstInScope.peek()) {
                firstInScope.pop();
                firstInScope.push(false);
            } else {
                out.append(',');
            }
        }
    }

    private void appendNumber(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append("null");
            return;
        }
        String s = String.format(Locale.US, "%." + decimals + "f", value);
        if (s.indexOf('.') >= 0) {
            int end = s.length();
            while (s.charAt(end - 1) == '0') {
                end--;
            }
            if (s.charAt(end - 1) == '.') {
                end--;
            }
            s = s.substring(0, end);
        }
        if (s.equals("-0")) {
            s = "0";
        }
        out.append(s);
    }

    private void appendString(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '<':
                    out.append("\\u003c"); // never let "</script>" close the embedding element
                    break;
                case '>':
                    out.append("\\u003e");
                    break;
                case '&':
                    out.append("\\u0026");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
