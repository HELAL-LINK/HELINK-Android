package com.alihelal.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lenient readers for yt-dlp's JSON. It comes from many different sites, and a field that
 * is a number on one site can be a string on another: these accept whatever arrives
 * instead of failing the whole video over one odd field (same rules as the Go version).
 */
public final class J {
    private J() {}

    private static Object get(Map<String, Object> m, String k) {
        return m == null ? null : m.get(k);
    }

    public static boolean isNull(Object v) {
        return v == null || v == Json.NULL;
    }

    /** A string; null becomes "" and numbers or booleans become their text. */
    public static String str(Map<String, Object> m, String k) {
        Object v = get(m, k);
        if (isNull(v)) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? "true" : "false";
        if (v instanceof Number) return Json.numberText((Number) v);
        return "";
    }

    /** A number; null, empty or unreadable values become 0. */
    public static double num(Map<String, Object> m, String k) {
        Object v = get(m, k);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d;
        }
        if (v instanceof String) {
            try {
                double d = Double.parseDouble(Str.trimSpace((String) v));
                return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** A number that may be absent (null when missing, null or unreadable). */
    public static Double optNum(Map<String, Object> m, String k) {
        Object v = get(m, k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return null;
    }

    /** True only for JSON true, the number 1 or the strings "true"/"1". */
    public static boolean bool(Map<String, Object> m, String k) {
        Object v = get(m, k);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Long) return ((Long) v).longValue() == 1;
        if (v instanceof String) return "true".equals(v) || "1".equals(v);
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = get(m, k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String k) {
        Object v = get(m, k);
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    public static Object raw(Map<String, Object> m, String k) {
        return get(m, k);
    }
}
