package com.alihelal.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The downloads list on disk (downloads.json in the app's private folder), plus the
 * pictures shown next to each entry (one small file each, so the list stays light).
 * Written to a temporary file and renamed, so a crash never leaves a half-written list.
 */
final class DownloadStore {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final File file;
    private final File thumbs;
    private final Host host;

    DownloadStore(File file, File thumbs, Host host) {
        this.file = file;
        this.thumbs = thumbs;
        this.host = host;
    }

    List<DownloadTask> load() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        if (!file.isFile()) return out;
        try {
            Map<String, Object> root = J.asObj(Json.parse(new String(Http.readAll(new java.io.FileInputStream(file)), UTF8)));
            List<Object> list = root == null ? null : J.arr(root, "tasks");
            if (list == null) return out;
            for (Object o : list) {
                Map<String, Object> m = J.asObj(o);
                if (m == null) continue;
                DownloadTask t = DownloadTask.fromStore(m);
                if (!t.valid()) continue;
                t.thumb = readThumb(t.id);
                out.add(t);
            }
        } catch (Exception e) {
            host.log("downloads list unreadable, starting fresh: " + e);
        }
        return out;
    }

    /** Serialized under the engine's lock (fast); written outside it. */
    static String serialize(List<DownloadTask> tasks) {
        List<Object> list = new ArrayList<Object>();
        for (DownloadTask t : tasks) list.add(t.toStore());
        Map<String, Object> root = Json.obj("version", 1, "tasks", list);
        return Json.write(root);
    }

    private final Object writeLock = new Object();
    private long written; // sequence of the newest text on disk

    /** Writes the list; an older text that arrives late never replaces a newer one. */
    void write(String text, long seq) {
        synchronized (writeLock) {
            if (seq < written) return;
            written = seq;
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File tmp = new File(file.getPath() + ".tmp");
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(text.getBytes(UTF8));
                    out.getFD().sync();
                } finally {
                    out.close();
                }
                if (!tmp.renameTo(file)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    if (!tmp.renameTo(file)) host.log("cannot save the downloads list");
                }
            } catch (IOException e) {
                host.log("saving the downloads list: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ pictures

    private File thumbFile(String id) {
        return new File(thumbs, id + ".txt");
    }

    void writeThumb(String id, String thumb) {
        if (thumb == null || thumb.length() == 0 || thumb.length() > (6 << 20)) return;
        //noinspection ResultOfMethodCallIgnored
        thumbs.mkdirs();
        try {
            Engine.writeText(thumbFile(id), thumb);
        } catch (IOException e) {
            host.log("picture of " + id + ": " + e);
        }
    }

    String readThumb(String id) {
        File f = thumbFile(id);
        if (!f.isFile()) return "";
        try {
            return new String(Http.readAll(new java.io.FileInputStream(f)), UTF8);
        } catch (IOException e) {
            return "";
        }
    }

    void deleteThumb(String id) {
        //noinspection ResultOfMethodCallIgnored
        thumbFile(id).delete();
    }

    /** Removes pictures of entries that are gone. */
    void pruneThumbs(java.util.Set<String> ids) {
        File[] fs = thumbs.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".txt") && !ids.contains(n.substring(0, n.length() - 4))) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
