package com.alihelal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * String helpers with the same behaviour as Go's strings/unicode packages, so the rules
 * ported from the Windows version (written in Go) give exactly the same results here.
 * Java's own trim()/toLowerCase() differ in small but visible ways (Arabic text, emoji,
 * Turkish locale), so they are not used for text that reaches the user.
 */
public final class Str {
    private Str() {}

    /** Go's unicode.IsSpace. */
    public static boolean isSpace(int r) {
        if (r <= 0xFF) {
            return r == ' ' || (r >= 0x09 && r <= 0x0D) || r == 0x85 || r == 0xA0;
        }
        return r == 0x1680 || (r >= 0x2000 && r <= 0x200A) || r == 0x2028 || r == 0x2029
                || r == 0x202F || r == 0x205F || r == 0x3000;
    }

    /** Go's strings.TrimSpace. */
    public static String trimSpace(String s) {
        int start = 0, end = s.length();
        while (start < end) {
            int c = s.codePointAt(start);
            if (!isSpace(c)) break;
            start += Character.charCount(c);
        }
        while (end > start) {
            int c = s.codePointBefore(end);
            if (!isSpace(c)) break;
            end -= Character.charCount(c);
        }
        return s.substring(start, end);
    }

    /** Go's strings.Fields. */
    public static List<String> fields(String s) {
        List<String> out = new ArrayList<String>();
        int i = 0, n = s.length(), start = -1;
        while (i < n) {
            int c = s.codePointAt(i);
            if (isSpace(c)) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
            i += Character.charCount(c);
        }
        if (start >= 0) out.add(s.substring(start));
        return out;
    }

    /** strings.Join(strings.Fields(s), " "): collapses all runs of white space. */
    public static String squash(String s) {
        return join(" ", fields(s));
    }

    public static String join(String sep, List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** len([]rune(s)) */
    public static int runeLen(String s) {
        return s.codePointCount(0, s.length());
    }

    /** string([]rune(s)[:n]) */
    public static String runePrefix(String s, int n) {
        if (n <= 0) return "";
        if (runeLen(s) <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    private static boolean inCutset(int c, String cutset) {
        return cutset.indexOf(c) >= 0;
    }

    /** Go's strings.Trim(s, cutset). */
    public static String trim(String s, String cutset) {
        return trimRight(trimLeft(s, cutset), cutset);
    }

    public static String trimLeft(String s, String cutset) {
        int i = 0;
        while (i < s.length()) {
            int c = s.codePointAt(i);
            if (!inCutset(c, cutset)) break;
            i += Character.charCount(c);
        }
        return s.substring(i);
    }

    public static String trimRight(String s, String cutset) {
        int end = s.length();
        while (end > 0) {
            int c = s.codePointBefore(end);
            if (!inCutset(c, cutset)) break;
            end -= Character.charCount(c);
        }
        return s.substring(0, end);
    }

    public static String trimPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    public static String trimSuffix(String s, String suffix) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    public static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** Go's strings.IndexAny for ASCII sets. */
    public static int indexAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    public static boolean containsAny(String s, String chars) {
        return indexAny(s, chars) >= 0;
    }

    public static boolean equalFold(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /** The last n lines of s, trimmed. */
    public static String tailLines(String s, int n) {
        String t = trimSpace(s);
        String[] lines = t.split("\n", -1);
        if (lines.length <= n) return t;
        StringBuilder b = new StringBuilder();
        for (int i = lines.length - n; i < lines.length; i++) {
            if (b.length() > 0) b.append('\n');
            b.append(lines[i]);
        }
        return b.toString();
    }

    public static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    /** Formats like String.format but always with Western digits, whatever the phone's language. */
    public static String fmt(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
