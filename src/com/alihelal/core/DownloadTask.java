package com.alihelal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One entry of the downloads list: what to download (fixed when it is added), where it
 * stands, and the finished file. Saved in downloads.json, so a paused or interrupted
 * download is still there, with its progress, after the app was closed.
 */
public final class DownloadTask {
    public static final String QUEUED = "queued";   // waiting for a free slot
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";   // stopped by the user (or the app was closed); the part downloaded is kept
    public static final String ERROR = "error";     // failed; "try again" continues from what was downloaded
    public static final String DONE = "done";

    // ---- what to download
    public String id = "";
    /** "" for a download; "sep" for removing the music from a file the phone already has. */
    public String type = "";
    /** For "sep": the file (the address the system gave for it), its name and size then. */
    public String source = "", srcName = "";
    public long srcSize;
    public String url = "";        // the post's address, after opening short links
    public String site = "";
    public int index;              // the video's position in the post for yt-dlp (1-based); 0 for a plain video
    public String title = "", channel = "", durationText = "";
    public String key = "", kind = "", label = "", sizeText = "";
    public long sizeBytes;
    public String formatSpec = "", sortSpec = "", audioQuality = "";
    /** Saved with the music removed (only the voices kept): MP3s and videos. */
    public boolean noMusic;
    public List<Formats.DlPart> parts = new ArrayList<Formats.DlPart>();
    public String base = "";       // the file name without extension
    /** The course (playlist) it was added from, if any, and its folder in Download/HELINK. */
    public String list = "", subdir = "";
    /** A part of the video only, from clipStart to clipEnd (seconds); clipEnd 0: the whole video. */
    public double clipStart, clipEnd;
    public long fetchedAt;         // when the video was read (the saved links expire after a few hours)
    public long createdAt, queuedAt, finishedAt;

    // ---- where it stands
    public String state = QUEUED;
    // while running: preparing, updating, video, audio, merge, convert, finish, copy (the phone's file
    // is copied in), sepmodel (getting the music remover), sepprep, sepwait (another file's music is
    // being removed), separate, save
    public String stage = "";
    public double percent;         // 0..100; -1 while a final step runs with no measurable progress
    public long done, total;       // bytes downloaded of the whole
    public double speed;           // bytes per second while running
    public double eta = -1;        // seconds left while running, -1 if unknown
    public int partsCount, partIndex;
    public UserError error;

    // ---- the finished file
    public String path = "", name = "", mime = "", folderLabel = "", fileSizeText = "";
    public long fileSize;

    // ---- only while the app runs (not saved)
    public String thumb = "";
    long rev;                 // grows with every change sent to the page (events can arrive out of order)
    Cancel cancel;            // set while a download process belongs to this task
    public String stopReason = ""; // "pause" or "delete" while the process is being stopped
    boolean resumeAfterStop;  // "resume" was pressed while it was still stopping
    boolean adding;           // just added: its work folder is still being prepared (or its file copied in)
    Cancel copyCancel;        // stops that copy
    boolean sepPhase;         // its music is being removed (or it waits for that): it takes no download's place

    public boolean active() {
        return state.equals(QUEUED) || state.equals(RUNNING);
    }

    public boolean finished() {
        return state.equals(DONE);
    }

    public boolean isSep() {
        return type.equals("sep");
    }

    public boolean isClip() {
        return clipEnd > 0;
    }

    /** Takes the latest progress of the running download. */
    void apply(Progress.Overall o) {
        stage = o.stage;
        partsCount = o.partsCount;
        partIndex = o.partIndex;
        speed = o.speed;
        eta = o.eta;
        if (o.percent >= 0) {
            percent = o.percent;
            if (o.total > 0) {
                done = o.done;
                total = o.total;
            }
        } else {
            // A final step (joining, converting, saving): the download itself is complete.
            percent = 100;
        }
    }

    // Words for the numbers, in the language in use when shown.
    public String doneText() {
        return total > 0 ? Text.humanSize(done) : "";
    }

    public String totalText() {
        return Text.humanSize(total);
    }

    public String speedText() {
        return state.equals(RUNNING) ? Text.humanSpeed(speed) : "";
    }

    public String etaText() {
        return state.equals(RUNNING) && eta >= 0 ? Text.humanETA(eta) : "";
    }

    /** "720p", "4K", "192k"; "Video" for a file of unknown size of picture. */
    public String labelText() {
        return key.equals("vbest") ? I18n.t("فيديو", "Video") : label;
    }

    /** What the page shows. The picture is sent only when asked (it can be large). */
    public Map<String, Object> toJson(boolean withThumb) {
        Map<String, Object> m = Json.obj("id", id, "title", title, "channel", channel, "durationText", durationText);
        m.put("rev", rev);
        if (isSep()) {
            m.put("type", type);
            m.put("srcName", srcName);
        }
        m.put("url", url);
        m.put("site", site);
        m.put("siteName", Sites.name(site));
        m.put("kind", kind);
        m.put("key", key);
        if (noMusic) m.put("noMusic", true);
        if (list.length() > 0) m.put("list", list);
        if (isClip()) m.put("clip", Text.clock(clipStart) + " – " + Text.clock(clipEnd));
        m.put("label", labelText());
        m.put("sizeText", sizeBytes > 0 ? Text.humanSize(sizeBytes) : sizeText);
        m.put("state", state);
        m.put("stage", stage);
        m.put("percent", percent);
        m.put("doneText", doneText());
        m.put("totalText", totalText());
        m.put("speedText", speedText());
        m.put("etaText", etaText());
        m.put("partsCount", partsCount);
        m.put("partIndex", partIndex);
        m.put("createdAt", createdAt);
        if (error != null && state.equals(ERROR)) m.put("error", error);
        if (state.equals(DONE)) {
            m.put("result", Json.obj("path", path, "name", name, "mime", mime, "folderLabel", folderLabel,
                    "sizeText", fileSize > 0 ? Text.humanSize(fileSize) : fileSizeText, "kind", kind));
            m.put("finishedAt", finishedAt);
        }
        if (stopReason.length() > 0) m.put("stopping", stopReason);
        if (withThumb && thumb.length() > 0) m.put("thumb", thumb);
        return m;
    }

    // ------------------------------------------------------------------ saving

    Map<String, Object> toStore() {
        Map<String, Object> m = Json.obj("id", id, "url", url, "site", site);
        if (isSep()) {
            m.put("type", type);
            m.put("source", source);
            m.put("srcName", srcName);
            m.put("srcSize", srcSize);
        }
        m.put("index", index);
        m.put("title", title);
        m.put("channel", channel);
        m.put("durationText", durationText);
        m.put("key", key);
        m.put("kind", kind);
        m.put("label", label);
        m.put("sizeText", sizeText);
        m.put("sizeBytes", sizeBytes);
        m.put("formatSpec", formatSpec);
        m.put("sortSpec", sortSpec);
        m.put("audioQuality", audioQuality);
        if (noMusic) m.put("noMusic", true);
        List<Object> ps = new ArrayList<Object>();
        for (Formats.DlPart p : parts) {
            Map<String, Object> pm = Json.obj("formatId", p.formatId);
            pm.put("bytes", p.bytes);
            pm.put("audio", p.isAudio);
            ps.add(pm);
        }
        m.put("parts", ps);
        m.put("base", base);
        if (list.length() > 0) m.put("list", list);
        if (subdir.length() > 0) m.put("subdir", subdir);
        if (isClip()) {
            m.put("clipStart", clipStart);
            m.put("clipEnd", clipEnd);
        }
        m.put("fetchedAt", fetchedAt);
        m.put("createdAt", createdAt);
        m.put("queuedAt", queuedAt);
        m.put("finishedAt", finishedAt);
        m.put("state", state);
        m.put("percent", percent);
        m.put("done", done);
        m.put("total", total);
        m.put("partsCount", partsCount);
        m.put("partIndex", partIndex);
        if (error != null) {
            // The code, not the words: the message is written in the language in use when shown.
            Map<String, Object> e = Json.obj("code", error.code, "details", error.details, "action", error.action,
                    "site", error.site);
            e.put("retry", error.retry);
            e.put("need", error.need);
            e.put("free", error.free);
            m.put("error", e);
        }
        if (state.equals(DONE)) {
            m.put("path", path);
            m.put("name", name);
            m.put("mime", mime);
            m.put("folderLabel", folderLabel);
            m.put("fileSizeText", fileSizeText);
            m.put("fileSize", fileSize);
        }
        return m;
    }

    static DownloadTask fromStore(Map<String, Object> m) {
        DownloadTask t = new DownloadTask();
        t.id = J.str(m, "id");
        t.type = J.str(m, "type").equals("sep") ? "sep" : "";
        t.source = J.str(m, "source");
        t.srcName = J.str(m, "srcName");
        t.srcSize = (long) J.num(m, "srcSize");
        t.url = J.str(m, "url");
        t.site = J.str(m, "site");
        t.index = (int) J.num(m, "index");
        t.title = J.str(m, "title");
        t.channel = J.str(m, "channel");
        t.durationText = J.str(m, "durationText");
        t.key = J.str(m, "key");
        t.kind = J.str(m, "kind");
        t.label = J.str(m, "label");
        t.sizeText = J.str(m, "sizeText");
        t.sizeBytes = (long) J.num(m, "sizeBytes");
        t.formatSpec = J.str(m, "formatSpec");
        t.sortSpec = J.str(m, "sortSpec");
        t.audioQuality = J.str(m, "audioQuality");
        t.noMusic = J.bool(m, "noMusic") || t.isSep();
        List<Object> ps = J.arr(m, "parts");
        if (ps != null) {
            for (Object o : ps) {
                Map<String, Object> pm = J.asObj(o);
                if (pm == null) continue;
                t.parts.add(new Formats.DlPart(J.str(pm, "formatId"), (long) J.num(pm, "bytes"), J.bool(pm, "audio")));
            }
        }
        t.base = J.str(m, "base");
        t.list = J.str(m, "list");
        t.subdir = J.str(m, "subdir");
        t.clipStart = J.num(m, "clipStart");
        t.clipEnd = J.num(m, "clipEnd");
        t.fetchedAt = (long) J.num(m, "fetchedAt");
        t.createdAt = (long) J.num(m, "createdAt");
        t.queuedAt = (long) J.num(m, "queuedAt");
        t.finishedAt = (long) J.num(m, "finishedAt");
        t.state = J.str(m, "state");
        t.percent = J.num(m, "percent");
        t.done = (long) J.num(m, "done");
        t.total = (long) J.num(m, "total");
        t.partsCount = (int) J.num(m, "partsCount");
        t.partIndex = (int) J.num(m, "partIndex");
        Map<String, Object> e = J.obj(m, "error");
        if (e != null) {
            UserError ue = UserError.make(J.str(e, "code"));
            ue.details = J.str(e, "details");
            ue.action = J.str(e, "action");
            ue.retry = J.raw(e, "retry") == null || J.bool(e, "retry");
            ue.site = J.str(e, "site").length() > 0 ? J.str(e, "site") : t.site;
            if (J.raw(e, "need") != null) ue.need = (long) J.num(e, "need");
            if (J.raw(e, "free") != null) ue.free = (long) J.num(e, "free");
            t.error = ue;
        }
        t.path = J.str(m, "path");
        t.name = J.str(m, "name");
        t.mime = J.str(m, "mime");
        t.folderLabel = J.str(m, "folderLabel");
        t.fileSizeText = J.str(m, "fileSizeText");
        t.fileSize = (long) J.num(m, "fileSize");
        return t;
    }

    boolean valid() {
        if (id.length() == 0 || kind.length() == 0 || base.length() == 0) return false;
        if (isSep() ? source.length() == 0 : url.length() == 0) return false;
        return state.equals(QUEUED) || state.equals(RUNNING) || state.equals(PAUSED) || state.equals(ERROR)
                || state.equals(DONE);
    }
}
