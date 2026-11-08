package com.alihelal.app;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.alihelal.core.Cancel;
import com.alihelal.core.Host;
import com.alihelal.core.Http;
import com.alihelal.core.Zip;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The phone side of the engine: where files live, how Python/ffmpeg are started, and how
 * finished files reach the Download/HELINK folder.
 *
 * Python, ffmpeg and the JavaScript helper ship as "lib*.so" files so Android installs
 * them as runnable programs (apps may not run programs they write themselves). Their
 * libraries come zipped and are unpacked once into the app's private folder.
 */
final class AndroidHost implements Host {
    static final String TAG = "HELINK";
    static final String FOLDER = "HELINK";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final File data, cache, packages, home;
    private final String libDir;
    private final SharedPreferences sp;

    AndroidHost(Context c) {
        ctx = c.getApplicationContext();
        File base = ctx.getNoBackupFilesDir();
        data = new File(base, "engine");
        packages = new File(base, "packages");
        home = new File(base, "home");
        cache = ctx.getCacheDir();
        libDir = ctx.getApplicationInfo().nativeLibraryDir;
        sp = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE);
        for (File d : new File[]{data, packages, home}) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
    }

    @Override
    public File dataDir() {
        return data;
    }

    @Override
    public File cacheDir() {
        return cache;
    }

    private File python() {
        return new File(packages, "python");
    }

    private File ffmpeg() {
        return new File(packages, "ffmpeg");
    }

    // ------------------------------------------------------------------ programs

    /**
     * Starts the program through a tiny shell line that prints its process number first and
     * puts it in a process group of its own, so "stop" can end it and everything it started
     * (ffmpeg, the JavaScript helper) at once.
     */
    @Override
    public Proc start(List<String> cmd, Map<String, String> env) throws IOException {
        List<String> full = new ArrayList<String>();
        full.add("/system/bin/sh");
        full.add("-c");
        full.add("echo $$; if [ -x /system/bin/setsid ]; then exec /system/bin/setsid \"$@\"; else exec \"$@\"; fi");
        full.add("helal");
        full.addAll(cmd);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.environment().putAll(env);
        pb.directory(cache);
        final Process p = pb.start();
        final InputStream out = p.getInputStream();
        // The first line is the process number.
        StringBuilder num = new StringBuilder();
        int ch;
        while ((ch = out.read()) != -1 && ch != '\n') {
            if (num.length() < 16) num.append((char) ch);
        }
        int pid = -1;
        try {
            pid = Integer.parseInt(num.toString().trim());
        } catch (NumberFormatException ignored) {
            // the shell failed: the reader threads will see the end of the output
        }
        final int fpid = pid;
        return new Proc() {
            @Override
            public OutputStream stdin() {
                return p.getOutputStream();
            }

            @Override
            public InputStream stdout() {
                return out;
            }

            @Override
            public InputStream stderr() {
                return p.getErrorStream();
            }

            @Override
            public int waitFor() throws InterruptedException {
                return p.waitFor();
            }

            @Override
            public void kill() {
                killTree(fpid);
                p.destroy();
            }
        };
    }

    /** Ends pid, its process group and all its descendants. */
    static void killTree(int pid) {
        if (pid <= 1) return;
        Set<Integer> all = descendants(pid);
        all.add(pid);
        for (int q : all) signal(q, OsConstants.SIGSTOP); // nobody starts new children meanwhile
        all.addAll(descendants(pid));
        signal(-pid, OsConstants.SIGKILL); // the whole group, when setsid worked
        for (int q : all) signal(q, OsConstants.SIGKILL);
    }

    private static void signal(int pid, int sig) {
        try {
            Os.kill(pid, sig);
        } catch (ErrnoException ignored) {
            // already gone
        }
    }

    /** Child processes found through /proc (only our own are visible to the app). */
    static Set<Integer> descendants(int root) {
        Map<Integer, List<Integer>> kids = new HashMap<Integer, List<Integer>>();
        File[] procs = new File("/proc").listFiles();
        if (procs != null) {
            for (File f : procs) {
                String n = f.getName();
                if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
                try {
                    int pid = Integer.parseInt(n);
                    int ppid = parentOf(pid);
                    if (ppid <= 0) continue;
                    List<Integer> l = kids.get(ppid);
                    if (l == null) {
                        l = new ArrayList<Integer>();
                        kids.put(ppid, l);
                    }
                    l.add(pid);
                } catch (NumberFormatException ignored) {
                    // not a process
                }
            }
        }
        Set<Integer> out = new HashSet<Integer>();
        List<Integer> todo = new ArrayList<Integer>();
        todo.add(root);
        while (!todo.isEmpty()) {
            int p = todo.remove(todo.size() - 1);
            List<Integer> l = kids.get(p);
            if (l == null) continue;
            for (int k : l) {
                if (out.add(k)) todo.add(k);
            }
        }
        return out;
    }

    private static int parentOf(int pid) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/" + pid + "/stat"), UTF8));
            try {
                String s = r.readLine();
                if (s == null) return -1;
                int close = s.lastIndexOf(')');
                String[] f = s.substring(close + 2).split(" ");
                return Integer.parseInt(f[1]);
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<String> ytdlpCommand(File dir) {
        return Arrays.asList(libDir + "/libpython.so", dir.getAbsolutePath());
    }

    @Override
    public List<String> pythonCommand(List<String> args) {
        List<String> c = new ArrayList<String>();
        c.add(libDir + "/libpython.so");
        c.addAll(args);
        return c;
    }

    @Override
    public List<String> ffmpegCommand(List<String> args) {
        List<String> c = new ArrayList<String>();
        c.add(libDir + "/libffmpeg.so");
        c.addAll(args);
        return c;
    }

    @Override
    public List<String> ffprobeCommand(List<String> args) {
        List<String> c = new ArrayList<String>();
        c.add(libDir + "/libffprobe.so");
        c.addAll(args);
        return c;
    }

    /** The music remover, below normal priority: the phone stays smooth while it works. */
    @Override
    public List<String> sepCommand(List<String> args) {
        List<String> c = new ArrayList<String>();
        if (new File("/system/bin/nice").canExecute()) {
            c.add("/system/bin/nice");
            c.add("-n");
            c.add("10");
        }
        c.add(libDir + "/libhelinksep.so");
        c.addAll(args);
        return c;
    }

    @Override
    public long[] memory() {
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new long[]{mi.availMem, mi.totalMem};
        } catch (RuntimeException e) {
            return new long[]{-1, -1};
        }
    }

    @Override
    public InputStream openAsset(String name) throws IOException {
        return ctx.getAssets().open(name);
    }

    @Override
    public void forgetContent(String uri) {
        try {
            ctx.getContentResolver().releasePersistableUriPermission(Uri.parse(uri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // none was kept (a shared file, or one of the app's own downloads)
        }
    }

    @Override
    public InputStream openContent(String uri) throws IOException {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) throw new java.io.FileNotFoundException(uri);
            return in;
        } catch (SecurityException e) {
            throw new java.io.FileNotFoundException("no permission to read " + uri + ": " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new java.io.FileNotFoundException(uri + ": " + e.getMessage());
        }
    }

    @Override
    public Map<String, String> toolEnv() {
        Map<String, String> e = new HashMap<String, String>();
        String py = python().getAbsolutePath(), ff = ffmpeg().getAbsolutePath();
        e.put("LD_LIBRARY_PATH", py + "/usr/lib:" + ff + "/usr/lib");
        e.put("SSL_CERT_FILE", py + "/usr/etc/tls/cert.pem");
        e.put("PYTHONHOME", py + "/usr");
        e.put("HOME", home.getAbsolutePath());
        e.put("TMPDIR", cache.getAbsolutePath());
        String path = System.getenv("PATH");
        e.put("PATH", (path == null || path.isEmpty() ? "/system/bin" : path) + ":" + libDir);
        e.put("PYTHONUTF8", "1");
        e.put("PYTHONIOENCODING", "utf-8");
        e.put("LANG", "C.UTF-8");
        return e;
    }

    @Override
    public List<String> ytdlpPlatformArgs() {
        return Arrays.asList("--js-runtimes", "quickjs:" + libDir + "/libqjs.so",
                "--ffmpeg-location", libDir + "/libffmpeg.so");
    }

    // ------------------------------------------------------------------ runtime

    /** Where Python and ffmpeg's libraries come from, and how to check them (assets/runtime.json). */
    private static final class Bundle {
        String file = "", path = "", sha256 = "";
        long size, unpacked;
    }

    private Map<String, Object> runtimeJson;

    private synchronized Map<String, Object> runtimeJson() {
        if (runtimeJson == null) {
            try {
                InputStream in = ctx.getAssets().open("runtime.json");
                try {
                    runtimeJson = com.alihelal.core.J.asObj(com.alihelal.core.Json.parse(
                            new String(Http.readAll(in), UTF8)));
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                Logs.note("runtime.json: " + e);
            }
            if (runtimeJson == null) runtimeJson = new HashMap<String, Object>();
        }
        return runtimeJson;
    }

    /** The phone's processor family, as the app was installed (64 or 32 bit ARM). */
    static String abi() {
        return android.os.Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
    }

    private Bundle bundle(String part) {
        Map<String, Object> abis = com.alihelal.core.J.obj(runtimeJson(), "abis");
        Map<String, Object> m = com.alihelal.core.J.obj(com.alihelal.core.J.obj(abis, abi()), part);
        Bundle b = new Bundle();
        if (m == null) return b;
        b.file = com.alihelal.core.J.str(m, "file");
        b.path = com.alihelal.core.J.str(m, "path");
        b.sha256 = com.alihelal.core.J.str(m, "sha256");
        b.size = (long) com.alihelal.core.J.num(m, "size");
        b.unpacked = (long) com.alihelal.core.J.num(m, "unpacked");
        return b;
    }

    private List<String> urls(Bundle b) {
        String repo = com.alihelal.core.J.str(runtimeJson(), "repo");
        String commit = com.alihelal.core.J.str(runtimeJson(), "commit");
        return Arrays.asList(
                "https://raw.githubusercontent.com/" + repo + "/" + commit + "/" + b.path,
                "https://cdn.jsdelivr.net/gh/" + repo + "@" + commit + "/" + b.path,
                "https://github.com/" + repo + "/raw/" + commit + "/" + b.path);
    }

    private File dirOf(String part) {
        return part.equals("python") ? python() : ffmpeg();
    }

    /** The bundle inside the app, for builds that ship it (none by default: it is downloaded). */
    private File shipped(Bundle b) {
        File f = new File(libDir, b.file);
        return b.size > 0 && f.isFile() && f.length() == b.size ? f : null;
    }

    @Override
    public List<String> runtimeMissing() {
        List<String> m = new ArrayList<String>();
        for (String part : new String[]{"python", "ffmpeg"}) {
            Bundle b = bundle(part);
            boolean ok = b.sha256.length() > 0 && new File(dirOf(part), "usr/lib").isDirectory()
                    && b.sha256.equals(sp.getString(part + "_sha256", ""));
            if (!ok) m.add(part);
        }
        return m;
    }

    @Override
    public long runtimeSize(String part) {
        Bundle b = bundle(part);
        return b.unpacked > 0 ? b.unpacked : (part.equals("python") ? 42_000_000L : 78_000_000L);
    }

    @Override
    public long runtimeDownloadSize(String part) {
        Bundle b = bundle(part);
        return shipped(b) != null ? 0 : b.size;
    }

    @Override
    public void installRuntime(String part, Cancel cancel, StepProgress progress) throws IOException {
        Bundle b = bundle(part);
        if (b.sha256.length() == 0) throw new IOException("this phone's processor (" + abi() + ") is not supported");
        File dir = dirOf(part);
        sp.edit().remove(part + "_sha256").commit();
        File zip = shipped(b);
        File downloaded = null;
        if (zip == null) {
            progress.phase("download");
            File dl = new File(cache, "runtime");
            //noinspection ResultOfMethodCallIgnored
            dl.mkdirs();
            downloaded = new File(dl, abi() + "-" + part + ".zip");
            // A part downloaded earlier is continued (the ".part" file), and checked before use.
            Http.fetchVerified(urls(b), downloaded, b.size, b.sha256, progress, cancel);
            zip = downloaded;
        }
        progress.phase("extract");
        Zip.deleteTree(dir);
        File tmp = new File(packages, part + ".tmp");
        Zip.deleteTree(tmp);
        Zip.extract(zip, tmp, null, this, progress, cancel);
        if (cancel.isCancelled()) {
            Zip.deleteTree(tmp);
            throw new InterruptedIOException("cancelled");
        }
        if (!tmp.renameTo(dir)) throw new IOException("cannot rename " + tmp);
        sp.edit().putString(part + "_sha256", b.sha256).commit();
        if (downloaded != null) {
            //noinspection ResultOfMethodCallIgnored
            downloaded.delete();
        }
    }

    @Override
    public void runtimeBroken() {
        sp.edit().remove("python_sha256").remove("ffmpeg_sha256").commit();
    }

    @Override
    public InputStream openBundledYtdlp() throws IOException {
        return ctx.getAssets().open("ytdlp.zip");
    }

    @Override
    public String bundledYtdlpVersion() {
        try {
            InputStream in = ctx.getAssets().open("ytdlp.version");
            try {
                byte[] b = new byte[64];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, UTF8).trim() : "";
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public long freeBytes(File dir) {
        try {
            return dir.getUsableSpace();
        } catch (SecurityException e) {
            return -1;
        }
    }

    @Override
    public void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("symlink " + link + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ saving

    static String mimeFor(String name, String kind) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        String ext = n.lastIndexOf('.') >= 0 ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (ext) {
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "ogg": return kind.equals("video") ? "video/ogg" : "audio/ogg";
            case "opus": return "audio/ogg";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "aiff": return "audio/aiff";
            case "mka": return "audio/x-matroska";
            case "webm": return kind.equals("audio") ? "audio/webm" : "video/webm";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "avi": return "video/x-msvideo";
            case "mp4": return kind.equals("audio") ? "audio/mp4" : "video/mp4";
            default: return kind.equals("audio") ? "audio/mpeg" : "video/mp4";
        }
    }

    /**
     * Copies the finished file into Download/HELINK through the system's media store
     * (no storage permission needed). The system picks "name (1).mp4" if the name is taken.
     */
    @Override
    public Saved save(File file, String displayName, String kind, String subdir, Cancel cancel, Http.ByteProgress progress)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String mime = mimeFor(displayName, kind);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        // A course's videos go in a folder of their own: Download/HELINK/<course>.
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                + (subdir == null || subdir.isEmpty() ? "" : "/" + subdir));
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item;
        try {
            item = cr.insert(collection, v);
        } catch (RuntimeException e) {
            throw new IOException("media store insert: " + e, e);
        }
        if (item == null) throw new IOException("media store refused the file");
        boolean ok = false;
        long total = file.length(), done = 0;
        try {
            OutputStream out = cr.openOutputStream(item, "w");
            if (out == null) throw new IOException("cannot write to the media store");
            InputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.isCancelled()) throw new InterruptedIOException("cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    progress.onBytes(done, total);
                }
            } finally {
                in.close();
                out.close();
            }
            ContentValues pub = new ContentValues();
            pub.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, pub, null, null);
            ok = true;
        } catch (RuntimeException e) {
            throw new IOException("save: " + e, e);
        } finally {
            if (!ok) {
                try {
                    cr.delete(item, null, null);
                } catch (RuntimeException ignored) {
                    // nothing more to do
                }
            }
        }
        Saved s = new Saved();
        s.uri = item.toString();
        s.name = displayName;
        s.size = total;
        s.mime = mime;
        s.folderLabel = saveLabel(subdir == null ? "" : subdir);
        Cursor c = null;
        try {
            c = cr.query(item, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && c.getString(0) != null) s.name = c.getString(0);
        } catch (RuntimeException ignored) {
            // keep the name we asked for
        } finally {
            if (c != null) c.close();
        }
        return s;
    }

    @Override
    public String saveLabel() {
        return com.alihelal.core.I18n.t("التنزيلات ← " + FOLDER, "Download › " + FOLDER);
    }

    @Override
    public String saveLabel(String subdir) {
        if (subdir == null || subdir.isEmpty()) return saveLabel();
        return saveLabel() + com.alihelal.core.I18n.t(" ← ", " › ") + subdir;
    }

    /** A file saved through the media store is still there (deleted from a file manager: its row is gone). */
    @Override
    public boolean exists(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse(uri), new String[]{MediaStore.MediaColumns._ID}, null, null, null);
            return c != null && c.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Shrinks a picture to 320 pixels wide for the downloads list (dozens of full pictures would use too much memory). */
    @Override
    public String smallPicture(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return dataUrl == null ? "" : dataUrl;
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) return "";
        try {
            byte[] raw = android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return "";
            final int target = 320;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target / 2) sample *= 2;
            android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
            d.inSampleSize = sample;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, d);
            if (b == null) return "";
            if (b.getWidth() > target) {
                int h = Math.max(1, Math.round(b.getHeight() * (float) target / b.getWidth()));
                android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, target, h, true);
                if (s != b) b.recycle();
                b = s;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
            b.recycle();
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (RuntimeException e) {
            Logs.note("small picture: " + e);
            return dataUrl.length() < 200_000 ? dataUrl : "";
        } catch (OutOfMemoryError e) {
            return "";
        }
    }

    @Override
    public void log(String message) {
        Log.i(TAG, message);
        Logs.add(message);
    }
}
