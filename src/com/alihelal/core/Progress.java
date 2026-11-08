package com.alihelal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reading yt-dlp's progress lines and merging them into one progress bar. */
public final class Progress {
    private Progress() {}

    // yt-dlp is asked to print machine-readable lines with these prefixes.
    public static final String TAG_DOWNLOAD = "NZLP|";
    public static final String TAG_POST = "NZLPP|";
    public static final String TAG_DONE = "NZLDONE|";

    public static final String TEMPLATE_DOWNLOAD = "download:" + TAG_DOWNLOAD
            + "%(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|"
            + "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.format_id)s";
    public static final String TEMPLATE_POST = "postprocess:" + TAG_POST + "%(progress.status)s|%(progress.postprocessor)s";
    public static final String PRINT_DONE = "after_move:" + TAG_DONE + "%(filepath)s";

    // Stage names shared with the UI.
    public static final String STAGE_VIDEO = "video";     // downloading the picture (or a ready-made file)
    public static final String STAGE_AUDIO = "audio";     // downloading the sound
    public static final String STAGE_MERGE = "merge";     // joining sound and picture
    public static final String STAGE_CONVERT = "convert"; // converting to MP3
    public static final String STAGE_FINISH = "finish";   // other quick final steps
    public static final String STAGE_SAVE = "save";       // moving the file to its folder
    public static final String STAGE_CLIP = "clip";       // cutting a part (ffmpeg fetches only that part)

    private static final java.util.regex.Pattern FF_TIME =
            java.util.regex.Pattern.compile("time=(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");

    /** The seconds done of an ffmpeg progress line ("frame=… time=00:01:02.50 …"), or -1. */
    public static double parseFfmpegTime(String line) {
        if (line == null || !line.contains("time=")) return -1;
        java.util.regex.Matcher m = FF_TIME.matcher(line);
        double best = -1;
        while (m.find()) {
            best = Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
        }
        return best;
    }

    public static final class DlLine {
        public String status = "";
        public double downloaded;
        public double total;    // exact or estimated, 0 if unknown
        public double speed;
        public double eta = -1; // -1 if unknown
        public String formatId = "";
    }

    /** Parses a number, or returns null for "NA", "None" or garbage. */
    static Double parseNum(String s) {
        s = Str.trimSpace(s);
        if (s.length() == 0 || s.equals("NA") || s.equals("None")) return null;
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static DlLine parseDownloadLine(String line) {
        if (!line.startsWith(TAG_DOWNLOAD)) return null;
        String[] f = line.substring(TAG_DOWNLOAD.length()).split("\\|", -1);
        if (f.length < 7) return null;
        DlLine d = new DlLine();
        d.status = f[0];
        StringBuilder id = new StringBuilder();
        for (int i = 6; i < f.length; i++) {
            if (i > 6) id.append('|');
            id.append(f[i]);
        }
        d.formatId = Str.trimSpace(id.toString());
        Double v = parseNum(f[1]);
        if (v != null) d.downloaded = v;
        Double t = parseNum(f[2]);
        if (t != null) d.total = t;
        else {
            t = parseNum(f[3]);
            if (t != null) d.total = t;
        }
        v = parseNum(f[4]);
        if (v != null) d.speed = v;
        v = parseNum(f[5]);
        if (v != null) d.eta = v;
        return d;
    }

    /** Returns {status ("started"|"finished"), postprocessor name}, or null. */
    public static String[] parsePostLine(String line) {
        if (!line.startsWith(TAG_POST)) return null;
        String rest = line.substring(TAG_POST.length());
        int bar = rest.indexOf('|');
        if (bar < 0) return null;
        return new String[]{Str.trimSpace(rest.substring(0, bar)), Str.trimSpace(rest.substring(bar + 1))};
    }

    public static String parseDoneLine(String line) {
        if (!line.startsWith(TAG_DONE)) return null;
        String p = Str.trimSpace(line.substring(TAG_DONE.length()));
        return p.length() > 0 && !p.equals("NA") ? p : null;
    }

    static String stageForPostprocessor(String pp) {
        if (pp.equals("Merger")) return STAGE_MERGE;
        if (pp.equals("ExtractAudio")) return STAGE_CONVERT;
        if (pp.equals("MoveFiles")) return STAGE_SAVE;
        return STAGE_FINISH;
    }

    /** What the UI receives while a download runs (numbers; the words are made when shown). */
    public static final class Overall implements Json.Value {
        public String stage = "";
        public double percent; // 0..100, -1 while a final step runs with no measurable progress
        public long done, total;
        public double speed;     // bytes per second, 0 if unknown
        public double eta = -1;  // seconds left, -1 if unknown
        public int partsCount, partIndex; // partIndex is 1-based

        public String doneText() {
            return total > 0 ? Text.humanSize(done) : "";
        }

        public String totalText() {
            return Text.humanSize(total);
        }

        public String speedText() {
            return Text.humanSpeed(speed);
        }

        public String etaText() {
            return eta >= 0 ? Text.humanETA(eta) : "";
        }

        @Override
        public Object toJson() {
            Map<String, Object> m = Json.obj("stage", stage);
            m.put("percent", percent);
            m.put("done", done);
            m.put("total", total);
            m.put("doneText", doneText());
            m.put("totalText", totalText());
            m.put("speedText", speedText());
            m.put("etaText", etaText());
            m.put("partsCount", partsCount);
            m.put("partIndex", partIndex);
            return m;
        }
    }

    private static final class Part {
        String id;
        boolean isAudio;
        double expected;
        double total; // learned from yt-dlp
        double done;
        boolean finished;
    }

    /** Merges the per-file progress of yt-dlp into one bar. */
    public static final class Tracker {
        private final List<Part> parts = new ArrayList<Part>();
        private final String kind;
        private Part current;
        private String stage;
        private double speed;

        public Tracker(String kind, List<Formats.DlPart> dlParts) {
            this.kind = kind;
            stage = "audio".equals(kind) ? STAGE_AUDIO : STAGE_VIDEO;
            for (Formats.DlPart p : dlParts) {
                Part t = new Part();
                t.id = p.formatId;
                t.isAudio = p.isAudio;
                t.expected = p.bytes;
                parts.add(t);
            }
        }

        private Part part(String id) {
            for (Part p : parts) if (p.id.equals(id)) return p;
            // yt-dlp used a fallback format: take the first part that has not started yet.
            for (Part p : parts) {
                if (!p.finished && p.done == 0 && p != current) {
                    p.id = id;
                    return p;
                }
            }
            Part p = new Part();
            p.id = id;
            parts.add(p);
            return p;
        }

        public synchronized Overall onDownload(DlLine d) {
            Part p = part(d.formatId);
            current = p;
            if (d.total > 0) p.total = d.total;
            if (d.downloaded > 0) p.done = d.downloaded;
            if (d.status.equals("finished")) {
                p.finished = true;
                if (p.total == 0) p.total = p.done;
                p.done = p.total;
            }
            if (d.speed > 0) speed = d.speed;
            stage = ("audio".equals(kind) || p.isAudio) ? STAGE_AUDIO : STAGE_VIDEO;
            return snapshot();
        }

        /** Records the start of a post-processing step. */
        public synchronized Overall onPost(String status, String pp) {
            if (status.equals("started")) stage = stageForPostprocessor(pp);
            Overall o = snapshot();
            o.percent = -1;
            o.speed = 0;
            o.eta = -1;
            return o;
        }

        private Overall snapshot() {
            double done = 0, total = 0;
            int idx = 0;
            for (int i = 0; i < parts.size(); i++) {
                Part p = parts.get(i);
                double size = p.total;
                if (size == 0) size = p.expected;
                if (size == 0 && p.done > 0) size = p.done;
                total += size;
                if (p.finished) done += size;
                else done += Math.min(p.done, size);
                if (p == current) idx = i + 1;
            }
            Overall o = new Overall();
            o.stage = stage;
            o.partsCount = parts.size();
            o.partIndex = idx;
            if (total > 0) {
                o.percent = Math.min(100, Math.max(0, done / total * 100));
                o.done = (long) done;
                o.total = (long) total;
            }
            if (speed > 0) {
                o.speed = speed;
                if (total > done) o.eta = (total - done) / speed;
            }
            return o;
        }
    }
}
