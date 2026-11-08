package com.alihelal.core;

/**
 * The language the app speaks: Modern Standard Arabic (the default) or English. Texts are
 * produced when they are shown, so switching the language changes everything at once,
 * including downloads already in the list.
 */
public final class I18n {
    private I18n() {}

    public static final String AR = "ar";
    public static final String EN = "en";

    private static volatile boolean english;

    public static String lang() {
        return english ? EN : AR;
    }

    /** "en" switches to English; anything else is Arabic. */
    public static void set(String lang) {
        english = EN.equals(lang);
    }

    public static boolean english() {
        return english;
    }

    /** The text in the current language. */
    public static String t(String ar, String en) {
        return english ? en : ar;
    }
}
