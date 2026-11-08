package com.alihelal.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Everything behind the screen: reading a link, offering qualities, and a list of
 * downloads that run two at a time, can be paused and resumed (yt-dlp continues the
 * ".part" files with HTTP range requests), and survive the app being closed. Public
 * methods return quickly; slow work runs on background threads and reports through
 * events ("setup", "info", "thumb", "task").
 */
public final class Engine {
    public static final String VERSION = "1.7.1";
    /** Downloads that run at the same time; the others wait for their turn. */
    public static final int PARALLEL = 2;
    /** Finished downloads kept in the list (the files themselves stay in the Download folder). */
    static final int KEEP_FINISHED = 30;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Sends an event to the page. data is a Map, a Json.Value or anything Json.write accepts. */
    public interface Events {
        void emit(String name, Object data);
    }

    private final Host host;
    private final Events events;
    private final Prefs prefs;
    private final YtdlpTool ytdlp;
    private final File work, jobs, cache;
    private final ExecutorService pool;
    private final DownloadStore store;
    private final SepTool sep;
    // One music removal at a time: it keeps every core busy.
    private final Object sepSlot = new Object();
    private boolean sepBusy;

    private final Object mu = new Object();
    private boolean setupBusy;
    private Cancel setupCancel;
    private int fetchSeq;
    private Cancel fetchCancel;
    private Fetched cur;
    private Cancel bgCancel;
    // The video on screen, so a page that Android recreated can show it again.
    private Object lastInfo;
    // How the latest link reading ended (found or failed), for a page that was in the
    // background and missed the event.
    private Map<String, Object> lastFetchEnd;
    private boolean shuttingDown;

    // The downloads list, oldest first.
    private final List<DownloadTask> tasks = new ArrayList<DownloadTask>();
    private long storeSeq, lastStoreWrite;
    private int idSeq;

    // The course (playlist) on screen, and the reading it came from.
    private Playlist.Info curList;
    private int curListSeq;
    // YouTube videos downloaded before (a course pasted again offers only its new lessons).
    private final Playlist.Ledger ledger;
    // HELINK's own updates (Updates.java); what the start found after an update, said once.
    private final Updates updates;
    private String justUpdated = "", updateFailed = "";

    // The "part of a video" tab: its own reading of a link, the video on it, and the small
    // server that plays it (and WhatsApp statuses) before anything is saved.
    private int clipSeq;
    private Cancel clipCancel;
    private Fetched clipCur;
    private Map<String, Object> lastClip;
    private final PreviewServer previews;

    static final class Fetched {
        int seq;
        String url;   // the post's address, after opening short links
        String site;
        long at;
        List<Formats.Item> items;
        List<File> infoFiles;
    }

    public Engine(Host host, Events events) {
        this.host = host;
        this.events = events;
        this.previews = new PreviewServer(host);
        this.prefs = new Prefs(new File(host.dataDir(), "settings.json"));
        I18n.set(prefs.getString("lang"));
        this.ytdlp = new YtdlpTool(host, prefs);
        this.work = new File(host.dataDir(), "work");
        this.jobs = new File(work, "jobs");
        this.cache = new File(host.cacheDir(), "yt-dlp");
        for (File d : new File[]{work, jobs, cache}) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        this.pool = Executors.newCachedThreadPool(new ThreadFactory() {
            private int n;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "engine-" + (++n));
                t.setDaemon(true);
                return t;
            }
        });
        this.store = new DownloadStore(new File(host.dataDir(), "downloads.json"), new File(work, "thumbs"), host);
        this.sep = new SepTool(host);
        this.ledger = new Playlist.Ledger(new File(host.dataDir(), "have.json"));
        this.updates = new Updates(new Updates.Owner() {
            @Override
            public Host host() {
                return Engine.this.host;
            }

            @Override
            public Prefs prefs() {
                return prefs;
            }

            @Override
            public void emitUpdate(Map<String, Object> view) {
                emit("update", view);
            }

            @Override
            public void background(String what, Runnable r) {
                bg(what, r);
            }

            @Override
            public List<String> activeIds() {
                List<String> ids = new ArrayList<String>();
                synchronized (mu) {
                    for (DownloadTask t : tasks) if (t.active()) ids.add(t.id);
                }
                return ids;
            }
        });
        boolean changed = false;
        for (DownloadTask t : store.load()) {
            if (t.active()) {
                // The app was closed in the middle: what was downloaded stays, and the
                // user resumes it from the list.
                t.state = DownloadTask.PAUSED;
                t.stage = "";
                changed = true;
            }
            tasks.add(t);
        }
        // Just updated (or an update did not happen): the downloads that were running continue.
        List<String> resume = new ArrayList<String>();
        String[] up = updates.afterUpdate(resume);
        justUpdated = up[0];
        updateFailed = up[1];
        long now = System.currentTimeMillis();
        int n = 0;
        for (DownloadTask t : tasks) {
            if (resume.contains(t.id) && t.state.equals(DownloadTask.PAUSED)) {
                t.state = DownloadTask.QUEUED;
                t.queuedAt = now + n++;
                changed = true;
            }
        }
        if (changed) saveTasks(true);
        if (n > 0) schedule();
    }

    /** HELINK's own updates. */
    public Updates updates() {
        return updates;
    }

    private volatile File devListJson;

    /** Tests only: read a list from this yt-dlp answer instead of the internet. */
    public void setDevListJson(File f) {
        devListJson = f;
    }

    private volatile File devInfoJson;

    /** Tests only: read the video from this yt-dlp info file instead of the internet. */
    public void setDevInfoJson(File f) {
        devInfoJson = f;
    }

    public Prefs prefs() {
        return prefs;
    }

    public YtdlpTool ytdlp() {
        return ytdlp;
    }

    private void emit(String name, Object data) {
        try {
            events.emit(name, data);
        } catch (RuntimeException e) {
            host.log("emit " + name + ": " + e);
        }
    }

    private void bg(final String what, final Runnable r) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    host.log("crash in " + what + ": " + stack(t));
                }
            }
        });
    }

    static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 3000 ? s.substring(0, 3000) : s;
    }

    /** Turns a crash inside a background job into an error message instead of closing the app. */
    private UserError crashError(String what, Throwable t) {
        host.log("crash in " + what + ": " + stack(t));
        UserError e = UserError.make("unknown");
        e.details = t.toString();
        return e;
    }

    // ---------------------------------------------------------------------------------
    // Start-up
    // ---------------------------------------------------------------------------------

    /** Tools still to set up: "python", "ffmpeg", "ytdlp". */
    public List<String> missingTools() {
        List<String> m = new ArrayList<String>(host.runtimeMissing());
        if (!ytdlp.ready()) m.add("ytdlp");
        return m;
    }

    private static final long YTDLP_UNPACKED = 9L << 20;

    private long unpackedSize(String part) {
        return part.equals("ytdlp") ? YTDLP_UNPACKED : host.runtimeSize(part);
    }

    private long downloadSize(String part) {
        return part.equals("ytdlp") ? 0 : Math.max(0, host.runtimeDownloadSize(part));
    }

    /**
     * Work of one setup step for the progress bar: downloading a byte counts fully,
     * unpacking (much faster) a quarter.
     */
    private long workOf(String part) {
        return downloadSize(part) + unpackedSize(part) / 4;
    }

    /** The language of the app: "ar" (Modern Standard Arabic, the default) or "en". */
    public String lang() {
        return I18n.lang();
    }

    public void setLang(String lang) {
        I18n.set(lang);
        prefs.put("lang", I18n.lang());
    }

    public Map<String, Object> init() {
        Map<String, Object> r = Json.obj("screen", "ready", "saveDirLabel", host.saveLabel(), "version", VERSION);
        r.put("lang", I18n.lang());
        String lk = prefs.getString("lastKind");
        r.put("lastKind", lk.equals("audio") ? "audio" : "video");
        r.put("noMusic", prefs.getBool("noMusic"));
        r.put("clipKind", prefs.getString("clipKind").equals("audio") ? "audio" : "video");
        // The music remover is downloaded the first time it is used.
        r.put("sepSizeText", sep.ready() ? "" : Text.humanSize(SepTool.downloadSize()));
        // Music removal for videos and MP3s, finished downloads and files of the phone.
        r.put("sepFiles", true);
        List<String> missing = missingTools();
        if (!missing.isEmpty()) {
            r.put("screen", "setup");
            long download = 0;
            for (String m : missing) download += downloadSize(m);
            r.put("missing", missing);
            // How much the phone downloads once ("" when everything ships inside the app).
            r.put("setupSizeText", Text.humanSize(download));
        } else {
            r.put("missing", new ArrayList<String>());
        }
        r.put("parallel", PARALLEL);
        r.put("update", updates.view());
        synchronized (mu) {
            // Said once: "HELINK was updated to version …", or that the update did not happen.
            if (justUpdated.length() > 0) r.put("updated", justUpdated);
            if (updateFailed.length() > 0) r.put("updateFailed", updateFailed);
            justUpdated = "";
            updateFailed = "";
            r.put("busy", setupBusy ? "setup" : "");
            r.put("restore", Json.obj("info", lastInfo));
            if (lastClip != null) r.put("clip", lastClip);
            if (lastFetchEnd != null) r.put("fetch", lastFetchEnd);
        }
        r.put("tasks", taskList());
        return r;
    }

    /** Cleans leftovers and looks for a newer yt-dlp in the background. */
    public void startMaintenance() {
        final Cancel c = new Cancel();
        synchronized (mu) {
            if (bgCancel != null) bgCancel.cancel();
            bgCancel = c;
        }
        bg("maintenance", new Runnable() {
            @Override
            public void run() {
                cleanLeftovers();
                sep.removeOld();
                if (missingTools().isEmpty()) {
                    try {
                        // After an app update the copy inside the app can be newer than the one in use.
                        if (ytdlp.bundledIsNewer()) ytdlp.installBundled(c, null);
                        ytdlp.update(c, 72L * 3600 * 1000);
                    } catch (IOException e) {
                        host.log("background update: " + e);
                    }
                }
                // A new version of HELINK: looked for a little after the start.
                long wait = Updates.firstDelay;
                while (wait > 0 && !c.isCancelled()) {
                    try {
                        Thread.sleep(Math.min(wait, 500));
                    } catch (InterruptedException e) {
                        return;
                    }
                    wait -= 500;
                }
                if (!c.isCancelled()) updates.checkIfDue();
            }
        });
    }

    void cleanLeftovers() {
        long now = System.currentTimeMillis();
        File[] files = work.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && now - f.lastModified() > 24L * 3600 * 1000) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        // Work folders belong to entries of the list; anything else is a leftover (an older
        // version of the app, or a crash while deleting).
        Set<String> ids = new HashSet<String>();
        synchronized (mu) {
            for (DownloadTask t : tasks) ids.add(t.id);
        }
        File[] js = jobs.listFiles();
        if (js != null) {
            for (File j : js) {
                if (!ids.contains(j.getName()) && now - newest(j) > 3600L * 1000) Zip.deleteTree(j);
            }
        }
        store.pruneThumbs(ids);
        File[] tmp = host.cacheDir().listFiles();
        if (tmp != null) {
            for (File f : tmp) {
                String n = f.getName();
                if (f.isFile() && (n.startsWith("ytdlp") || n.startsWith("yt-dlp")) && n.endsWith(".zip")
                        && now - f.lastModified() > 3600 * 1000) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        ytdlp.cleanup();
    }

    private static long newest(File f) {
        long t = f.lastModified();
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) t = Math.max(t, newest(k));
        return t;
    }

    private static long treeSize(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += treeSize(k);
        return n;
    }

    public boolean busy() {
        synchronized (mu) {
            return setupBusy || activeCountLocked() > 0;
        }
    }

    /** Whether a download is running or waiting for its turn. */
    public boolean downloading() {
        return activeCount() > 0;
    }

    public int activeCount() {
        synchronized (mu) {
            return activeCountLocked();
        }
    }

    private int activeCountLocked() {
        int n = 0;
        for (DownloadTask t : tasks) if (t.active()) n++;
        return n;
    }

    /** Stops everything (running downloads are paused, so they can be resumed later). */
    public void shutdown() {
        previews.stop();
        List<Cancel> stop = new ArrayList<Cancel>();
        synchronized (mu) {
            shuttingDown = true;
            for (Cancel c : new Cancel[]{setupCancel, fetchCancel, clipCancel, bgCancel}) {
                if (c != null) stop.add(c);
            }
            for (DownloadTask t : tasks) {
                if (t.cancel != null) {
                    if (t.stopReason.length() == 0) t.stopReason = "pause";
                    stop.add(t.cancel);
                } else if (t.state.equals(DownloadTask.QUEUED)) {
                    t.state = DownloadTask.PAUSED;
                }
            }
        }
        for (Cancel c : stop) c.cancel();
        saveTasks(true);
        pool.shutdown();
    }

    // ---------------------------------------------------------------------------------
    // First-run setup
    // ---------------------------------------------------------------------------------

    /** Sends setup progress, at most every 150 ms unless the step changed. */
    private final class SetupReporter {
        final List<String> parts;
        final long total, dlTotal;
        long workBefore, dlBefore;
        long lastEmit;
        String phase = "", tool = "";
        int step;
        String part = "";

        SetupReporter(List<String> parts) {
            this.parts = parts;
            long t = 0, d = 0;
            for (String p : parts) {
                t += workOf(p);
                d += downloadSize(p);
            }
            total = Math.max(1, t);
            dlTotal = d;
        }

        void set(int step, String tool, String phase) {
            this.step = step;
            this.tool = tool;
            this.part = tool;
            this.phase = phase;
            send(0, true);
        }

        void phase(String phase) {
            this.phase = phase;
            send(0, true);
        }

        /** done: bytes downloaded (phase "download") or unpacked (phase "extract") in this step. */
        void send(long done, boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastEmit < 150) return;
            lastEmit = now;
            long work, dl = dlBefore;
            if (phase.equals("download")) {
                long d = Math.min(done, downloadSize(part));
                work = workBefore + d;
                dl = dlBefore + d;
            } else if (phase.equals("extract")) {
                work = workBefore + downloadSize(part) + Math.min(done, unpackedSize(part)) / 4;
                dl = dlBefore + downloadSize(part);
            } else {
                work = workBefore;
            }
            double pct = phase.equals("verify") ? 99 : Math.min(98, Math.min(total, work) * 100.0 / total);
            Map<String, Object> m = Json.obj("phase", phase, "tool", tool);
            m.put("step", step);
            m.put("steps", parts.size());
            m.put("percent", pct);
            if (phase.equals("download") && dlTotal > 0) {
                m.put("doneText", Text.humanSize(Math.max(dl, 1)));
                m.put("totalText", Text.humanSize(dlTotal));
            }
            emit("setup", m);
        }

        void stepDone() {
            workBefore += workOf(part);
            dlBefore += downloadSize(part);
        }
    }

    public void startSetup() {
        final Cancel c;
        synchronized (mu) {
            if (setupBusy) return;
            setupBusy = true;
            setupCancel = c = new Cancel();
        }
        bg("setup", new Runnable() {
            @Override
            public void run() {
                UserError fail = null;
                try {
                    installTools(c);
                } catch (SetupBlocked e) {
                    fail = UserError.make("setup_blocked");
                    fail.details = e.getMessage();
                } catch (IOException e) {
                    String msg = Str.lower(e.toString());
                    String code = "setup_failed";
                    if (msg.contains("no space") || msg.contains("enospc")) code = "disk";
                    else if (msg.contains("certpathvalidator") || msg.contains("certificate") && msg.contains("valid")) code = "clock";
                    fail = UserError.make(code);
                    fail.details = e.toString();
                    host.log("setup failed: " + stack(e));
                } catch (Throwable t) {
                    fail = crashError("setup", t);
                    fail.code = "setup_failed";
                }
                synchronized (mu) {
                    setupBusy = false;
                    setupCancel = null;
                }
                if (c.isCancelled()) return;
                if (fail != null) {
                    emit("setup", Json.obj("phase", "error", "error", fail));
                    return;
                }
                Map<String, Object> done = Json.obj("phase", "done");
                done.put("percent", 100);
                emit("setup", done);
                startMaintenance();
            }
        });
    }

    static final class SetupBlocked extends IOException {
        private static final long serialVersionUID = 1L;

        SetupBlocked(String details) {
            super(details);
        }
    }

    private void installTools(Cancel c) throws IOException {
        final List<String> parts = new ArrayList<String>(host.runtimeMissing());
        if (!ytdlp.ready()) parts.add("ytdlp");
        final SetupReporter rep = new SetupReporter(parts);
        int step = 0;
        for (final String part : parts) {
            step++;
            rep.set(step, part, downloadSize(part) > 0 ? "download" : "extract");
            Host.StepProgress p = new Host.StepProgress() {
                @Override
                public void onBytes(long done, long total) {
                    rep.send(done, false);
                }

                @Override
                public void phase(String phase) {
                    rep.phase(phase);
                }
            };
            if (part.equals("ytdlp")) ytdlp.installBundled(c, p);
            else host.installRuntime(part, c, p);
            rep.stepDone();
        }
        if (c.isCancelled()) throw new InterruptedIOException("cancelled");
        // Make sure everything starts, and let Python prepare its files once now, so the
        // first link does not wait for it.
        rep.set(Math.max(1, parts.size()), "ytdlp", "verify");
        verifyTools();
    }

    private static final String WARMUP =
            "import sys\n"
                    + "sys.path.insert(0, sys.argv[1])\n"
                    + "import yt_dlp\n"
                    + "for m in ['yt_dlp.YoutubeDL', 'yt_dlp.options', 'yt_dlp.extractor.lazy_extractors',\n"
                    + "          'yt_dlp.extractor.youtube', 'yt_dlp.extractor.facebook', 'yt_dlp.extractor.instagram',\n"
                    + "          'yt_dlp.extractor.tiktok', 'yt_dlp.extractor.twitter', 'yt_dlp.extractor.generic',\n"
                    + "          'yt_dlp.extractor.youtube.jsc._builtin.quickjs', 'yt_dlp.networking._urllib',\n"
                    + "          'yt_dlp.downloader.http', 'yt_dlp.downloader.hls', 'yt_dlp.downloader.dash',\n"
                    + "          'yt_dlp.postprocessor.ffmpeg', 'yt_dlp.postprocessor.movefilesafterdownload',\n"
                    + "          'ssl', 'http.cookiejar', 'xml.etree.ElementTree', 'email.utils', 'sqlite3', 'asyncio']:\n"
                    + "    try:\n"
                    + "        __import__(m)\n"
                    + "    except Exception as e:\n"
                    + "        print('warmup', m, e)\n"
                    + "print('ok', yt_dlp.version.__version__)\n";

    void verifyTools() throws IOException {
        File dir = ytdlp.dir();
        if (dir == null) throw new IOException("yt-dlp missing after setup");
        List<String> cmd = new ArrayList<String>(host.ytdlpCommand(dir));
        cmd.add("--version");
        StringBuilder log = new StringBuilder();
        String out = Runner.quickWithLog(host, cmd, host.toolEnv(), 240000, log);
        if (out == null || !out.contains(ytdlp.version())) {
            throw new SetupBlocked("yt-dlp --version:\n" + Str.tailLines(log.toString(), 25));
        }
        List<String> warm = host.pythonCommand(Arrays.asList("-c", WARMUP, dir.getAbsolutePath()));
        StringBuilder wlog = new StringBuilder();
        // Not fatal and not worth a long wait: whatever is left is prepared at the first link.
        if (Runner.quickWithLog(host, warm, host.toolEnv(), 150000, wlog) == null) {
            host.log("warm-up failed (not fatal): " + Str.tailLines(wlog.toString(), 10));
        }
    }

    public void cancelSetup() {
        synchronized (mu) {
            if (setupCancel != null) setupCancel.cancel();
        }
    }

    // ---------------------------------------------------------------------------------
    // Reading the video and its qualities
    // ---------------------------------------------------------------------------------

    private Map<String, Object> infoEvent(int seq, String status, String site, Object items, UserError err) {
        Map<String, Object> m = Json.obj("seq", seq, "status", status);
        if (site != null && site.length() > 0) {
            m.put("site", site);
            m.put("siteName", new SiteName(site));
        }
        if (items != null) m.put("items", items);
        if (err != null) m.put("error", err);
        return m;
    }

    /** A site's name, written in the language in use whenever the event is shown (again). */
    private static final class SiteName implements Json.Value {
        final String site;

        SiteName(String site) {
            this.site = site;
        }

        @Override
        public Object toJson() {
            return Sites.name(site);
        }
    }

    /** Starts reading the video behind a pasted link. Results arrive as "info" events. */
    public int fetch(String input) {
        final int seq;
        final Cancel c;
        synchronized (mu) {
            if (fetchCancel != null) fetchCancel.cancel();
            seq = ++fetchSeq;
            fetchCancel = c = new Cancel();
        }
        Sites.LinkResult lr = Sites.parseLink(input == null ? "" : input);
        String code = null;
        if (lr.result == Sites.UNSUPPORTED) code = "unsupported_site";
        else if (lr.result == Sites.NONE || lr.result == Sites.INVALID) code = "invalid_url";
        else if (lr.link.kind.equals(Sites.KIND_PLAYLIST) && !Sites.YOUTUBE.equals(lr.link.site)) code = "playlist";
        else if (lr.link.kind.equals(Sites.KIND_PROFILE)) code = Sites.YOUTUBE.equals(lr.link.site) ? "channel" : "profile";
        else if (lr.link.kind.equals(Sites.KIND_LIVE)) code = "live";
        if (code != null) {
            c.cancel();
            String site = lr.result == Sites.UNSUPPORTED ? "" : lr.link.site;
            emit("info", infoEvent(seq, "error", site, null, UserError.make(code).localize(site)));
            return seq;
        }
        final Sites.Link link = lr.link;
        // A YouTube course or playlist: the whole list is read, to download all of it at once.
        final boolean isList = link.kind.equals(Sites.KIND_PLAYLIST);
        Map<String, Object> loading = infoEvent(seq, "loading", link.site, null, null);
        if (isList) loading.put("isList", true);
        emit("info", loading);
        bg("fetch", new Runnable() {
            @Override
            public void run() {
                try {
                    if (isList) runFetchList(c, seq, link);
                    else runFetch(c, seq, link);
                } catch (Throwable t) {
                    if (!c.isCancelled()) fetchEnded(seq, infoEvent(seq, "error", link.site, null, crashError("fetch", t)));
                }
            }
        });
        return seq;
    }

    /** Stops reading a link. forget=true also drops the video shown (the user went home). */
    public void cancelFetch(boolean forget) {
        synchronized (mu) {
            if (fetchCancel != null) {
                fetchCancel.cancel();
                fetchCancel = null;
            }
            fetchSeq++;
            if (forget) lastInfo = null;
        }
    }

    private static final class FetchOut {
        Json.Obj data;
        String log = "";
        UserError err;
        boolean cancelled;
    }

    /** What reading a link gave: its videos, or why not. */
    private static final class Got {
        String site = "", target = "";
        List<Formats.Item> items;
        UserError err;
        boolean cancelled;
    }

    /** Told when yt-dlp is being repaired while a link is read (it takes a little longer). */
    private interface Updating {
        void now(String site);
    }

    /**
     * Reads the video (or videos) behind a link: short links are opened first, and yt-dlp is
     * repaired once if the site changed.
     */
    private Got readPost(Cancel c, Sites.Link link, Updating updating) {
        Got g = new Got();
        String site = link.site;
        g.site = site;
        if (!missingTools().isEmpty()) {
            g.err = UserError.make("tools_missing");
            return g;
        }
        String target = link.url;
        if (link.kind.equals(Sites.KIND_SHORT)) {
            // fb.watch/…, vm.tiktok.com/…, facebook.com/share/…: find the real post first.
            try {
                String resolved = Sites.resolveShortLink(link.url, c);
                if (c.isCancelled()) {
                    g.cancelled = true;
                    return g;
                }
                Sites.LinkResult r2 = Sites.parseLink(resolved);
                if (r2.result == Sites.FOUND && !r2.link.kind.equals(Sites.KIND_SHORT)) {
                    String k = r2.link.kind;
                    if (k.equals(Sites.KIND_PROFILE)) {
                        g.err = UserError.make("profile");
                        return g;
                    }
                    if (k.equals(Sites.KIND_LIVE)) {
                        g.err = UserError.make("live");
                        return g;
                    }
                    if (k.equals(Sites.KIND_PLAYLIST)) {
                        g.err = UserError.make("playlist");
                        return g;
                    }
                    target = r2.link.url;
                }
            } catch (IOException e) {
                if (c.isCancelled()) {
                    g.cancelled = true;
                    return g;
                }
                host.log("short link " + link.url + ": " + e);
            }
        }
        g.target = target;
        boolean repaired = false;
        while (true) {
            FetchOut fo = fetchJSON(c, target, site);
            if (c.isCancelled() || fo.cancelled) {
                g.cancelled = true;
                return g;
            }
            List<Formats.Item> items = null;
            UserError uerr = fo.err;
            if (uerr == null) {
                try {
                    Formats.Post post = Formats.buildPost(fo.data, link.site);
                    site = post.site;
                    items = post.items;
                } catch (Formats.InfoException ie) {
                    uerr = UserError.make(ie.code);
                    uerr.details = Str.tailLines(fo.log, 25);
                } catch (RuntimeException e) {
                    uerr = UserError.make("unknown");
                    uerr.details = "bad json: " + e;
                }
            }
            g.site = site;
            // The YouTube helper warned that it could not solve YouTube's challenge:
            // qualities may be missing, so try repairing once.
            if (uerr == null && Sites.YOUTUBE.equals(site) && !repaired && jsTrouble(fo.log)
                    && !hasQualityAbove(items.get(0).options, 360)) {
                uerr = UserError.make("jsruntime");
                uerr.details = Str.tailLines(fo.log, 25);
            }
            if (uerr != null) {
                if (!repaired && (uerr.outdated || uerr.jsRuntime)) {
                    repaired = true;
                    updating.now(site);
                    if (repair(c, uerr) || uerr.code.equals("jsruntime")) continue;
                }
                if (uerr.code.equals("jsruntime") && items != null && !items.isEmpty()) {
                    uerr = null; // we still have something to offer
                } else {
                    g.err = uerr;
                    return g;
                }
            }
            g.items = items;
            return g;
        }
    }

    /** Keeps what yt-dlp found for each video in a file, so its download starts right away. */
    private List<File> keepInfo(int seq, List<Formats.Item> items) {
        long stamp = System.nanoTime();
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < items.size(); i++) {
            File f = new File(work, "info-" + stamp + "-" + seq + "-" + i + ".json");
            try {
                writeText(f, items.get(i).raw);
            } catch (IOException e) {
                f = null;
            }
            files.add(f);
        }
        return files;
    }

    private void runFetch(Cancel c, final int seq, Sites.Link link) {
        Got g = readPost(c, link, new Updating() {
            @Override
            public void now(String site) {
                emit("info", infoEvent(seq, "updating", site, null, null));
            }
        });
        if (g.cancelled) return;
        if (g.err != null) {
            fail(c, seq, g.site, g.err);
            return;
        }
        String site = g.site, target = g.target;
        List<Formats.Item> items = g.items;
        // Keep what yt-dlp found for each video, so the download starts right away.
        List<File> files = keepInfo(seq, items);
        for (Formats.Item it : items) it.raw = ""; // free the memory
        Fetched fe = new Fetched();
        fe.seq = seq;
        fe.url = target;
        fe.site = site;
        fe.at = System.currentTimeMillis();
        fe.items = items;
        fe.infoFiles = files;
        Map<String, Object> ev = infoEvent(seq, "done", site, items, null);
        // A video opened from a course ("…&list=…"): the whole list is offered too.
        String listUrl = Sites.YOUTUBE.equals(site) ? Playlist.listOf(link.url) : "";
        if (listUrl.length() > 0) ev.put("listUrl", listUrl);
        synchronized (mu) {
            if (seq != fetchSeq) {
                deleteFiles(files);
                return;
            }
            cur = fe;
            lastInfo = ev;
            lastFetchEnd = ev;
        }
        pruneInfoFiles();
        emit("info", ev);
        if (!Sites.YOUTUBE.equals(site)) fetchThumbnails(fe);
    }

    private void fail(Cancel c, int seq, String site, UserError e) {
        if (!c.isCancelled()) fetchEnded(seq, infoEvent(seq, "error", site, null, e.localize(site)));
    }

    // ---------------------------------------------------------------------------------
    // A part of a video (the "part of a video" tab): played first, then only the part saved
    // ---------------------------------------------------------------------------------

    /** The small server that plays previews (also the phone's WhatsApp statuses). */
    public PreviewServer previews() {
        return previews;
    }

    /** One video of the post with what the page can play of it. */
    private static final class ClipItem implements Json.Value {
        final Formats.Item item;
        final Map<String, Object> preview;

        ClipItem(Formats.Item item, Map<String, Object> preview) {
            this.item = item;
            this.preview = preview;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object toJson() {
            Map<String, Object> m = (Map<String, Object>) item.toJson();
            m.put("preview", preview);
            return m;
        }
    }

    /** Starts reading a link for the cut screen. Results arrive as "clip" events. */
    public int clipFetch(String input) {
        final int seq;
        final Cancel c;
        synchronized (mu) {
            if (clipCancel != null) clipCancel.cancel();
            seq = ++clipSeq;
            clipCancel = c = new Cancel();
        }
        Sites.LinkResult lr = Sites.parseLink(input == null ? "" : input);
        String code = null;
        if (lr.result == Sites.UNSUPPORTED) code = "unsupported_site";
        else if (lr.result == Sites.NONE || lr.result == Sites.INVALID) code = "invalid_url";
        else if (lr.link.kind.equals(Sites.KIND_PLAYLIST)) code = "clip_list";
        else if (lr.link.kind.equals(Sites.KIND_PROFILE)) code = Sites.YOUTUBE.equals(lr.link.site) ? "channel" : "profile";
        else if (lr.link.kind.equals(Sites.KIND_LIVE)) code = "live";
        if (code != null) {
            c.cancel();
            String site = lr.result == Sites.UNSUPPORTED ? "" : lr.link.site;
            clipEnded(seq, infoEvent(seq, "error", site, null, UserError.make(code).localize(site)));
            return seq;
        }
        final Sites.Link link = lr.link;
        emit("clip", infoEvent(seq, "loading", link.site, null, null));
        bg("clip", new Runnable() {
            @Override
            public void run() {
                try {
                    runClipFetch(c, seq, link);
                } catch (Throwable t) {
                    if (!c.isCancelled()) clipEnded(seq, infoEvent(seq, "error", link.site, null, crashError("clip", t)));
                }
            }
        });
        return seq;
    }

    /** Stops reading a link on the cut screen; forget=true also drops the video shown there. */
    public void cancelClip(boolean forget) {
        synchronized (mu) {
            if (clipCancel != null) {
                clipCancel.cancel();
                clipCancel = null;
            }
            clipSeq++;
            if (forget) {
                lastClip = null;
                clipCur = null;
            }
        }
        if (forget) previews.drop("clip");
    }

    private void clipEnded(int seq, Map<String, Object> ev) {
        synchronized (mu) {
            if (seq == clipSeq && !J.str(ev, "status").equals("loading")) lastClip = ev;
        }
        emit("clip", ev);
    }

    private void runClipFetch(final Cancel c, final int seq, Sites.Link link) {
        Got g = readPost(c, link, new Updating() {
            @Override
            public void now(String site) {
                emit("clip", infoEvent(seq, "updating", site, null, null));
            }
        });
        if (g.cancelled || c.isCancelled()) return;
        if (g.err != null) {
            clipEnded(seq, infoEvent(seq, "error", g.site, null, g.err.localize(g.site)));
            return;
        }
        List<File> files = keepInfo(seq, g.items);
        previews.drop("clip");
        List<ClipItem> shown = new ArrayList<ClipItem>();
        for (Formats.Item it : g.items) {
            Map<String, Object> pv = Json.obj("mode", "");
            try {
                Json.Obj raw = Json.parseObject(it.raw);
                if (raw != null) {
                    Formats.Preview p = Formats.preview(raw);
                    pv.put("mode", p.mode);
                    if (p.videoUrl.length() > 0) pv.put("video", previews.add("clip", PreviewServer.web(p.videoUrl, p.videoHeaders, p.videoType)));
                    if (p.audioUrl.length() > 0) pv.put("audio", previews.add("clip", PreviewServer.web(p.audioUrl, p.audioHeaders, p.audioType)));
                }
            } catch (IOException e) {
                host.log("preview: " + e);
                pv.put("mode", "");
            }
            it.raw = ""; // free the memory
            shown.add(new ClipItem(it, pv));
        }
        Fetched fe = new Fetched();
        fe.seq = seq;
        fe.url = g.target;
        fe.site = g.site;
        fe.at = System.currentTimeMillis();
        fe.items = g.items;
        fe.infoFiles = files;
        Map<String, Object> ev = infoEvent(seq, "done", g.site, shown, null);
        synchronized (mu) {
            if (seq != clipSeq) {
                deleteFiles(files);
                return;
            }
            clipCur = fe;
            lastClip = ev;
        }
        pruneInfoFiles();
        emit("clip", ev);
        if (!Sites.YOUTUBE.equals(g.site)) fetchThumbnails(fe, true);
    }

    /**
     * Adds the part from start to end (seconds) of video number item of the post on the cut
     * screen to the downloads, in the quality chosen.
     */
    public Map<String, Object> clipDownload(int seq, int item, String key, double start, double end, boolean noMusic) {
        Fetched f;
        Formats.Item it;
        Formats.QualityOption opt;
        DownloadTask existing = null;
        synchronized (mu) {
            f = clipCur != null && clipCur.seq == seq ? clipCur : null;
            it = f != null && item >= 0 && item < f.items.size() ? f.items.get(item) : null;
            opt = it != null ? it.option(key) : null;
        }
        if (opt == null) return Json.obj("error", UserError.make("format").localize(""));
        double d = it.meta.duration;
        if (d > 0 && end > d) end = d;
        if (start < 0 || end - start < 0.5 || (d > 0 && start >= d)) {
            return Json.obj("error", UserError.make("clip_range").localize(""));
        }
        synchronized (mu) {
            for (DownloadTask x : tasks) {
                if (!x.finished() && x.url.equals(f.url) && x.index == it.index && x.key.equals(key) && x.noMusic == noMusic
                        && Math.abs(x.clipStart - start) < 0.05 && Math.abs(x.clipEnd - end) < 0.05) {
                    existing = x;
                }
            }
        }
        if (existing != null) {
            if (existing.state.equals(DownloadTask.PAUSED) || existing.state.equals(DownloadTask.ERROR)) resume(existing.id);
            Map<String, Object> r = Json.obj("id", existing.id);
            r.put("existing", true);
            return r;
        }
        prefs.putAll("clipKind", opt.kind, "noMusic", noMusic);
        return addTask(f, item, it, opt, noMusic, start, end);
    }

    // ---------------------------------------------------------------------------------
    // Courses and playlists (YouTube): the whole list at once
    // ---------------------------------------------------------------------------------

    /** A course's folder in Download/HELINK: named after the list. */
    static String folderOf(String title) {
        String folder = Text.limitBytes(Text.safeFileBase(title, 60), 120);
        return folder.length() == 0 ? I18n.t("قائمة تشغيل", "Playlist") : folder;
    }

    /** Where a course is saved, in words, in the language in use whenever the event is shown (again). */
    private final class FolderLabel implements Json.Value {
        final String folder;

        FolderLabel(String folder) {
            this.folder = folder;
        }

        @Override
        public Object toJson() {
            return host.saveLabel(folder);
        }
    }

    /** Reads a list; the "info" event says "list". */
    private void runFetchList(Cancel c, int seq, Sites.Link link) {
        String site = Sites.YOUTUBE;
        if (!missingTools().isEmpty()) {
            fail(c, seq, site, UserError.make("tools_missing"));
            return;
        }
        boolean repaired = false;
        while (true) {
            FetchOut fo = fetchListJSON(c, link.url);
            if (c.isCancelled() || fo.cancelled) return;
            Playlist.Info l = null;
            UserError uerr = fo.err;
            if (uerr == null) {
                try {
                    l = Playlist.build(fo.data);
                } catch (Formats.InfoException ie) {
                    uerr = UserError.make(ie.code);
                    uerr.details = Str.tailLines(fo.log, 25);
                } catch (RuntimeException e) {
                    uerr = UserError.make("unknown");
                    uerr.details = "bad json: " + e;
                }
            }
            if (uerr != null) {
                if (!repaired && (uerr.outdated || uerr.jsRuntime)) {
                    // YouTube changed something: yt-dlp is updated once, then the list read again.
                    repaired = true;
                    emit("info", infoEvent(seq, "updating", site, null, null));
                    if (repair(c, uerr)) continue;
                }
                fail(c, seq, site, uerr);
                return;
            }
            l.url = link.url;
            markHave(l);
            Map<String, Object> ev = infoEvent(seq, "list", site, null, null);
            ev.put("list", l);
            ev.put("folderLabel", new FolderLabel(folderOf(l.title)));
            synchronized (mu) {
                if (seq != fetchSeq) return;
                curList = l;
                curListSeq = seq;
                lastInfo = ev;
                lastFetchEnd = ev;
            }
            emit("info", ev);
            return;
        }
    }

    private FetchOut fetchListJSON(Cancel c, String link) {
        File dev = devListJson;
        if (dev != null) {
            FetchOut fo = new FetchOut();
            try {
                fo.data = Json.parseObject(new String(Http.readAll(new FileInputStream(dev)), UTF8));
            } catch (IOException e) {
                fo.err = UserError.make("unknown");
            }
            if (fo.data == null && fo.err == null) fo.err = UserError.make("unknown");
            return fo;
        }
        FetchOut fo = new FetchOut();
        File dir = ytdlp.acquire();
        Runner.Result res;
        try {
            if (dir == null) {
                fo.err = UserError.make("tools_missing");
                return fo;
            }
            List<String> cmd = new ArrayList<String>(host.ytdlpCommand(dir));
            cmd.addAll(ytdlpArgs());
            cmd.addAll(Arrays.asList("-J", "--flat-playlist", "--yes-playlist", "--", link));
            res = Runner.run(host, cmd, host.toolEnv(), true, null, c);
        } catch (IOException e) {
            fo.err = brokenTool(e.toString());
            return fo;
        } finally {
            ytdlp.release();
        }
        if (res.cancelled) {
            fo.cancelled = true;
            return fo;
        }
        String out = Str.trimSpace(res.stdout);
        if (out.startsWith("{")) {
            Json.Obj o = Json.parseObject(out);
            if (o != null) {
                fo.data = o;
                fo.log = res.log;
                return fo;
            }
        }
        if ((res.exitCode != 0 && Str.trimSpace(res.log).length() == 0) || runtimeCrashed(res.log)
                || res.exitCode == 126 || res.exitCode == 127) {
            fo.err = brokenTool("exit code " + res.exitCode + "\n" + res.log);
            return fo;
        }
        fo.log = res.log;
        fo.err = UserError.classifyFor(res.log, Sites.YOUTUBE);
        return fo;
    }

    /**
     * Notes which videos of the list are in the downloads already, as video or as sound:
     * downloaded (the file still there) or not finished yet. The page leaves them unticked, so a
     * course pasted again downloads only its new lessons.
     */
    private void markHave(Playlist.Info l) {
        Map<String, java.util.Set<String>> have = new java.util.HashMap<String, java.util.Set<String>>();
        java.util.Set<String> inList = new HashSet<String>();
        for (Playlist.Entry e : l.entries) inList.add(e.id);
        List<String[]> finished = new ArrayList<String[]>();
        synchronized (mu) {
            for (DownloadTask t : tasks) {
                if (t.isSep() || !(t.kind.equals("video") || t.kind.equals("audio"))) continue;
                String id = Playlist.videoId(t.url);
                if (!inList.contains(id)) continue;
                if (t.finished()) {
                    finished.add(new String[]{id, t.kind, t.path});
                } else {
                    if (!have.containsKey(id)) have.put(id, Playlist.set());
                    have.get(id).add(t.kind);
                }
            }
        }
        for (String[] f : finished) {
            if (have.containsKey(f[0]) && have.get(f[0]).contains(f[1])) continue;
            if (f[2].length() > 0 && host.exists(f[2])) {
                if (!have.containsKey(f[0])) have.put(f[0], Playlist.set());
                have.get(f[0]).add(f[1]);
            }
        }
        // Downloaded before and no longer in the list: what the ledger remembers.
        for (Playlist.Entry e : l.entries) {
            for (Map.Entry<String, String> k : ledger.of(e.id).entrySet()) {
                if (have.containsKey(e.id) && have.get(e.id).contains(k.getKey())) continue;
                if (host.exists(k.getValue())) {
                    if (!have.containsKey(e.id)) have.put(e.id, Playlist.set());
                    have.get(e.id).add(k.getKey());
                }
            }
        }
        for (Playlist.Entry e : l.entries) {
            e.have.clear();
            if (have.containsKey(e.id)) e.have.addAll(Playlist.kinds(have.get(e.id)));
        }
    }

    /**
     * Adds the videos picks (their places in the list, 1-based) of the list on screen to the
     * downloads, all in quality key ("v720", "mp3-192"), without their music if noMusic. Returns
     * {"ids", "added", "skipped" (already in the list), "folder"} or {"error"}.
     */
    public Map<String, Object> downloadList(int seq, List<Integer> picks, String key, boolean noMusic) {
        Playlist.Info l;
        synchronized (mu) {
            l = curList != null && curListSeq == seq ? curList : null;
        }
        if (l == null) return Json.obj("error", UserError.make("unknown"));
        String[] f = Playlist.format(key);
        if (f == null) return Json.obj("error", UserError.make("format").localize(Sites.YOUTUBE));
        String kind = f[0];
        java.util.Set<Integer> chosen = new HashSet<Integer>(picks == null ? new ArrayList<Integer>() : picks);
        List<Playlist.Entry> entries = new ArrayList<Playlist.Entry>();
        int last = 0;
        for (Playlist.Entry e : l.entries) {
            last = Math.max(last, e.n);
            if (chosen.contains(e.n) && e.available) entries.add(e);
        }
        if (entries.isEmpty()) return Json.obj("error", UserError.make("list_empty").localize(Sites.YOUTUBE));
        prefs.putAll("lastKind", kind, "noMusic", noMusic);
        // The course's folder in Download/HELINK, named after it.
        String folder = folderOf(l.title);
        int digits = Math.max(2, String.valueOf(last).length());
        long now = System.currentTimeMillis();
        List<DownloadTask> added = new ArrayList<DownloadTask>();
        int skipped = 0;
        List<DownloadTask> dropped;
        synchronized (mu) {
            int i = 0;
            for (Playlist.Entry e : entries) {
                String url = "https://www.youtube.com/watch?v=" + e.id;
                boolean dup = false;
                for (DownloadTask x : tasks) {
                    if (!x.finished() && x.url.equals(url) && x.key.equals(key) && x.noMusic == noMusic) dup = true;
                }
                if (dup) {
                    skipped++;
                    continue;
                }
                DownloadTask t = new DownloadTask();
                t.id = newId();
                t.url = url;
                t.site = Sites.YOUTUBE;
                t.title = e.title;
                t.channel = e.channel.length() > 0 ? e.channel : l.channel;
                t.durationText = e.durationText;
                t.key = key;
                t.kind = kind;
                t.label = f[1];
                t.formatSpec = f[2];
                t.sortSpec = f[3];
                t.audioQuality = f[4];
                t.noMusic = noMusic;
                t.parts = Playlist.parts(kind, e.duration);
                // Numbered in the list's order, so the lessons stay in order in the folder.
                String num = String.valueOf(e.n);
                while (num.length() < digits) num = "0" + num;
                t.base = num + " - " + Text.limitBytes(Text.safeFileBase(e.title, 80), 160);
                t.list = l.title;
                t.subdir = folder;
                // Downloaded in the list's order; shown in it too (the newest first, so the
                // first lesson gets the latest time).
                t.createdAt = now - i;
                t.queuedAt = now + i;
                i++;
                t.state = DownloadTask.QUEUED;
                t.thumb = e.thumbnail;
                tasks.add(t);
                added.add(t);
            }
            dropped = trimFinishedLocked();
        }
        for (DownloadTask d : dropped) {
            store.deleteThumb(d.id);
            emitRemoved(d.id);
        }
        List<String> ids = new ArrayList<String>();
        for (DownloadTask t : added) {
            store.writeThumb(t.id, t.thumb);
            emitTask("add", t, true);
            ids.add(t.id);
        }
        if (!added.isEmpty()) {
            saveTasks(true);
            schedule();
        }
        Map<String, Object> r = Json.obj("ids", ids, "added", added.size(), "skipped", skipped);
        r.put("folder", host.saveLabel(folder));
        return r;
    }

    private void fetchEnded(int seq, Map<String, Object> ev) {
        synchronized (mu) {
            if (seq == fetchSeq) lastFetchEnd = ev;
        }
        emit("info", ev);
    }

    private static void deleteFiles(List<File> files) {
        for (File f : files) {
            if (f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Removes what yt-dlp found for older links (a YouTube answer can take a few MB), keeping
     * the post on screen. Each download keeps its own copy in its work folder.
     */
    private void pruneInfoFiles() {
        Set<String> keep = new HashSet<String>();
        synchronized (mu) {
            for (Fetched f : new Fetched[]{cur, clipCur}) {
                if (f != null) for (File x : f.infoFiles) if (x != null) keep.add(x.getName());
            }
        }
        File[] files = work.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File x : files) {
            String n = x.getName();
            // A minute's grace: a link being read right now has written its files but is not "cur" yet.
            if (x.isFile() && n.startsWith("info-") && n.endsWith(".json") && !keep.contains(n)
                    && now - x.lastModified() > 60_000) {
                //noinspection ResultOfMethodCallIgnored
                x.delete();
            }
        }
    }

    static void writeText(File f, String s) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes(UTF8));
        } finally {
            out.close();
        }
    }

    static void copyFile(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * Loads the pictures of social-media posts here instead of in the page: Instagram and
     * TikTok refuse to show them inside other apps.
     */
    private void fetchThumbnails(final Fetched f) {
        fetchThumbnails(f, false);
    }

    /** clip: for the cut screen ("clipThumb" events). */
    private void fetchThumbnails(final Fetched f, final boolean clip) {
        final int seq = f.seq;
        final String site = f.site;
        final List<Formats.Item> items = f.items;
        bg("thumbnails", new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < items.size() && i < 12; i++) {
                    String src = items.get(i).meta.thumbnail;
                    if (src.length() == 0 || src.startsWith("data:")) continue;
                    synchronized (mu) {
                        if (seq != (clip ? clipSeq : fetchSeq)) return;
                    }
                    try {
                        String data = Sites.fetchImageDataURL(src, Sites.referer(site), null);
                        items.get(i).meta.thumbnail = data; // so a restored page shows it too
                        Map<String, Object> m = Json.obj("seq", seq, "index", i, "src", data);
                        emit(clip ? "clipThumb" : "thumb", m);
                        thumbArrived(f.url, items.get(i).index, data);
                    } catch (IOException e) {
                        host.log("thumbnail " + i + ": " + e);
                    }
                }
            }
        });
    }

    /** A download added before its picture arrived gets it now. */
    private void thumbArrived(String url, int index, String data) {
        List<DownloadTask> got = new ArrayList<DownloadTask>();
        synchronized (mu) {
            for (DownloadTask t : tasks) {
                if (t.url.equals(url) && t.index == index && !t.thumb.startsWith("data:")) got.add(t);
            }
        }
        if (got.isEmpty()) return;
        String small = host.smallPicture(data);
        for (DownloadTask t : got) {
            synchronized (mu) {
                t.thumb = small;
            }
            store.writeThumb(t.id, small);
            emitTask("update", t, true);
        }
    }

    static boolean jsTrouble(String log) {
        String l = Str.lower(log);
        return l.contains("challenge solving failed") || l.contains("javascript runtime") || l.contains("js challenge")
                || l.contains("no supported javascript");
    }

    static boolean hasQualityAbove(List<Formats.QualityOption> opts, int h) {
        for (Formats.QualityOption o : opts) if (o.kind.equals("video") && o.height > h) return true;
        return false;
    }

    /** Options used for every yt-dlp run. */
    private List<String> ytdlpArgs() {
        List<String> a = new ArrayList<String>(Arrays.asList("--ignore-config", "--no-color", "--encoding", "utf-8",
                "--socket-timeout", "30", "--cache-dir", cache.getAbsolutePath()));
        a.addAll(host.ytdlpPlatformArgs());
        return a;
    }

    private static boolean runtimeCrashed(String log) {
        String l = Str.lower(log);
        return l.contains("cannot link executable") || l.contains("fatal python error")
                || l.contains("could not find platform independent libraries");
    }

    private FetchOut fetchJSON(Cancel c, String link, String site) {
        FetchOut fo = new FetchOut();
        File dir = ytdlp.acquire();
        Runner.Result res;
        try {
            if (dir == null) {
                fo.err = UserError.make("tools_missing");
                return fo;
            }
            List<String> cmd = new ArrayList<String>(host.ytdlpCommand(dir));
            cmd.addAll(ytdlpArgs());
            File dev = devInfoJson;
            if (dev != null) {
                cmd.addAll(Arrays.asList("-J", "--no-clean-info-json", "--load-info-json", dev.getAbsolutePath()));
            } else {
                cmd.addAll(Arrays.asList("-J", "--no-playlist", "--flat-playlist", "--", link));
            }
            res = Runner.run(host, cmd, host.toolEnv(), true, null, c);
        } catch (IOException e) {
            fo.err = brokenTool(e.toString());
            return fo;
        } finally {
            ytdlp.release();
        }
        if (res.cancelled) {
            fo.cancelled = true;
            return fo;
        }
        String out = Str.trimSpace(res.stdout);
        // A post can come back with some parts failed (the photos of an Instagram carousel):
        // yt-dlp then still prints what it found, but ends with an error code.
        if (out.startsWith("{")) {
            Json.Obj o = Json.parseObject(out);
            if (o != null) {
                fo.data = o;
                fo.log = res.log;
                return fo;
            }
        }
        if ((res.exitCode != 0 && Str.trimSpace(res.log).length() == 0) || runtimeCrashed(res.log)
                || res.exitCode == 126 || res.exitCode == 127) {
            fo.err = brokenTool("exit code " + res.exitCode + "\n" + res.log);
            return fo;
        }
        fo.log = res.log;
        fo.err = UserError.classifyFor(res.log, site);
        return fo;
    }

    /**
     * yt-dlp did not start at all (damaged files): everything is unpacked again on the
     * setup screen.
     */
    private UserError brokenTool(String details) {
        host.log("yt-dlp did not start: " + details);
        prefs.put("ytdlpDir", "");
        host.runtimeBroken();
        UserError e = UserError.make("tools_missing");
        e.details = Str.tailLines(details, 25);
        return e;
    }

    /** Updates yt-dlp. Returns true when something changed. */
    private boolean repair(Cancel c, UserError uerr) {
        if (!(uerr.outdated || uerr.jsRuntime)) return false;
        try {
            return ytdlp.update(c, 15L * 60 * 1000);
        } catch (IOException e) {
            host.log("update yt-dlp: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------
    // The downloads list
    // ---------------------------------------------------------------------------------

    private DownloadTask find(String id) {
        if (id == null) return null;
        for (DownloadTask t : tasks) if (t.id.equals(id)) return t;
        return null;
    }

    private String newId() {
        return "d" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(++idSeq, 36);
    }

    File jobDir(DownloadTask t) {
        return new File(jobs, t.id);
    }

    /** The whole list for the page, newest first, with pictures. */
    public List<Object> taskList() {
        List<Object> out = new ArrayList<Object>();
        synchronized (mu) {
            for (int i = tasks.size() - 1; i >= 0; i--) out.add(tasks.get(i).toJson(true));
        }
        return out;
    }

    /** A copy of the downloads that run or wait, running ones first (for the notification). */
    public List<DownloadTask> activeTasks() {
        List<DownloadTask> out = new ArrayList<DownloadTask>();
        synchronized (mu) {
            for (int pass = 0; pass < 2; pass++) {
                for (DownloadTask t : tasks) {
                    if (pass == 0 ? !t.state.equals(DownloadTask.RUNNING) : !t.state.equals(DownloadTask.QUEUED)) continue;
                    DownloadTask c = new DownloadTask();
                    c.id = t.id;
                    c.title = t.title;
                    c.kind = t.kind;
                    c.label = t.label;
                    c.state = t.state;
                    c.stage = t.stage;
                    c.percent = t.percent;
                    c.done = t.done;
                    c.total = t.total;
                    c.speed = t.speed;
                    c.eta = t.eta;
                    c.stopReason = t.stopReason;
                    c.noMusic = t.noMusic;
                    c.type = t.type;
                    out.add(c);
                }
            }
        }
        return out;
    }

    private void emitTask(String op, DownloadTask t, boolean withThumb) {
        Map<String, Object> ev;
        synchronized (mu) {
            t.rev++;
            ev = Json.obj("op", op, "task", t.toJson(withThumb));
        }
        emit("task", ev);
    }

    private void emitRemoved(String id) {
        emit("task", Json.obj("op", "remove", "id", id));
    }

    /** Writes the list now (force) or at most every few seconds (progress). */
    private void saveTasks(boolean force) {
        String text;
        long seq;
        synchronized (mu) {
            long now = System.currentTimeMillis();
            if (!force && now - lastStoreWrite < 3000) return;
            lastStoreWrite = now;
            text = DownloadStore.serialize(tasks);
            seq = ++storeSeq;
        }
        store.write(text, seq);
    }

    /** Keeps the newest finished downloads in the list. Call with mu held. */
    private List<DownloadTask> trimFinishedLocked() {
        List<DownloadTask> drop = new ArrayList<DownloadTask>();
        int finished = 0;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            DownloadTask t = tasks.get(i);
            if (t.finished() && ++finished > KEEP_FINISHED) drop.add(t);
        }
        tasks.removeAll(drop);
        return drop;
    }

    /**
     * Adds video number item (0-based, in the order shown) of the post on screen in the
     * chosen quality to the downloads. Returns {"id": …} (with "existing": true when that
     * download was already in the list), or {"error": …}.
     */
    public Map<String, Object> download(int item, String key) {
        return download(item, key, false);
    }

    /** noMusic: remove the music once downloaded (only the voices stay), from a video or an MP3. */
    public Map<String, Object> download(int item, String key, boolean noMusic) {
        final Fetched f;
        final Formats.Item it;
        final Formats.QualityOption opt;
        DownloadTask existing = null;
        boolean nm = false;
        synchronized (mu) {
            f = cur;
            it = f != null && item >= 0 && item < f.items.size() ? f.items.get(item) : null;
            opt = it != null ? it.option(key) : null;
            if (opt != null) {
                nm = noMusic;
                for (DownloadTask x : tasks) {
                    if (!x.finished() && x.url.equals(f.url) && x.index == it.index && x.key.equals(key) && x.noMusic == nm) {
                        existing = x;
                    }
                }
            }
        }
        if (opt == null) return Json.obj("error", UserError.make("format").localize(""));
        if (existing != null) {
            // Already in the list: continue it instead of downloading it twice.
            if (existing.state.equals(DownloadTask.PAUSED) || existing.state.equals(DownloadTask.ERROR)) resume(existing.id);
            Map<String, Object> r = Json.obj("id", existing.id);
            r.put("existing", true);
            return r;
        }
        prefs.putAll("lastKind", opt.kind, "noMusic", noMusic);
        return addTask(f, item, it, opt, nm, 0, 0);
    }

    /**
     * Adds a video of the post read to the downloads: the quality chosen, with its music
     * removed or not, whole (clipEnd 0) or the part from clipStart to clipEnd (seconds).
     */
    private Map<String, Object> addTask(Fetched f, int item, Formats.Item it, Formats.QualityOption opt, boolean nm,
                                        double clipStart, double clipEnd) {
        DownloadTask t = new DownloadTask();
        synchronized (mu) {
            t.id = newId();
        }
        t.url = f.url;
        t.site = f.site;
        t.index = it.index;
        t.title = it.meta.title;
        t.channel = it.meta.channel;
        t.durationText = it.meta.durationText;
        t.key = opt.key;
        t.kind = opt.kind;
        t.label = opt.label;
        t.sizeText = opt.sizeText;
        t.sizeBytes = opt.sizeBytes;
        double share = 1;
        if (clipEnd > 0) {
            t.clipStart = clipStart;
            t.clipEnd = clipEnd;
            double d = it.meta.duration;
            if (d > 0) share = Math.min(1, (clipEnd - clipStart) / d);
            t.sizeBytes = (long) (opt.sizeBytes * share);
            t.sizeText = Text.humanSize(t.sizeBytes);
            t.durationText = Text.clock(clipEnd - clipStart);
        }
        t.formatSpec = opt.formatSpec;
        t.sortSpec = opt.sortSpec;
        t.audioQuality = opt.audioQuality;
        t.noMusic = nm;
        t.parts = new ArrayList<Formats.DlPart>();
        for (Formats.DlPart dp : opt.parts) t.parts.add(share < 1 ? new Formats.DlPart(dp.formatId, (long) (dp.bytes * share), dp.isAudio) : dp);
        String title = it.meta.title;
        if (f.items.size() > 1) title += " - " + (item + 1); // several videos in one post
        // File names are limited to 255 bytes; Arabic letters take two each, emoji four.
        String base = Text.limitBytes(Text.safeFileBase(title, 80), 170);
        // A part: its times in the name ("مقطع 1.20-2.05"; no ":" in file names).
        if (clipEnd > 0) base += " - " + I18n.t("مقطع ", "clip ") + Text.clock(clipStart).replace(':', '.') + "-" + Text.clock(clipEnd).replace(':', '.');
        if (opt.kind.equals("video")) base += " - " + opt.labelText();
        t.base = base;
        t.fetchedAt = f.at;
        t.createdAt = t.queuedAt = System.currentTimeMillis();
        t.state = DownloadTask.QUEUED;
        final String thumb = it.meta.thumbnail;
        t.thumb = thumb;
        final File info = item < f.infoFiles.size() ? f.infoFiles.get(item) : null;
        List<DownloadTask> dropped;
        synchronized (mu) {
            // Not started before its work folder has what yt-dlp found (see schedule()).
            t.adding = true;
            tasks.add(t);
            dropped = trimFinishedLocked();
        }
        for (DownloadTask d : dropped) {
            store.deleteThumb(d.id);
            emitRemoved(d.id);
        }
        saveTasks(true);
        emitTask("add", t, true);
        final DownloadTask added = t;
        // Off the page's thread: the button answers at once.
        bg("add", new Runnable() {
            @Override
            public void run() {
                try {
                    // The work folder keeps the partly downloaded files until the download is
                    // finished or deleted, and what yt-dlp found, so the download starts without
                    // asking the site again (renamed when complete, so it is never read half-copied).
                    File job = jobDir(added);
                    //noinspection ResultOfMethodCallIgnored
                    job.mkdirs();
                    if (info != null && fileOK(info)) {
                        File tmp = new File(job, "info.json.tmp");
                        try {
                            copyFile(info, tmp);
                            if (!tmp.renameTo(new File(job, "info.json"))) throw new IOException("rename failed");
                        } catch (IOException e) {
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                            host.log("keeping the video's info: " + e);
                        }
                    }
                    // A small copy of the picture for the list (the full one can be large).
                    String small = thumb.startsWith("data:") ? host.smallPicture(thumb) : thumb;
                    synchronized (mu) {
                        added.thumb = small;
                    }
                    store.writeThumb(added.id, small);
                } finally {
                    synchronized (mu) {
                        added.adding = false;
                    }
                    schedule();
                }
            }
        });
        return Json.obj("id", t.id);
    }

    // ---------------------------------------------------------------------------------
    // Removing the music from files the phone has
    // ---------------------------------------------------------------------------------

    private static final Set<String> MEDIA_EXT = new HashSet<String>(Arrays.asList("mp3", "m4a", "m4b", "aac", "wav",
            "flac", "ogg", "oga", "opus", "wma", "amr", "awb", "ac3", "aif", "aiff", "aifc", "mka", "ape", "mp4", "m4v",
            "mkv", "webm", "mov", "avi", "3gp", "3g2", "wmv", "flv", "ts", "mts", "m2ts", "mpg", "mpeg"));

    /** Whether the file can have sound to take the music from (by its type or its name). */
    static boolean looksLikeMedia(Host.Content f) {
        String m = Str.lower(f.mime);
        return m.startsWith("audio/") || m.startsWith("video/") || MEDIA_EXT.contains(extOf(f.name));
    }

    /** "song.mp3" → "song" (a file name that works on the phone). */
    static String baseOf(String name) {
        String n = Str.trimSpace(name);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (n.length() == 0) n = I18n.t("ملف", "file");
        return Text.limitBytes(Text.safeFileBase(n, 80), 170);
    }

    private DownloadTask newSepTask(Host.Content f) {
        DownloadTask t = new DownloadTask();
        synchronized (mu) {
            t.id = newId();
        }
        t.type = "sep";
        t.source = f.uri;
        t.srcName = f.name;
        t.srcSize = Math.max(0, f.size);
        t.noMusic = true;
        t.title = baseOf(f.name);
        t.base = t.title;
        String ext = extOf(f.name);
        t.label = ext.length() > 0 ? Str.upper(ext) : "";
        t.kind = Str.lower(f.mime).startsWith("audio/") || (!Str.lower(f.mime).startsWith("video/")
                && Arrays.asList("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr").contains(ext)) ? "audio" : "video";
        if (t.srcSize > 0) t.sizeBytes = t.srcSize;
        t.createdAt = t.queuedAt = System.currentTimeMillis();
        t.state = DownloadTask.QUEUED;
        t.stage = "copy";
        return t;
    }

    /**
     * Removes the music from a finished download of the list: a new entry makes a copy
     * without music (the file with music stays). Returns {"id": …} or {"error": …}.
     */
    public Map<String, Object> removeMusic(String id) {
        DownloadTask from, existing = null;
        synchronized (mu) {
            from = find(id);
            if (from != null && from.finished() && from.path.length() > 0) {
                for (DownloadTask x : tasks) {
                    if (x.isSep() && !x.finished() && x.source.equals(from.path)) existing = x;
                }
            }
        }
        if (from == null || !from.finished() || from.path.length() == 0) {
            return Json.obj("error", UserError.make("file_missing"));
        }
        if (existing != null) {
            if (existing.state.equals(DownloadTask.PAUSED) || existing.state.equals(DownloadTask.ERROR)) resume(existing.id);
            Map<String, Object> r = Json.obj("id", existing.id);
            r.put("existing", true);
            return r;
        }
        Host.Content f = new Host.Content();
        f.uri = from.path;
        f.name = from.name;
        f.mime = from.mime;
        f.size = from.fileSize;
        DownloadTask t = newSepTask(f);
        t.title = from.title;
        t.base = from.base.length() > 0 ? from.base : baseOf(from.name);
        t.channel = from.channel;
        t.site = from.site;
        t.url = from.url;
        t.index = from.index;
        t.durationText = from.durationText;
        t.kind = from.kind;
        t.label = from.kind.equals("audio") ? "MP3 " + from.labelText() : from.labelText();
        t.key = from.key;
        t.thumb = from.thumb;
        addSep(t);
        return Json.obj("id", t.id);
    }

    /**
     * Removes the music from files of the phone (chosen in the file picker or shared from
     * another app): one entry each, saved as "name (بدون موسيقى)" in the Download folder.
     * Returns {"ids": […], "skipped": n} (files that are not sound or video are skipped).
     */
    public Map<String, Object> addFiles(List<Host.Content> files) {
        List<Object> ids = new ArrayList<Object>();
        int skipped = 0;
        for (Host.Content f : files) {
            if (f == null || f.uri.length() == 0 || !looksLikeMedia(f)) {
                skipped++;
                continue;
            }
            DownloadTask t = newSepTask(f);
            addSep(t);
            ids.add(t.id);
        }
        Map<String, Object> r = Json.obj("ids", ids);
        r.put("skipped", skipped);
        return r;
    }

    /** Puts the entry in the list and copies its file in right away (the system's permission to read it may not last). */
    private void addSep(final DownloadTask t) {
        final Cancel copy = new Cancel();
        List<DownloadTask> dropped;
        synchronized (mu) {
            t.adding = true;
            t.copyCancel = copy;
            tasks.add(t);
            dropped = trimFinishedLocked();
        }
        for (DownloadTask d : dropped) {
            store.deleteThumb(d.id);
            emitRemoved(d.id);
        }
        if (t.thumb.length() > 0) store.writeThumb(t.id, t.thumb);
        saveTasks(true);
        emitTask("add", t, true);
        bg("copy", new Runnable() {
            @Override
            public void run() {
                try {
                    UserError e = copyIn(t, copy);
                    if (e != null && !copy.isCancelled()) host.log("copying " + t.srcName + ": " + e.code + " " + e.details);
                } finally {
                    synchronized (mu) {
                        t.adding = false;
                        t.copyCancel = null;
                        if (t.stage.equals("copy") && t.cancel == null) t.stage = "";
                    }
                    emitTask("update", t, false);
                    schedule();
                }
            }
        });
    }

    /** Copies the entry's file into its work folder (once). Returns null when it is there. */
    private UserError copyIn(final DownloadTask t, final Cancel c) {
        File job = jobDir(t);
        if (downloadedBefore(job) != null) return null;
        //noinspection ResultOfMethodCallIgnored
        job.mkdirs();
        String ext = extOf(t.srcName);
        File dst = new File(job, "src" + (ext.length() > 0 ? "." + ext : "")), tmp = new File(job, "src.part");
        long free = host.freeBytes(job);
        // The copy, the voices and the result.
        if (t.srcSize > 0 && free >= 0 && free < t.srcSize * 2 + (64L << 20)) {
            UserError e = UserError.make("disk");
            e.need = t.srcSize * 2 + (64L << 20);
            e.free = free;
            return e;
        }
        final Speed speed = new Speed();
        long last = 0, done = 0;
        sepProgress(t, "copy", 0, 0, t.srcSize, 0, -1, false);
        try {
            InputStream in = host.openContent(t.source);
            try {
                OutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[1 << 18];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (c.isCancelled()) throw new InterruptedIOException("cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        double r = speed.update(done);
                        if (now - last >= 300) {
                            last = now;
                            double eta = r > 0 && t.srcSize > done ? (t.srcSize - done) / r : -1;
                            sepProgress(t, "copy", t.srcSize > 0 ? Math.min(100, done * 100.0 / t.srcSize) : -1, done,
                                    Math.max(t.srcSize, done), r, eta, false);
                        }
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (done == 0) throw new java.io.FileNotFoundException("the file is empty");
            if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
            if (!tmp.renameTo(dst)) throw new IOException("cannot rename " + tmp);
            markDownloaded(job, dst);
            synchronized (mu) {
                if (t.srcSize <= 0) t.srcSize = done;
                t.sizeBytes = done;
            }
            return null;
        } catch (InterruptedIOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return UserError.make("unknown");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            String m = Str.lower(e.toString());
            UserError ue = UserError.make(m.contains("no space") || m.contains("enospc") ? "disk"
                    : e instanceof java.io.FileNotFoundException || m.contains("permission") || m.contains("denial") ? "file_missing" : "sep_unreadable");
            ue.details = e.toString();
            return ue;
        } catch (SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            UserError ue = UserError.make("file_missing");
            ue.details = e.toString();
            return ue;
        }
    }

    private Outcome runSep(DownloadTask t, Cancel c) {
        File src = downloadedBefore(jobDir(t));
        if (src == null) {
            // Not copied yet (or the copy was stopped): from the file's address again.
            UserError e = copyIn(t, c);
            if (c.isCancelled()) return Outcome.stopped();
            if (e != null) return Outcome.fail(e);
            src = downloadedBefore(jobDir(t));
            if (src == null) return Outcome.fail(UserError.make("file_missing"));
        }
        return noMusic(c, t, src, new Progress.Tracker(t.kind, t.parts));
    }

    /** Starts waiting downloads while fewer than PARALLEL run. */
    private void schedule() {
        List<DownloadTask> start = new ArrayList<DownloadTask>();
        synchronized (mu) {
            if (shuttingDown) return;
            int running = 0;
            // (a download whose music is being removed no longer takes a place)
            for (DownloadTask t : tasks) if (t.state.equals(DownloadTask.RUNNING) && !t.sepPhase) running++;
            while (running < PARALLEL) {
                DownloadTask next = null;
                for (DownloadTask t : tasks) {
                    if (t.state.equals(DownloadTask.QUEUED) && (next == null || t.queuedAt < next.queuedAt)) next = t;
                }
                // First come, first served: if the next one is still being added, it starts
                // when that is done (it calls schedule() then), and nothing overtakes it.
                if (next == null || next.adding) break;
                next.state = DownloadTask.RUNNING;
                next.stage = "preparing";
                next.error = null;
                next.cancel = new Cancel();
                next.stopReason = "";
                next.resumeAfterStop = false;
                start.add(next);
                running++;
            }
        }
        for (final DownloadTask t : start) {
            emitTask("update", t, false);
            final Cancel c = t.cancel;
            bg("download", new Runnable() {
                @Override
                public void run() {
                    Outcome o;
                    try {
                        o = runTask(t, c);
                    } catch (Throwable e) {
                        o = Outcome.fail(crashError("download", e));
                    }
                    finishTask(t, c, o);
                }
            });
        }
        if (!start.isEmpty()) saveTasks(true);
    }

    /** Pauses a download: it stops pulling data and keeps what it downloaded. */
    public void pause(String id) {
        Cancel stop = null;
        DownloadTask t;
        boolean save = false;
        synchronized (mu) {
            t = find(id);
            if (t == null) return;
            if (t.state.equals(DownloadTask.QUEUED)) {
                t.state = DownloadTask.PAUSED;
                t.stage = "";
                save = true;
            } else if (t.state.equals(DownloadTask.RUNNING) && t.cancel != null) {
                if (t.stopReason.length() == 0) t.stopReason = "pause";
                t.resumeAfterStop = false;
                stop = t.cancel;
            } else {
                return;
            }
        }
        emitTask("update", t, false);
        if (stop != null) stop.cancel(); // the download thread then marks it paused
        if (save) saveTasks(true);
    }

    /** Pauses everything that runs or waits (the notification's button, or Android's time limit). */
    public void pauseAll() {
        List<String> ids = new ArrayList<String>();
        synchronized (mu) {
            for (DownloadTask t : tasks) if (t.active()) ids.add(t.id);
        }
        for (String id : ids) pause(id);
    }

    /** Continues a paused or failed download from where it stopped. */
    public void resume(String id) {
        DownloadTask t;
        synchronized (mu) {
            t = find(id);
            if (t == null) return;
            if (t.cancel != null) {
                // Still stopping: it continues as soon as it has stopped.
                if (!t.stopReason.equals("pause")) return;
                t.resumeAfterStop = true;
            } else if (t.state.equals(DownloadTask.PAUSED) || t.state.equals(DownloadTask.ERROR)) {
                t.state = DownloadTask.QUEUED;
                t.error = null;
                t.stage = "";
                t.queuedAt = System.currentTimeMillis();
            } else {
                return;
            }
        }
        saveTasks(true);
        emitTask("update", t, false);
        schedule();
    }

    /**
     * Cancels a download and deletes what it downloaded so far. For a finished download it
     * only removes the entry from the list (the file stays in the Download folder).
     */
    public void delete(String id) {
        Cancel stop = null;
        final DownloadTask t;
        synchronized (mu) {
            t = find(id);
            if (t == null) return;
            if (t.cancel != null) {
                t.stopReason = "delete";
                t.resumeAfterStop = false;
                stop = t.cancel;
            } else {
                tasks.remove(t);
                if (t.copyCancel != null) t.copyCancel.cancel();
            }
        }
        if (stop != null) {
            emitTask("update", t, false);
            stop.cancel(); // the download thread removes it once the program has stopped
            return;
        }
        store.deleteThumb(t.id);
        saveTasks(true);
        emitRemoved(t.id);
        if (t.isSep() && !t.finished()) host.forgetContent(t.source);
        bg("delete", new Runnable() {
            @Override
            public void run() {
                Zip.deleteTree(jobDir(t));
            }
        });
    }

    /** Removes the finished downloads from the list (the files stay). */
    public void clearFinished() {
        List<DownloadTask> drop = new ArrayList<DownloadTask>();
        synchronized (mu) {
            for (DownloadTask t : tasks) if (t.finished()) drop.add(t);
            tasks.removeAll(drop);
        }
        if (drop.isEmpty()) return;
        saveTasks(true);
        for (DownloadTask t : drop) {
            store.deleteThumb(t.id);
            emitRemoved(t.id);
        }
    }

    /** The title of a download in the list ("" if none). */
    public String taskTitle(String id) {
        synchronized (mu) {
            DownloadTask t = find(id);
            return t == null ? "" : t.title;
        }
    }

    /** How a run of a download ended. */
    private static final class Outcome {
        Host.Saved saved;
        UserError error;
        boolean stopped;

        static Outcome fail(UserError e) {
            Outcome o = new Outcome();
            o.error = e;
            return o;
        }

        static Outcome stopped() {
            Outcome o = new Outcome();
            o.stopped = true;
            return o;
        }
    }

    private void finishTask(DownloadTask t, Cancel c, Outcome o) {
        c.done();
        boolean removed = false, deleteJob = false;
        String[] ledgerAdd = null;
        List<DownloadTask> dropped = new ArrayList<DownloadTask>();
        synchronized (mu) {
            t.cancel = null;
            t.sepPhase = false;
            String reason = t.stopReason;
            t.stopReason = "";
            if (reason.equals("delete")) {
                tasks.remove(t);
                removed = true;
                deleteJob = true;
            } else if (o.saved != null) {
                Host.Saved s = o.saved;
                if (Sites.YOUTUBE.equals(t.site) && !t.isSep()) ledgerAdd = new String[]{Playlist.videoId(t.url), t.kind, s.uri};
                t.state = DownloadTask.DONE;
                t.path = s.uri;
                t.name = s.name;
                t.mime = s.mime;
                t.folderLabel = s.folderLabel;
                t.fileSize = s.size;
                t.fileSizeText = Text.humanSize(s.size);
                t.finishedAt = System.currentTimeMillis();
                t.percent = 100;
                deleteJob = true;
                dropped = trimFinishedLocked();
            } else if (o.stopped || o.error == null || reason.equals("pause") || c.isCancelled()) {
                if (t.resumeAfterStop && !shuttingDown) {
                    t.state = DownloadTask.QUEUED;
                    t.queuedAt = System.currentTimeMillis();
                } else {
                    t.state = DownloadTask.PAUSED;
                }
            } else {
                t.state = DownloadTask.ERROR;
                t.error = o.error;
            }
            t.resumeAfterStop = false;
            t.stage = "";
            t.speed = 0;
            t.eta = -1;
        }
        if (deleteJob) Zip.deleteTree(jobDir(t));
        if (ledgerAdd != null) ledger.record(ledgerAdd[0], ledgerAdd[1], ledgerAdd[2]);
        if (t.isSep() && (removed || o.saved != null)) host.forgetContent(t.source);
        if (removed) store.deleteThumb(t.id);
        for (DownloadTask d : dropped) store.deleteThumb(d.id);
        saveTasks(true);
        if (removed) emitRemoved(t.id);
        else emitTask("update", t, false);
        for (DownloadTask d : dropped) emitRemoved(d.id);
        schedule();
    }

    // ---------------------------------------------------------------------------------
    // Running one download
    // ---------------------------------------------------------------------------------

    static boolean fileOK(File f) {
        return f.isFile() && f.length() > 0;
    }

    private void setStage(DownloadTask t, String stage) {
        synchronized (mu) {
            t.stage = stage;
        }
        emitTask("update", t, false);
    }

    /**
     * A conversion to MP3 that was stopped half-way leaves a short "name.mp3" next to the
     * downloaded sound; yt-dlp would take it for the finished file, so it is removed and
     * the conversion runs again.
     */
    static void dropHalfConverted(File tmp, String base) {
        File mp3 = new File(tmp, base + ".mp3");
        File[] all = tmp.listFiles();
        boolean source = false;
        if (all != null) {
            for (File f : all) {
                String n = f.getName();
                if (!n.startsWith(base + ".") || n.equals(mp3.getName())) continue;
                String rest = n.substring(base.length() + 1);
                if (rest.startsWith("temp.") || rest.startsWith("orig.")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                if (!n.endsWith(".part") && !n.endsWith(".ytdl") && !rest.contains(".part-Frag")) source = true;
            }
        }
        if (source && mp3.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            mp3.delete();
        }
    }

    private Outcome runTask(DownloadTask t, Cancel c) {
        if (!missingTools().isEmpty()) return Outcome.fail(UserError.make("tools_missing"));
        if (t.isSep()) return runSep(t, c);
        File job = jobDir(t), out = new File(job, "out"), tmp = new File(job, "tmp");
        if (!out.isDirectory() && !out.mkdirs()) return Outcome.fail(UserError.make("permission"));
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        if (t.sizeBytes > 0) {
            // The parts, the joined file and the copy in the Download folder; what was
            // downloaded before a pause already counts.
            long need = t.sizeBytes * 21 / 10 + (50L << 20) - treeSize(job);
            if (t.noMusic) need += t.sizeBytes + (sep.ready() ? 0 : SepTool.spaceNeeded());
            long free = host.freeBytes(work);
            if (free >= 0 && free < need) {
                UserError e = UserError.make("disk");
                e.need = need;
                e.free = free;
                return Outcome.fail(e);
            }
        }
        if (t.kind.equals("audio")) dropHalfConverted(tmp, t.base);
        if (t.noMusic) {
            // Paused while the music was being removed: the file is already downloaded.
            File had = downloadedBefore(job);
            if (had != null) return noMusic(c, t, had, new Progress.Tracker(t.kind, t.parts));
        }

        String ext = t.kind.equals("audio") ? "mp3" : "mp4";
        File info = new File(job, "info.json");
        boolean useInfo = fileOK(info) && System.currentTimeMillis() - t.fetchedAt < 3L * 3600 * 1000;
        boolean retried = false;
        while (true) {
            Progress.Tracker tracker = new Progress.Tracker(t.kind, t.parts);
            // (tests only: a course's videos come from the local test video)
            File dev = devInfoJson;
            DlRun run = ytdlpDownload(c, t, useInfo ? info : dev != null && t.list.length() > 0 ? dev : null, out, tmp, tracker);
            if (c.isCancelled()) return Outcome.stopped();
            if (run.startError == null && run.exitCode == 0) {
                File file = run.finalPath != null ? new File(run.finalPath) : null;
                if (file == null || !fileOK(file)) file = new File(out, t.base + "." + ext);
                if (!fileOK(file)) {
                    UserError e = UserError.make("unknown");
                    e.details = Str.tailLines(run.log, 25);
                    return Outcome.fail(e.localize(t.site));
                }
                if (t.noMusic) {
                    markDownloaded(job, file);
                    return noMusic(c, t, file, tracker);
                }
                return saveFile(c, t, file, tracker);
            }
            if (run.startError != null || (Str.trimSpace(run.log).length() == 0) || runtimeCrashed(run.log)
                    || run.exitCode == 126 || run.exitCode == 127) {
                return Outcome.fail(brokenTool(run.startError != null ? run.startError : "exit code " + run.exitCode + "\n" + run.log));
            }
            UserError uerr = UserError.classifyFor(run.log, t.site);
            host.log("download failed (" + uerr.code + "), exit " + run.exitCode);
            if (!retried) {
                boolean retry = false;
                String code = uerr.code;
                if ((code.equals("forbidden") || code.equals("format") || code.equals("unknown") || code.equals("extractor")
                        || code.equals("network")) && useInfo) {
                    // The links saved from the first look may have expired: ask the site again.
                    // The part already downloaded is kept and continued.
                    useInfo = false;
                    retry = true;
                    //noinspection ResultOfMethodCallIgnored
                    info.delete();
                }
                if (uerr.outdated || uerr.jsRuntime) {
                    setStage(t, "updating");
                    if (repair(c, uerr)) {
                        useInfo = false;
                        retry = true;
                    }
                }
                if (retry && !c.isCancelled()) {
                    retried = true;
                    continue;
                }
            }
            if (c.isCancelled()) return Outcome.stopped();
            return Outcome.fail(uerr);
        }
    }

    private void sendProgress(DownloadTask t, Progress.Overall o) {
        synchronized (mu) {
            if (t.cancel == null) return; // already finished
            t.apply(o);
        }
        emitTask("update", t, false);
        saveTasks(false);
    }

    /** Copies the finished file to its final place. */
    private Outcome saveFile(final Cancel c, final DownloadTask t, File file, Progress.Tracker tracker) {
        return saveFile(c, t, file, file.getName(), tracker);
    }

    private Outcome saveFile(final Cancel c, final DownloadTask t, File file, String name, Progress.Tracker tracker) {
        final Progress.Overall o = tracker.onPost("started", "MoveFiles");
        o.percent = -1;
        sendProgress(t, o);
        Host.Saved s;
        try {
            s = host.save(file, name, t.kind, t.subdir, c, new Http.ByteProgress() {
                long last;

                @Override
                public void onBytes(long done, long total) {
                    long now = System.currentTimeMillis();
                    if (now - last < 300 || total <= 0) return;
                    last = now;
                    sendProgress(t, o);
                }
            });
        } catch (IOException e) {
            if (c.isCancelled()) return Outcome.stopped();
            String m = Str.lower(String.valueOf(e.getMessage()));
            UserError ue = UserError.make(m.contains("space") || m.contains("enospc") ? "disk" : "permission");
            ue.details = e.toString();
            host.log("save failed: " + stack(e));
            return Outcome.fail(ue.localize(t.site));
        }
        Outcome done = new Outcome();
        done.saved = s;
        return done;
    }

    // ---------------------------------------------------------------------------------
    // Without music
    // ---------------------------------------------------------------------------------

    /** The name of a file without music: "title (بدون موسيقى).mp3". */
    static String noMusicName(String base, String ext) {
        return base + noMusicSuffix() + "." + ext;
    }

    static String noMusicSuffix() {
        return MediaSep.suffix();
    }

    /** Remembers the downloaded file, so a removal that was paused does not download it again. */
    private static void markDownloaded(File job, File file) {
        try {
            writeText(new File(job, "src.done"), file.getAbsolutePath());
        } catch (IOException ignored) {
            // then it is downloaded again (yt-dlp finds the file and skips it)
        }
    }

    private static File downloadedBefore(File job) {
        // (audio.done: HELINK 1.4's mark, for an MP3 paused while its music was removed)
        for (String name : new String[]{"src.done", "audio.done"}) {
            File m = new File(job, name);
            if (!m.isFile()) continue;
            try {
                InputStream in = new FileInputStream(m);
                try {
                    File f = new File(Str.trimSpace(new String(Http.readUpTo(in, 8192), UTF8)));
                    if (fileOK(f) && f.getAbsolutePath().startsWith(job.getAbsolutePath())) return f;
                } finally {
                    in.close();
                }
            } catch (IOException ignored) {
                // not there
            }
        }
        return null;
    }

    /** Sends the progress of a step of the music removal. */
    private void sepProgress(DownloadTask t, String stage, double percent, long done, long total, double speed, double eta,
                             boolean force) {
        synchronized (mu) {
            if (t.cancel == null && !t.adding) return; // already finished
            t.stage = stage;
            if (percent >= 0) t.percent = percent;
            t.done = done;
            t.total = total;
            t.speed = speed;
            t.eta = eta;
            t.partsCount = 0;
            t.partIndex = 0;
        }
        emitTask("update", t, false);
        saveTasks(force);
    }

    /** Bytes per second over the last few seconds. */
    private static final class Speed {
        long t0 = System.currentTimeMillis(), b0 = -1;
        double rate;

        double update(long done) {
            long now = System.currentTimeMillis();
            if (b0 < 0 || done < b0) {
                b0 = done;
                t0 = now;
                return rate;
            }
            if (now - t0 >= 1000) {
                double r = (done - b0) * 1000.0 / (now - t0);
                rate = rate == 0 ? r : rate * 0.6 + r * 0.4;
                b0 = done;
                t0 = now;
            }
            return rate;
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? Str.lower(name.substring(dot + 1)) : "";
    }

    private static Outcome failCode(String code, String details) {
        UserError e = UserError.make(code);
        e.details = Str.tailLines(details, 25);
        return Outcome.fail(e);
    }

    /**
     * Makes a copy of file without its music and saves that one: the downloaded video or
     * MP3 (the one with music is not kept), or a copy of a file of the phone. It waits its
     * turn (one file at a time), and the first time the music remover itself is downloaded.
     */
    private Outcome noMusic(final Cancel c, final DownloadTask t, File file, Progress.Tracker tracker) {
        File work = new File(jobDir(t), "voice");
        if (!work.isDirectory() && !work.mkdirs()) return Outcome.fail(UserError.make("permission"));
        setStage(t, "preparing");
        MediaSep.Probe p;
        try {
            p = MediaSep.probe(host, file, c);
        } catch (InterruptedIOException e) {
            return Outcome.stopped();
        } catch (MediaSep.Failure e) {
            if (c.isCancelled()) return Outcome.stopped();
            host.log("reading " + file.getName() + ": " + e.getMessage());
            return failCode(e.code, e.getMessage());
        } catch (IOException e) {
            if (c.isCancelled()) return Outcome.stopped();
            return failCode("sep_unreadable", e.toString());
        }
        if (!p.audio) return Outcome.fail(UserError.make("sep_no_audio"));
        // A download in MP3 stays an MP3 at the quality chosen; anything else keeps its own format.
        String mp3 = !t.isSep() && t.kind.equals("audio") ? (t.audioQuality.length() > 0 ? t.audioQuality : "192K") : "";
        MediaSep.Plan pl = MediaSep.planFor(p, extOf(file.getName()), mp3);
        if (t.isSep()) {
            synchronized (mu) {
                t.kind = pl.video ? "video" : "audio"; // what the file turned out to be
            }
        }
        MediaSep.Job j = new MediaSep.Job();
        j.src = file;
        j.work = work;
        j.base = "result";
        j.mp3 = mp3;
        j.probe = p;
        j.plan = pl;
        j.tag = sep.weightsMark() + ":" + file.getName() + ":" + file.length();
        // The voices are kept in a file (to continue after a pause) when there is room for them.
        long need = MediaSep.spaceNeeded(p, pl, file.length()) + (sep.ready() ? 0 : SepTool.spaceNeeded());
        long raw = MediaSep.rawBytes(p);
        long free = host.freeBytes(work);
        boolean had = MediaSep.stateFile(work).isFile() && MediaSep.voicesFile(work).isFile();
        long kept = had ? MediaSep.voicesFile(work).length() : 0;
        j.resumable = raw > 0 && (free < 0 || free + kept >= need + raw + (64L << 20));
        if (!j.resumable && had) {
            MediaSep.dropVoices(work);
            free = host.freeBytes(work);
        }
        if (free >= 0 && free < need) {
            UserError e = UserError.make("disk");
            e.need = need;
            e.free = free;
            return Outcome.fail(e);
        }
        if (!takeSepSlot(c, t)) return Outcome.stopped();
        File result;
        try {
            if (!sep.ready()) {
                Outcome o = installSep(c, t);
                if (o != null) return o;
            }
            sepProgress(t, "separate", 0, 0, 0, 0, -1, true);
            final long[] last = {0};
            final long began = System.currentTimeMillis();
            result = MediaSep.run(host, sep, j, c, new MediaSep.Listener() {
                @Override
                public void separating(double percent, double eta) {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 400) return;
                    last[0] = now;
                    sepProgress(t, "separate", Math.max(0, percent), 0, 0, 0, eta, false);
                }

                @Override
                public void finishing(String stage, double percent) {
                    sepProgress(t, stage, percent, 0, 0, 0, -1, percent < 0 || percent == 0);
                }
            });
            host.log("music removed from " + file.getName() + ": " + Math.round(p.duration) + " s of sound in "
                    + (System.currentTimeMillis() - began) / 1000 + " s");
        } catch (InterruptedIOException e) {
            return Outcome.stopped();
        } catch (MediaSep.Failure e) {
            if (c.isCancelled()) return Outcome.stopped();
            host.log("music removal failed: " + e.getMessage());
            if (e.code.equals("disk")) {
                UserError ue = UserError.make("disk");
                ue.details = Str.tailLines(e.getMessage(), 25);
                ue.need = need + raw;
                ue.free = host.freeBytes(work);
                return Outcome.fail(ue);
            }
            return failCode(e.code, e.getMessage());
        } catch (IOException e) {
            if (c.isCancelled()) return Outcome.stopped();
            host.log("music removal failed: " + stack(e));
            String m = Str.lower(String.valueOf(e.getMessage()));
            return failCode(m.contains("no space") || m.contains("enospc") ? "disk" : "sep_failed", String.valueOf(e.getMessage()));
        } finally {
            releaseSepSlot();
        }
        Outcome o = saveFile(c, t, result, noMusicName(t.base, j.plan.ext), tracker);
        if (o.saved != null) MediaSep.dropVoices(work);
        return o;
    }

    /** Downloads and prepares the music remover (once). Returns null when it is ready. */
    private Outcome installSep(final Cancel c, final DownloadTask t) {
        final Speed speed = new Speed();
        final long[] last = {0};
        final long size = SepTool.downloadSize();
        sepProgress(t, "sepmodel", 0, 0, size, 0, -1, true);
        try {
            sep.install(c, new Host.StepProgress() {
                @Override
                public void phase(String phase) {
                    if (phase.equals("extract")) sepProgress(t, "sepprep", -1, 0, 0, 0, -1, true);
                }

                @Override
                public void onBytes(long done, long total) {
                    double r = speed.update(done);
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 300) return;
                    last[0] = now;
                    long tot = total > 0 ? total : size;
                    double eta = r > 0 && tot > done ? (tot - done) / r : -1;
                    sepProgress(t, "sepmodel", Math.min(100, done * 100.0 / Math.max(1, tot)), done, tot, r, eta, false);
                }
            });
            return null;
        } catch (InterruptedIOException e) {
            return Outcome.stopped();
        } catch (IOException e) {
            if (c.isCancelled()) return Outcome.stopped();
            host.log("music remover setup: " + stack(e));
            String m = Str.lower(e.toString());
            String code = "sep_download";
            if (m.contains("no space") || m.contains("enospc")) code = "disk";
            else if (m.contains("certpathvalidator") || m.contains("certificate") && m.contains("valid")) code = "clock";
            else if (e instanceof SepTool.SetupFailed) code = "sep_setup";
            UserError ue = UserError.make(code);
            ue.details = Str.tailLines(e.toString(), 25);
            if (code.equals("disk")) {
                ue.need = SepTool.spaceNeeded();
                ue.free = host.freeBytes(host.dataDir());
            }
            return Outcome.fail(ue);
        }
    }

    /**
     * Waits for the other music removal to finish (false when cancelled meanwhile). From
     * now on the entry no longer takes a download's place: another download can start.
     */
    private boolean takeSepSlot(Cancel c, DownloadTask t) {
        boolean wasSep;
        synchronized (mu) {
            wasSep = t.sepPhase;
            t.sepPhase = true;
        }
        if (!wasSep) {
            bg("schedule", new Runnable() {
                @Override
                public void run() {
                    schedule();
                }
            });
        }
        boolean told = false;
        while (true) {
            synchronized (sepSlot) {
                if (c.isCancelled()) return false;
                if (!sepBusy) {
                    sepBusy = true;
                    return true;
                }
                if (told) {
                    try {
                        sepSlot.wait(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    continue;
                }
            }
            told = true;
            sepProgress(t, "sepwait", -1, 0, 0, 0, -1, true);
        }
    }

    private void releaseSepSlot() {
        synchronized (sepSlot) {
            sepBusy = false;
            sepSlot.notifyAll();
        }
    }

    private static final class DlRun {
        String finalPath;
        String log = "";
        int exitCode = -1;
        String startError;
    }

    private DlRun ytdlpDownload(Cancel c, final DownloadTask t, File info, File out, File tmp, final Progress.Tracker tracker) {
        final DlRun run = new DlRun();
        File dir = ytdlp.acquire();
        try {
            if (dir == null) {
                run.startError = "yt-dlp is not installed";
                return run;
            }
            List<String> cmd = new ArrayList<String>(host.ytdlpCommand(dir));
            cmd.addAll(ytdlpArgs());
            cmd.addAll(Arrays.asList(
                    "--newline", "--progress", "--progress-delta", "0.25",
                    "--progress-template", Progress.TEMPLATE_DOWNLOAD,
                    "--progress-template", Progress.TEMPLATE_POST,
                    "--print", Progress.PRINT_DONE,
                    "--no-mtime", "--no-playlist",
                    // Phones lose the connection now and then: wait a little longer before giving up.
                    "--retries", "15", "--fragment-retries", "15",
                    "--retry-sleep", "http:exp=1:16", "--retry-sleep", "fragment:exp=1:16",
                    "-f", t.formatSpec,
                    "-P", "home:" + out.getAbsolutePath(), "-P", "temp:" + tmp.getAbsolutePath(),
                    "-o", Text.escapeOutputTemplate(t.base) + ".%(ext)s"));
            if (t.sortSpec.length() > 0) cmd.addAll(Arrays.asList("-S", t.sortSpec));
            if (t.isClip()) {
                // Only the part: ffmpeg reads from its start to its end, and makes the cuts exact
                // (the picture around them is encoded again, quickly).
                cmd.addAll(Arrays.asList("--download-sections", "*" + Text.seconds(t.clipStart) + "-" + Text.seconds(t.clipEnd),
                        "--force-keyframes-at-cuts"));
                if (!t.kind.equals("audio")) cmd.addAll(Arrays.asList("--downloader-args", "ffmpeg_o:-preset veryfast -crf 21"));
            }
            if (t.kind.equals("audio")) {
                String q = t.audioQuality.length() > 0 ? t.audioQuality : "192K";
                cmd.addAll(Arrays.asList("-x", "--audio-format", "mp3", "--audio-quality", q, "--embed-metadata"));
            } else {
                cmd.addAll(Arrays.asList("--merge-output-format", "mp4", "--remux-video", "mp4"));
            }
            if (info != null) {
                cmd.addAll(Arrays.asList("--load-info-json", info.getAbsolutePath()));
            } else if (t.index > 0) {
                // One video of a post with several: ask for that one only.
                cmd.addAll(Arrays.asList("--playlist-items", String.valueOf(t.index), "--", t.url));
            } else {
                cmd.addAll(Arrays.asList("--", t.url));
            }
            final long[] lastSent = {0};
            final String[] lastStage = {""};
            Runner.Result res = Runner.run(host, cmd, host.toolEnv(), false, new Runner.LineHandler() {
                @Override
                public void onLine(String line, boolean fromStderr) {
                    if (t.isClip()) {
                        // ffmpeg cutting the part: how far it got.
                        double at = Progress.parseFfmpegTime(line);
                        if (at >= 0) {
                            Progress.Overall o = new Progress.Overall();
                            o.stage = Progress.STAGE_CLIP;
                            double len = Math.max(0.1, t.clipEnd - t.clipStart);
                            o.percent = Math.min(99, at / len * 100);
                            o.partsCount = 1;
                            o.partIndex = 1;
                            send(o, false);
                            return;
                        }
                    }
                    Progress.DlLine d = Progress.parseDownloadLine(line);
                    if (d != null) {
                        Progress.Overall o = tracker.onDownload(d);
                        send(o, d.status.equals("finished"));
                        return;
                    }
                    String[] pl = Progress.parsePostLine(line);
                    if (pl != null) {
                        Progress.Overall o = tracker.onPost(pl[0], pl[1]);
                        if (pl[0].equals("started")) send(o, true);
                        return;
                    }
                    String p = Progress.parseDoneLine(line);
                    if (p != null) {
                        synchronized (run) {
                            run.finalPath = p;
                        }
                    }
                }

                private void send(Progress.Overall o, boolean force) {
                    synchronized (lastSent) {
                        long now = System.currentTimeMillis();
                        if (!force && o.stage.equals(lastStage[0]) && now - lastSent[0] < 250) return;
                        lastSent[0] = now;
                        lastStage[0] = o.stage;
                    }
                    sendProgress(t, o);
                }
            }, c);
            synchronized (run) {
                run.log = res.log;
                run.exitCode = res.exitCode;
            }
            return run;
        } catch (IOException e) {
            run.startError = e.toString();
            return run;
        } finally {
            ytdlp.release();
        }
    }
}
