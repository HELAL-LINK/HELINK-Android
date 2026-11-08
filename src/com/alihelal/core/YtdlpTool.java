package com.alihelal.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps yt-dlp unpacked and up to date. Sites change often, and yt-dlp's authors
 * recommend the nightly builds for regular users, so the app checks for a new one every
 * few days and right away when a site stops working.
 *
 * Each version lives in its own folder; the old one is removed only when no yt-dlp is
 * running, so an update never disturbs a download.
 */
public final class YtdlpTool {
    // Not final only so the tests can point them at a local server.
    static String LATEST = "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest";
    static String ASSET = "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/%s/%s";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Host host;
    private final Prefs prefs;
    private final File root;
    private int running;
    private boolean installing;

    public YtdlpTool(Host host, Prefs prefs) {
        this.host = host;
        this.prefs = prefs;
        this.root = new File(host.dataDir(), "ytdlp");
    }

    static boolean valid(File d) {
        return d != null && new File(d, "__main__.py").isFile() && new File(d, "yt_dlp/version.py").isFile();
    }

    /** The folder of the yt-dlp in use, or null when none is installed. */
    public File dir() {
        String d = prefs.getString("ytdlpDir");
        if (d.length() == 0) return null;
        File f = new File(root, d);
        return valid(f) ? f : null;
    }

    public String version() {
        return prefs.getString("ytdlpVersion");
    }

    public boolean ready() {
        return dir() != null;
    }

    /** Wraps every yt-dlp run: returns the folder to run from. */
    public synchronized File acquire() {
        running++;
        return dir();
    }

    public void release() {
        synchronized (this) {
            running--;
            if (running > 0 || installing) return;
        }
        cleanup();
    }

    /** Removes old versions and half-unpacked folders, if no yt-dlp is running. */
    public void cleanup() {
        File keep;
        synchronized (this) {
            if (running > 0 || installing) return;
            keep = dir();
        }
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (keep != null && k.getName().equals(keep.getName())) continue;
            Zip.deleteTree(k);
        }
    }

    private static final Pattern VERSION = Pattern.compile("__version__\\s*=\\s*'([^']+)'");

    static String readVersion(File dir) throws IOException {
        String s = new String(Prefs.readAll(new File(dir, "yt_dlp/version.py")), UTF8);
        Matcher m = VERSION.matcher(s);
        if (!m.find()) throw new IOException("no version in yt-dlp");
        return m.group(1);
    }

    /** Compares versions like 2026.10.10.233141 (true when a is newer than b). */
    public static boolean isNewer(String a, String b) {
        long[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static long[] parts(String v) {
        if (v == null || v.length() == 0) return new long[0];
        String[] s = v.split("\\.");
        long[] out = new long[s.length];
        for (int i = 0; i < s.length; i++) {
            try {
                out[i] = Long.parseLong(s[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Unpacks a yt-dlp zipapp into a new folder (not used yet). Call with installing set. */
    private File unpack(File zip, Cancel cancel, Http.ByteProgress progress) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
        File tmp = new File(root, ".new-" + Long.toString(System.nanoTime(), 36));
        try {
            Zip.extract(zip, tmp, new Zip.Filter() {
                @Override
                public String map(String name) {
                    if (name.equals("__main__.py") || name.startsWith("yt_dlp/") || name.startsWith("yt_dlp_ejs/")) {
                        return name;
                    }
                    return null;
                }
            }, host, progress, cancel);
            if (!valid(tmp)) throw new IOException("not a yt-dlp file");
            String ver = readVersion(tmp);
            File fin = new File(root, ver + "-" + Long.toString(System.currentTimeMillis(), 36));
            if (!tmp.renameTo(fin)) throw new IOException("cannot rename " + tmp);
            return fin;
        } finally {
            if (tmp.exists()) Zip.deleteTree(tmp);
        }
    }

    private synchronized boolean beginInstall() {
        if (installing) return false;
        installing = true;
        return true;
    }

    private void endInstall() {
        synchronized (this) {
            installing = false;
        }
        cleanup();
    }

    /** Unpacks a yt-dlp zipapp and makes it the one in use. Returns its version. */
    public String install(File zip, Cancel cancel, Http.ByteProgress progress) throws IOException {
        // Wait for a background update to finish first.
        while (!beginInstall()) {
            if (cancel != null && cancel.isCancelled()) throw new java.io.InterruptedIOException("cancelled");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted");
            }
        }
        try {
            File d = unpack(zip, cancel, progress);
            String ver = readVersion(d);
            prefs.putAll("ytdlpDir", d.getName(), "ytdlpVersion", ver);
            return ver;
        } finally {
            endInstall();
        }
    }

    /** Unpacks the copy of yt-dlp that ships inside the app. */
    public String installBundled(Cancel cancel, Http.ByteProgress progress) throws IOException {
        File tmp = File.createTempFile("ytdlp", ".zip", host.cacheDir());
        try {
            InputStream in = host.openBundledYtdlp();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
                in.close();
            }
            return install(tmp, cancel, progress);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Whether the copy inside the app is newer than the one in use (after an app update). */
    public boolean bundledIsNewer() {
        return !ready() || isNewer(host.bundledYtdlpVersion(), version());
    }

    /** Asks GitHub which nightly build is the latest ("2026.10.12.233056"), or null. */
    String latestTag(Cancel cancel) throws IOException {
        Http.Response r = Http.get(LATEST, null, 30000, 64 << 10, false, null, cancel);
        String loc = r.location;
        int i = loc.indexOf("/releases/tag/");
        if (i >= 0) {
            String tag = Str.trim(loc.substring(i + "/releases/tag/".length()), "/");
            if (tag.length() > 0 && !Str.containsAny(tag, "?#/")) return tag;
        }
        throw new IOException("no release tag (status " + r.code + ")");
    }

    /**
     * Installs a newer nightly build if there is one and the last check is older than
     * minAgeMs. Returns true when yt-dlp changed. The new version must start correctly,
     * otherwise the previous one stays in use.
     */
    public boolean update(Cancel cancel, long minAgeMs) throws IOException {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("ytdlpChecked") < minAgeMs) return false;
        if (!beginInstall()) return false;
        File tmp = null, fresh = null;
        boolean activated = false;
        try {
            String tag = latestTag(cancel);
            if (!isNewer(tag, version())) {
                prefs.put("ytdlpChecked", now);
                return false;
            }
            Http.Response sums = Http.get(Str.fmt(ASSET, tag, "SHA2-256SUMS"), null, 30000, 256 << 10, true, null, cancel);
            if (sums.code != 200) throw new Http.StatusException(sums.code);
            String want = null;
            for (String line : new String(sums.body, UTF8).split("\n")) {
                List<String> f = Str.fields(line);
                if (f.size() == 2 && f.get(1).equals("yt-dlp")) want = Str.lower(f.get(0));
            }
            if (want == null) throw new IOException("no checksum for yt-dlp");
            tmp = File.createTempFile("yt-dlp-" + tag, ".zip", host.cacheDir());
            String got = Http.download(Str.fmt(ASSET, tag, "yt-dlp"), tmp, 60000, 60L << 20, null, cancel);
            if (!got.equals(want)) throw new IOException("checksum mismatch for yt-dlp " + tag);
            fresh = unpack(tmp, cancel, null);
            String ver = readVersion(fresh);
            List<String> cmd = new ArrayList<String>(host.ytdlpCommand(fresh));
            cmd.add("--version");
            String out = Runner.quick(host, cmd, host.toolEnv(), 180000);
            if (out == null || !out.contains(ver)) {
                // The new build does not start here: keep the one that works.
                host.log("new yt-dlp " + ver + " failed to start; keeping " + version());
                prefs.put("ytdlpChecked", now);
                return false;
            }
            prefs.putAll("ytdlpDir", fresh.getName(), "ytdlpVersion", ver, "ytdlpChecked", now);
            activated = true;
            host.log("yt-dlp updated to " + ver);
            return true;
        } finally {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (fresh != null && !activated) Zip.deleteTree(fresh);
            endInstall();
        }
    }
}
