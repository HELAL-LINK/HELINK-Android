package com.alihelal.core;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HELINK updates itself from its page on GitHub (github.com/HELAL-LINK/HELINK-Android).
 * latest.json there names the newest version, what is new in it, and its APK with its size and
 * SHA-256, signed with HELINK's key (Ed25519: only the public half is in the app, so a changed
 * or forged file is ignored). The app looks a little after it starts and whenever its screen
 * shows or the internet comes back, at most every few hours (every few minutes while there is no
 * internet). A newer version is offered on the screen; it is downloaded and installed only when
 * the user says so ("Update now"); "Not now" asks again a day later. Android asks the user to
 * confirm the install, keeps the app's data, and the downloads that were running continue in the
 * new version.
 */
public final class Updates {
    public static final String APP_ID = "helink-android";
    /** Where people download HELINK themselves. */
    public static final String PAGE = "https://github.com/HELAL-LINK/HELINK-Android";
    /** GitHub itself, then a copy of the same repository on jsDelivr's network. */
    static List<String> manifestUrls = Arrays.asList(
            "https://raw.githubusercontent.com/HELAL-LINK/HELINK-Android/main/latest.json",
            "https://cdn.jsdelivr.net/gh/HELAL-LINK/HELINK-Android@main/latest.json",
            // HELINK's first page, kept in case it comes back (its files are signed with the same key).
            "https://raw.githubusercontent.com/helink-updates/HELINK-Android/main/latest.json");
    /** Checks latest.json's signature; the private half stays with whoever publishes HELINK. */
    static byte[] publicKey = Base64.getDecoder().decode("zVqJUl6UkCskdHwrssheiCMOtoAbKql+6/3bozJX2NQ=");
    /** Tests only: files from a local server. */
    static boolean allowLocalHttp;

    // When the app looks (variables, so the tests can make it quick).
    static long firstDelay = 8_000;
    static long every = 6L * 3600 * 1000;
    static long retryOffline = 2L * 60 * 1000;   // while there is no internet
    static long retryLater = 15L * 60 * 1000;    // after any other failure
    static long snooze = 24L * 3600 * 1000;      // "Not now"

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** One file of a version: where to get it, and how to recognise it. */
    public static final class FileRef {
        public final List<String> urls = new ArrayList<String>();
        public long size;
        public String sha256 = "";
    }

    /** What latest.json says (inside its signature). */
    public static final class Manifest {
        public String app = "", version = "", date = "";
        public long code;
        public final Map<String, List<String>> notes = new LinkedHashMap<String, List<String>>();
        public FileRef apk;
    }

    /** latest.json is not HELINK's (a bad signature or content). */
    public static final class BadManifest extends IOException {
        private static final long serialVersionUID = 1L;

        BadManifest(String why) {
            super("update manifest: " + why);
        }
    }

    /** Checks the signature and the content of latest.json ({"manifest": base64, "sig": base64}). */
    public static Manifest parse(byte[] data, byte[] key) throws BadManifest {
        Json.Obj outer = Json.parseObject(new String(data, UTF8));
        if (outer == null) throw new BadManifest("not JSON");
        byte[] body, sig;
        try {
            body = Base64.getDecoder().decode(J.str(outer, "manifest"));
            sig = Base64.getDecoder().decode(J.str(outer, "sig"));
        } catch (IllegalArgumentException e) {
            throw new BadManifest("not base64");
        }
        if (!Ed25519.verify(key, body, sig)) throw new BadManifest("bad signature");
        Json.Obj m = Json.parseObject(new String(body, UTF8));
        if (m == null) throw new BadManifest("not JSON inside");
        Manifest r = new Manifest();
        r.app = J.str(m, "app");
        r.version = J.str(m, "version");
        r.date = J.str(m, "date");
        r.code = (long) J.num(m, "versionCode");
        if (!r.app.equals(APP_ID) || parts(r.version) == null) throw new BadManifest("not for this app");
        Map<String, Object> notes = J.obj(m, "notes");
        if (notes != null) {
            for (String lang : new String[]{"ar", "en"}) {
                List<String> lines = new ArrayList<String>();
                for (Object o : J.arr(notes, lang)) if (o instanceof String && ((String) o).length() > 0) lines.add((String) o);
                r.notes.put(lang, lines);
            }
        }
        Map<String, Object> apk = J.obj(m, "apk");
        if (apk == null) throw new BadManifest("no apk");
        FileRef f = new FileRef();
        for (Object o : J.arr(apk, "urls")) {
            String u = o instanceof String ? (String) o : "";
            if (!u.startsWith("https://") && !(allowLocalHttp && u.startsWith("http://127.0.0.1:"))) throw new BadManifest("bad address");
            f.urls.add(u);
        }
        f.size = (long) J.num(apk, "size");
        f.sha256 = J.str(apk, "sha256");
        if (f.urls.isEmpty() || f.size <= 0 || !SHA.matcher(f.sha256).matches()) throw new BadManifest("bad apk");
        r.apk = f;
        return r;
    }

    /** "1.6.0" as numbers (null when it is not a version). */
    static int[] parts(String v) {
        v = Str.trimPrefix(Str.trimSpace(v == null ? "" : v), "v");
        if (v.length() == 0) return null;
        String[] p = v.split("\\.", -1);
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            if (p[i].length() == 0 || p[i].length() > 9) return null;
            for (int k = 0; k < p[i].length(); k++) if (p[i].charAt(k) < '0' || p[i].charAt(k) > '9') return null;
            out[i] = Integer.parseInt(p[i]);
        }
        return out;
    }

    /** Whether version a comes after version b ("1.10.0" after "1.9.3"). */
    public static boolean newer(String a, String b) {
        int[] pa = parts(a), pb = parts(b);
        if (pa == null || pb == null) return false;
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? pa[i] : 0, y = i < pb.length ? pb[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** No internet (or GitHub unreachable), as opposed to a bad answer. */
    static boolean networkTrouble(IOException e) {
        if (e instanceof BadManifest) return false;
        if (e instanceof Http.StatusException) {
            int c = ((Http.StatusException) e).code;
            return c >= 500 || c == 429 || c == 408;
        }
        return e instanceof UnknownHostException || e instanceof ConnectException || e instanceof NoRouteToHostException
                || e instanceof SocketTimeoutException || e instanceof SocketException || e instanceof InterruptedIOException
                || !(e instanceof Http.TooLargeException);
    }

    // ------------------------------------------------------------------ where the update stands

    /** The engine side: what the updater needs from it. */
    interface Owner {
        Host host();

        Prefs prefs();

        void emitUpdate(Map<String, Object> view);

        void background(String what, Runnable r);

        /** The downloads running now (they continue in the new version). */
        List<String> activeIds();
    }

    private final Owner owner;
    private final Object mu = new Object();
    // "" | checking | available | downloading | ready (downloaded, to install) | installing | error | latest
    private String phase = "";
    private Manifest latest;
    private long done, total;
    private UserError err;
    private String failedIn = ""; // "check" or "install"
    private long checkedAt;        // the last answer from GitHub
    private boolean checking;
    private long nextAuto;         // when an automatic look is due
    private Cancel cancel;
    private String installNote = ""; // why the last install did not finish ("aborted", or details)

    Updates(Owner owner) {
        this.owner = owner;
        // A new start of the app (Android ends it often in the background): it looks again soon,
        // so a version found before (and put off with "Not now") is still known.
        nextAuto = System.currentTimeMillis() + firstDelay;
    }

    /** The folder that keeps the downloaded update until it is installed. */
    File dir() {
        return new File(owner.host().dataDir(), "update");
    }

    /** Looks now if it is time: called when the screen shows, when the internet comes back, and after the start. */
    public void checkIfDue() {
        synchronized (mu) {
            if (checking || System.currentTimeMillis() < nextAuto) return;
            if (phase.equals("downloading") || phase.equals("installing") || phase.equals("ready")) return;
            checking = true;
        }
        owner.background("update check", new Runnable() {
            @Override
            public void run() {
                try {
                    check(false);
                } finally {
                    synchronized (mu) {
                        checking = false;
                    }
                }
            }
        });
    }

    /** "Check for updates" in About: the answer is shown even when there is nothing new. */
    public void checkNow() {
        synchronized (mu) {
            if (checking || phase.equals("downloading") || phase.equals("installing")) return;
            checking = true;
        }
        owner.background("update check", new Runnable() {
            @Override
            public void run() {
                try {
                    check(true);
                } finally {
                    synchronized (mu) {
                        checking = false;
                    }
                }
            }
        });
    }

    private Manifest fetch() throws IOException {
        IOException first = null;
        for (String u : manifestUrls) {
            try {
                Map<String, String> h = new LinkedHashMap<String, String>();
                h.put("Cache-Control", "no-cache");
                Http.Response r = Http.get(u, h, 30_000, 256 << 10, true, null, null);
                if (r.code != 200) throw new Http.StatusException(r.code);
                return parse(r.body, publicKey);
            } catch (IOException e) {
                owner.host().log("update manifest " + u + ": " + e);
                if (first == null) first = e;
            }
        }
        throw first != null ? first : new IOException("no address");
    }

    void check(boolean manual) {
        String before;
        synchronized (mu) {
            if (phase.equals("downloading") || phase.equals("installing")) return;
            before = phase;
            if (manual) {
                phase = "checking";
                err = null;
            }
        }
        if (manual) emit();
        Manifest m;
        try {
            m = fetch();
        } catch (IOException e) {
            boolean net = networkTrouble(e);
            synchronized (mu) {
                nextAuto = System.currentTimeMillis() + (net ? retryOffline : retryLater);
                if (phase.equals("downloading") || phase.equals("installing")) return;
                if (manual) {
                    if (latest != null) {
                        phase = "available"; // a new version is known already: it stays offered
                    } else {
                        phase = "error";
                        err = UserError.make(e instanceof BadManifest ? "update_verify" : "update_check");
                        err.details = e.toString();
                        failedIn = "check";
                    }
                } else if (phase.equals("checking")) {
                    phase = before;
                }
            }
            if (manual) emit();
            return;
        }
        boolean newer = newer(m.version, Engine.VERSION);
        synchronized (mu) {
            checkedAt = System.currentTimeMillis();
            nextAuto = checkedAt + every;
            if (phase.equals("downloading") || phase.equals("installing")) return;
            if (newer) {
                if (latest == null || !latest.version.equals(m.version) || !phase.equals("ready")) {
                    latest = m;
                    phase = readyFile(m) != null ? "ready" : "available";
                }
                err = null;
            } else {
                latest = null;
                phase = "latest";
                err = null;
            }
        }
        owner.prefs().putAll("updChecked", System.currentTimeMillis(), "updSeen", m.version);
        if (newer) owner.host().log("update: version " + m.version + " is available (this is " + Engine.VERSION + ")");
        if (newer || manual || before.equals("available") || before.equals("error") || before.equals("ready")) emit();
    }

    /**
     * "Not now": the update stays offered (the button, About) and is offered again in a day. A
     * failed try is forgotten: next time the window offers the update again.
     */
    public void later() {
        String v;
        boolean wasError;
        synchronized (mu) {
            v = latest != null ? latest.version : "";
            wasError = phase.equals("error");
            if (wasError) {
                phase = latest == null ? "" : readyFile(latest) != null ? "ready" : "available";
                err = null;
            }
        }
        if (v.length() > 0) owner.prefs().putAll("updSnoozed", v, "updSnoozeUntil", System.currentTimeMillis() + snooze);
        if (v.length() > 0 || wasError) emit();
    }

    private boolean snoozed(String v) {
        return v.equals(owner.prefs().getString("updSnoozed")) && System.currentTimeMillis() < owner.prefs().getLong("updSnoozeUntil");
    }

    void emit() {
        owner.emitUpdate(view());
    }

    /** Where the update stands, for the page. */
    public Map<String, Object> view() {
        synchronized (mu) {
            Map<String, Object> m = Json.obj("state", phase, "current", Engine.VERSION, "page", PAGE);
            if (checkedAt > 0) m.put("checkedAt", checkedAt);
            if (latest != null) {
                m.put("version", latest.version);
                List<String> notes = latest.notes.get(I18n.english() ? "en" : "ar");
                if (notes == null || notes.isEmpty()) notes = latest.notes.get(I18n.english() ? "ar" : "en");
                m.put("notes", notes == null ? new ArrayList<String>() : notes);
                m.put("sizeText", Text.humanSize(latest.apk.size));
                m.put("snoozed", snoozed(latest.version));
            }
            if (phase.equals("downloading")) {
                m.put("done", done);
                m.put("total", total);
                m.put("doneText", Text.humanSize(done));
                m.put("totalText", Text.humanSize(total));
                if (total > 0) m.put("percent", done * 100.0 / total);
            }
            if (phase.equals("ready") && installNote.length() > 0) m.put("installNote", installNote);
            if (err != null && phase.equals("error")) {
                m.put("error", err);
                m.put("failedIn", failedIn);
            }
            return m;
        }
    }

    /** The APK of version m if it is downloaded and checked already. */
    private File readyFile(Manifest m) {
        File f = new File(dir(), "HELINK-" + m.version + ".apk");
        if (f.length() != m.apk.size) return null;
        try {
            return Http.sha256(f).equalsIgnoreCase(m.apk.sha256) ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** "Update now": downloads the new version and checks it; then the page asks to install it. */
    public Map<String, Object> updateNow() {
        final Manifest m;
        final Cancel c;
        synchronized (mu) {
            if (phase.equals("downloading") || phase.equals("installing")) return Json.obj("ok", true);
            m = latest;
            if (m == null) return Json.obj("error", UserError.make("update_none"));
            if (phase.equals("ready")) return Json.obj("ok", true, "ready", true);
            phase = "downloading";
            err = null;
            installNote = "";
            done = 0;
            total = m.apk.size;
            cancel = c = new Cancel();
        }
        emit();
        owner.background("update download", new Runnable() {
            @Override
            public void run() {
                download(m, c);
            }
        });
        return Json.obj("ok", true);
    }

    private void download(Manifest m, Cancel c) {
        File d = dir();
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        // Other versions' files are of no use any more.
        File[] old = d.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!f.getName().startsWith("HELINK-" + m.version + ".apk")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        File dest = new File(d, "HELINK-" + m.version + ".apk");
        long free = owner.host().freeBytes(d);
        if (readyFile(m) == null && free >= 0 && free < m.apk.size * 2 + (20L << 20)) {
            UserError e = UserError.make("update_disk");
            e.need = m.apk.size * 2 + (20L << 20);
            e.free = free;
            failed(e, "install");
            return;
        }
        try {
            if (readyFile(m) == null) {
                final long[] last = {0};
                Http.fetchVerified(m.apk.urls, dest, m.apk.size, m.apk.sha256, new Http.ByteProgress() {
                    @Override
                    public void onBytes(long got, long all) {
                        synchronized (mu) {
                            done = got;
                            total = all > 0 ? all : m.apk.size;
                        }
                        long now = System.currentTimeMillis();
                        if (now - last[0] >= 250 || got == all) {
                            last[0] = now;
                            emit();
                        }
                    }
                }, c);
            }
        } catch (IOException e) {
            if (c.isCancelled()) {
                // Stopped: what was downloaded stays for the next try.
                synchronized (mu) {
                    phase = "available";
                    cancel = null;
                }
                emit();
                return;
            }
            UserError ue = UserError.make(String.valueOf(e.getMessage()).contains("checksum") ? "update_verify" : "update_network");
            ue.details = e.toString();
            failed(ue, "install");
            return;
        }
        synchronized (mu) {
            cancel = null;
            if (readyFile(m) == null) {
                err = UserError.make("update_verify");
                err.details = "size or SHA-256 differ from latest.json";
                phase = "error";
                failedIn = "install";
            } else {
                phase = "ready";
                done = total = m.apk.size;
            }
        }
        emit();
    }

    /** Stops downloading the update (what was downloaded is kept for next time). */
    public void cancel() {
        Cancel c;
        synchronized (mu) {
            c = phase.equals("downloading") ? cancel : null;
        }
        if (c != null) c.cancel();
    }

    private void failed(UserError e, String in) {
        synchronized (mu) {
            phase = "error";
            err = e;
            failedIn = in;
            cancel = null;
        }
        emit();
    }

    /**
     * The checked APK, about to be handed to Android's installer: the downloads running now are
     * noted (the new version continues them). null when there is none. Also while installing:
     * Android's window may not have come, the user asks again.
     */
    public File startInstall() {
        Manifest m;
        synchronized (mu) {
            if (!(phase.equals("ready") || phase.equals("installing")) || latest == null) return null;
            m = latest;
        }
        File f = readyFile(m);
        if (f == null) {
            synchronized (mu) {
                phase = "available";
            }
            emit();
            return null;
        }
        owner.prefs().putAll("updResume", Str.join(",", owner.activeIds()), "updFrom", Engine.VERSION, "updTo", m.version);
        synchronized (mu) {
            phase = "installing";
            installNote = "";
        }
        emit();
        return f;
    }

    /** Android's installer did not install it: the user said no, or it failed. The file stays ready. */
    public void installEnded(boolean aborted, String details) {
        owner.prefs().putAll("updResume", "", "updFrom", "", "updTo", "");
        synchronized (mu) {
            if (!phase.equals("installing")) return;
            if (aborted || latest == null) {
                phase = latest == null ? "" : "ready";
                installNote = "aborted";
            } else {
                phase = "error";
                err = UserError.make("update_failed");
                err.details = details;
                failedIn = "install";
            }
        }
        emit();
    }

    /** The page asks to try again after an error: back to offering the version. */
    public void reset() {
        synchronized (mu) {
            if (!phase.equals("error")) return;
            phase = latest == null ? "" : readyFile(latest) != null ? "ready" : "available";
            err = null;
        }
        emit();
    }

    /**
     * When the app starts: whether it was just updated (the version it is now), or an update was
     * started but this is still the old version; the ids of the downloads to continue.
     */
    String[] afterUpdate(List<String> resume) {
        Prefs p = owner.prefs();
        String to = p.getString("updTo"), from = p.getString("updFrom"), ids = p.getString("updResume");
        if (to.length() == 0 && ids.length() == 0) return new String[]{"", ""};
        String updated = "", failed = "";
        if (to.equals(Engine.VERSION)) {
            updated = Engine.VERSION;
            owner.host().log("update: now " + Engine.VERSION + " (was " + from + ")");
            Zip.deleteTree(dir());
            p.putAll("updSnoozed", "", "updSnoozeUntil", 0);
        } else if (to.length() > 0 && from.equals(Engine.VERSION)) {
            failed = to;
            owner.host().log("update to " + to + " did not happen");
        }
        for (String id : ids.split(",")) if (id.length() > 0) resume.add(id);
        p.putAll("updResume", "", "updFrom", "", "updTo", "");
        return new String[]{updated, failed};
    }
}
