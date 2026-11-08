package com.alihelal.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer. It behaves the same on the phone and in the desktop
 * tests (Android's built-in org.json has quirks, e.g. it turns JSON null into "null"),
 * and it remembers where each object came from in the text, so one video of a post can be
 * handed back to yt-dlp exactly as yt-dlp printed it.
 */
public final class Json {
    private Json() {}

    /** JSON null inside objects and arrays (a missing key is Java null). */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** A JSON object; raw() returns its original text. */
    public static final class Obj extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        String src;
        int start, end;

        public String raw() {
            return src == null ? write(this) : src.substring(start, end);
        }
    }

    public static final class ParseError extends Exception {
        private static final long serialVersionUID = 1L;

        ParseError(String msg, int pos) {
            super(msg + " at " + pos);
        }
    }

    public static Object parse(String s) throws ParseError {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new ParseError("trailing data", p.i);
        return v;
    }

    /** Parses a JSON object, or returns null if the text is not one. */
    public static Obj parseObject(String s) {
        try {
            Object v = parse(s);
            return v instanceof Obj ? (Obj) v : null;
        } catch (ParseError e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value(int depth) throws ParseError {
            if (depth > 400) throw new ParseError("too deep", i);
            if (i >= s.length()) throw new ParseError("unexpected end", i);
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return word("true", Boolean.TRUE);
                case 'f':
                    return word("false", Boolean.FALSE);
                case 'n':
                    return word("null", NULL);
                case 'N':
                    return word("NaN", Double.NaN); // Python's json writes these
                case 'I':
                    return word("Infinity", Double.POSITIVE_INFINITY);
                default:
                    if (c == '-' && s.startsWith("-Infinity", i)) return word("-Infinity", Double.NEGATIVE_INFINITY);
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new ParseError("unexpected character", i);
            }
        }

        Object word(String w, Object v) throws ParseError {
            if (!s.startsWith(w, i)) throw new ParseError("bad literal", i);
            i += w.length();
            return v;
        }

        Obj object(int depth) throws ParseError {
            Obj o = new Obj();
            o.src = s;
            o.start = i;
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                o.end = i;
                return o;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new ParseError("expected key", i);
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new ParseError("expected ':'", i);
                i++;
                ws();
                Object v = value(depth + 1);
                o.put(k, v);
                ws();
                if (i >= s.length()) throw new ParseError("unexpected end", i);
                char c = s.charAt(i++);
                if (c == ',') continue;
                if (c == '}') break;
                throw new ParseError("expected ',' or '}'", i - 1);
            }
            o.end = i;
            return o;
        }

        List<Object> array(int depth) throws ParseError {
            List<Object> a = new ArrayList<Object>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                ws();
                a.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw new ParseError("unexpected end", i);
                char c = s.charAt(i++);
                if (c == ',') continue;
                if (c == ']') break;
                throw new ParseError("expected ',' or ']'", i - 1);
            }
            return a;
        }

        String string() throws ParseError {
            i++; // opening quote
            StringBuilder b = null;
            int start = i;
            while (true) {
                if (i >= s.length()) throw new ParseError("unterminated string", start);
                char c = s.charAt(i);
                if (c == '"') {
                    String out = b == null ? s.substring(start, i) : b.append(s, start, i).toString();
                    i++;
                    return out;
                }
                if (c == '\\') {
                    if (b == null) b = new StringBuilder();
                    b.append(s, start, i);
                    i++;
                    if (i >= s.length()) throw new ParseError("bad escape", i);
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new ParseError("bad unicode escape", i);
                            try {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw new ParseError("bad unicode escape", i);
                            }
                            i += 4;
                            break;
                        default:
                            throw new ParseError("bad escape", i - 1);
                    }
                    start = i;
                    continue;
                }
                i++;
            }
        }

        Object number() throws ParseError {
            int start = i;
            if (s.charAt(i) == '-') i++;
            boolean frac = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    frac = true;
                    i++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, i);
            if (!frac) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException e) {
                    // too big for a long: fall through to a double
                }
            }
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw new ParseError("bad number", start);
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------

    /** Objects that know how to turn themselves into JSON values (maps, lists, strings…). */
    public interface Value {
        Object toJson();
    }

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static void write(StringBuilder b, Object v) {
        if (v == null || v == NULL) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Number) {
            b.append(numberText((Number) v));
        } else if (v instanceof Value) {
            write(b, ((Value) v).toJson());
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            Iterator<Object> it = ((Iterable<Object>) v).iterator();
            boolean first = true;
            while (it.hasNext()) {
                if (!first) b.append(',');
                first = false;
                write(b, it.next());
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            b.append('[');
            Object[] a = (Object[]) v;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) b.append(',');
                write(b, a[i]);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    public static String numberText(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return n.toString();
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        quote(b, s);
        return b.toString();
    }

    public static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    // U+2028/2029 break JavaScript string literals in older engines.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == 0x7f) {
                        b.append(Str.fmt("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    /** A map literal helper: obj("a", 1, "b", "x"). Null values are skipped (like omitempty). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
