package com.alihelal.core;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** File names, and sizes, speeds and times in words (Arabic or English, see I18n). */
public final class Text {
    private Text() {}

    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

    /** Turns a video title into a safe file name (without extension). */
    public static String safeFileBase(String title, int maxRunes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < title.length(); ) {
            int r = title.codePointAt(i);
            i += Character.charCount(r);
            if (r == '/' || r == '\\' || r == '|' || r == ':') {
                b.append(" - ");
            } else if (r == '"') {
                b.append('\'');
            } else if (r == '<' || r == '>' || r == '?' || r == '*') {
                b.append(' ');
            } else if (r < 0x20 || r == 0x7f) {
                b.append(' ');
            } else if ((r >= 0x200B && r <= 0x200F) || (r >= 0x202A && r <= 0x202E) || (r >= 0x2066 && r <= 0x2069) || r == 0xFEFF) {
                // Invisible direction marks and zero-width characters confuse file names: drop.
            } else if (Str.isSpace(r)) {
                b.append(' ');
            } else {
                b.appendCodePoint(r);
            }
        }
        String s = Str.squash(b.toString());
        while (s.contains("- -")) s = s.replace("- -", "-");
        if (maxRunes > 0 && Str.runeLen(s) > maxRunes) s = Str.trimSpace(Str.runePrefix(s, maxRunes));
        s = Str.trim(s, " .-");
        if (s.length() == 0) s = I18n.t("فيديو", "video");
        int dot = s.indexOf('.');
        String first = dot >= 0 ? s.substring(0, dot) : s;
        if (RESERVED.contains(Str.upper(first))) s = "_" + s;
        return s;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Cuts s (at a character boundary) so it takes at most maxBytes in UTF-8. */
    public static String limitBytes(String s, int maxBytes) {
        if (s.getBytes(UTF8).length <= maxBytes) return s;
        int n = Str.runeLen(s);
        while (n > 0) {
            String t = Str.runePrefix(s, n);
            if (t.getBytes(UTF8).length <= maxBytes) return Str.trim(t, " .-");
            n--;
        }
        return "";
    }

    /** dir/base.ext, or dir/base (2).ext ... if that file already exists. */
    public static File uniquePath(File dir, String base, String ext) {
        File p = new File(dir, base + "." + ext);
        if (!p.exists()) return p;
        for (int i = 2; i < 1000; i++) {
            p = new File(dir, base + " (" + i + ")." + ext);
            if (!p.exists()) return p;
        }
        return p;
    }

    /** Makes literal text safe inside a yt-dlp output template. */
    public static String escapeOutputTemplate(String s) {
        return s.replace("%", "%%");
    }

    // ---------------------------------------------------------------------------------
    // Numbers
    // ---------------------------------------------------------------------------------

    private static final double KIB = 1024.0, MIB = 1024.0 * 1024, GIB = 1024.0 * 1024 * 1024;

    /** strconv.FormatFloat(v, 'f', decimals) without trailing zeros (Go's rounding). */
    static String trimFloat(double v, int decimals) {
        String s = new BigDecimal(v).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
        if (s.indexOf('.') >= 0) {
            s = Str.trimRight(Str.trimRight(s, "0"), ".");
        }
        if (s.equals("-0")) s = "0";
        return s;
    }

    /** Go's math.Round: half away from zero. */
    static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return v < 0 ? -Math.floor(-v + 0.5) : Math.floor(v + 0.5);
    }

    /** "850 كيلوبايت" / "850 KB", "6.2 ميجابايت" / "6.2 MB", "85 ميجابايت", "1.4 جيجابايت". */
    public static String humanSize(long b) {
        double f = b;
        if (b <= 0) return "";
        if (f < MIB) return trimFloat(Math.max(1, round(f / KIB)), 0) + I18n.t(" كيلوبايت", " KB");
        if (f < 10 * MIB) return trimFloat(f / MIB, 1) + I18n.t(" ميجابايت", " MB");
        if (f < 1000 * MIB) return trimFloat(round(f / MIB), 0) + I18n.t(" ميجابايت", " MB");
        return trimFloat(f / GIB, 1) + I18n.t(" جيجابايت", " GB");
    }

    /** "2.3 ميجابايت/ث" / "2.3 MB/s". */
    public static String humanSpeed(double bytesPerSec) {
        if (bytesPerSec <= 0 || Double.isNaN(bytesPerSec) || Double.isInfinite(bytesPerSec)) return "";
        if (bytesPerSec < MIB) return trimFloat(Math.max(1, round(bytesPerSec / KIB)), 0) + I18n.t(" كيلوبايت/ث", " KB/s");
        return trimFloat(bytesPerSec / MIB, 1) + I18n.t(" ميجابايت/ث", " MB/s");
    }

    /** "4:05", "12:34", "1:02:03". */
    public static String humanDuration(double sec) {
        if (sec <= 0 || Double.isNaN(sec)) return "";
        long s = (long) round(sec);
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return Str.fmt("%d:%02d:%02d", h, m, ss);
        return Str.fmt("%d:%02d", m, ss);
    }

    /** A moment of a video: "0:00", "1:05", "1:02:03" (to the second). */
    public static String clock(double sec) {
        if (sec <= 0 || Double.isNaN(sec)) return "0:00";
        return humanDuration(sec);
    }

    /** Seconds as yt-dlp takes them ("83.4", "90"). */
    static String seconds(double sec) {
        double r = Math.round(sec * 1000) / 1000.0;
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    /** Minutes after "نحو": دقيقة، دقيقتين، 3 دقائق، 15 دقيقة. */
    static String minutesAr(int n) {
        if (n == 1) return "دقيقة";
        if (n == 2) return "دقيقتين";
        if (n >= 3 && n <= 10) return n + " دقائق";
        return n + " دقيقة";
    }

    static String hoursAr(int n) {
        if (n == 1) return "ساعة";
        if (n == 2) return "ساعتين";
        if (n >= 3 && n <= 10) return n + " ساعات";
        return n + " ساعة";
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /**
     * "متبقٍ بضع ثوانٍ", "متبقٍ أقل من دقيقة", "متبقٍ نحو 5 دقائق", "متبقٍ نحو ساعة و20 دقيقة";
     * in English "A few seconds left", "About 5 minutes left", "About 1 hour 20 min left".
     */
    public static String humanETA(double sec) {
        if (sec < 0 || Double.isNaN(sec) || Double.isInfinite(sec)) return "";
        boolean en = I18n.english();
        if (sec < 10) return en ? "A few seconds left" : "متبقٍ بضع ثوانٍ";
        if (sec < 60) return en ? "Less than a minute left" : "متبقٍ أقل من دقيقة";
        if (sec < 3600) {
            int m = (int) round(sec / 60);
            return en ? "About " + plural(m, "minute", "minutes") + " left" : "متبقٍ نحو " + minutesAr(m);
        }
        int total = (int) round(sec / 60);
        int h = total / 60, m = total % 60;
        m = (int) (round(m / 5.0) * 5);
        if (m == 60) {
            h++;
            m = 0;
        }
        if (en) return "About " + plural(h, "hour", "hours") + (m > 0 ? " " + m + " min" : "") + " left";
        String s = "متبقٍ نحو " + hoursAr(h);
        if (m > 0) s += " و" + minutesAr(m);
        return s;
    }
}
