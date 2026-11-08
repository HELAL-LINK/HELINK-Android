package com.alihelal.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The last lines of the app's own log, for the "details" a helper can read. */
final class Logs {
    private Logs() {}

    private static final List<String> LINES = new ArrayList<String>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    /** Writes to the system log and keeps the line. */
    static void note(String line) {
        android.util.Log.i(AndroidHost.TAG, line);
        add(line);
    }

    static synchronized void add(String line) {
        if (line.length() > 1500) line = line.substring(0, 1500) + "…";
        LINES.add(TIME.format(new Date()) + " " + line);
        if (LINES.size() > 120) LINES.subList(0, LINES.size() - 120).clear();
    }

    static synchronized String recent(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, LINES.size() - n); i < LINES.size(); i++) b.append(LINES.get(i)).append('\n');
        return b.toString();
    }
}
