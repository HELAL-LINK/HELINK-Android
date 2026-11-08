package com.alihelal.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the app remembers between runs, in a small JSON file (written to a
 * temporary file and renamed, so a crash never leaves a half-written file).
 */
public final class Prefs {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final File file;
    private final Map<String, Object> m = new LinkedHashMap<String, Object>();

    public Prefs(File file) {
        this.file = file;
        try {
            byte[] b = readAll(file);
            Map<String, Object> o = J.asObj(Json.parse(new String(b, UTF8)));
            if (o != null) m.putAll(o);
        } catch (Exception ignored) {
            // first run or damaged file: start fresh
        }
    }

    static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return Http.readUpTo(in, 1 << 20);
        } finally {
            in.close();
        }
    }

    public synchronized String getString(String k) {
        return J.str(m, k);
    }

    public synchronized boolean getBool(String k) {
        return J.bool(m, k);
    }

    public synchronized long getLong(String k) {
        return (long) J.num(m, k);
    }

    public synchronized void put(String k, Object v) {
        if (v == null) m.remove(k);
        else m.put(k, v);
        save();
    }

    /** Several values at once: put("a", 1, "b", "x"). */
    public synchronized void putAll(Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) m.remove((String) kv[i]);
            else m.put((String) kv[i], kv[i + 1]);
        }
        save();
    }

    private void save() {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File tmp = new File(file.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(Json.write(m).getBytes(UTF8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (IOException ignored) {
            // settings are a convenience; never fail because of them
        }
    }
}
