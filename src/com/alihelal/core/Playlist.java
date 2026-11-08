package com.alihelal.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Courses and playlists (YouTube), as in HELINK for Windows: the whole list is read at once,
 * titles and lengths only (yt-dlp's --flat-playlist: one request for every hundred videos), the
 * user ticks the videos and picks one quality for all, and each video becomes a download of its
 * own, numbered in the list's order ("01 - …") in a folder named after the list. A video's own
 * qualities are read when its download starts; the quality chosen is a ceiling ("720p": the best
 * up to 720p).
 */
public final class Playlist {
    private Playlist() {}

    /** As much of a list as is shown (YouTube lists go up to 5000 videos). */
    static final int MAX_ENTRIES = 1000;

    /** One video of a list. */
    public static final class Entry implements Json.Value {
        public int n;                 // its place in the list (1-based)
        public String id = "", title = "", channel = "", durationText = "", thumbnail = "";
        public double duration;
        public boolean available;     // false: private, deleted, members only, or not out yet
        /** In the downloads already, as "video" and/or "audio" (the page leaves those unticked). */
        public final List<String> have = new ArrayList<String>();

        @Override
        public Object toJson() {
            Map<String, Object> m = Json.obj("n", n, "id", id, "title", title, "channel", channel);
            m.put("duration", duration);
            m.put("durationText", durationText);
            m.put("thumbnail", thumbnail);
            m.put("available", available);
            if (!have.isEmpty()) m.put("have", have);
            return m;
        }
    }

    /** A list as read. */
    public static final class Info implements Json.Value {
        public String id = "", title = "", channel = "", url = "";
        public final List<Entry> entries = new ArrayList<Entry>();
        public boolean more; // the list goes on after MAX_ENTRIES

        @Override
        public Object toJson() {
            int count = 0;
            double total = 0;
            for (Entry e : entries) {
                if (e.available) {
                    count++;
                    total += e.duration;
                }
            }
            Map<String, Object> m = Json.obj("id", id, "title", title, "channel", channel, "url", url);
            m.put("entries", entries);
            m.put("count", count);
            m.put("duration", total);
            m.put("durationText", Text.humanDuration(total));
            m.put("more", more);
            m.put("options", options()); // written in the language in use when shown
            return m;
        }
    }

    /** A quality offered for a whole list. */
    public static final class Option implements Json.Value {
        public String key = "", kind = "", label = "", tag = "", descId = "";
        public boolean recommended;
        public long perMinute; // about how many bytes a minute takes

        @Override
        public Object toJson() {
            Map<String, Object> m = Json.obj("key", key, "kind", kind, "label", label, "tag", tag, "desc", Formats.descText(descId));
            m.put("recommended", recommended);
            m.put("perMinute", perMinute);
            return m;
        }
    }

    /** Rough sizes of a minute of a YouTube video at each ceiling (picture and sound). */
    static long perMinute(int q) {
        switch (q) {
            case 1080: return 20L << 20;
            case 720: return 11L << 20;
            case 480: return 6L << 20;
            default: return 4L << 20;
        }
    }

    private static final int[] HEIGHTS = {1080, 720, 480, 360};
    private static final int[] KBPS = {320, 192, 128};

    static List<Option> options() {
        List<Option> out = new ArrayList<Option>();
        for (int q : HEIGHTS) {
            String[] w = Formats.qualityWords(q);
            Option o = new Option();
            o.key = "v" + q;
            o.kind = "video";
            o.label = w[0];
            o.tag = w[1];
            o.descId = w[2];
            o.recommended = q == 720;
            o.perMinute = perMinute(q);
            out.add(o);
        }
        for (int k : KBPS) {
            Option o = new Option();
            o.key = "mp3-" + k;
            o.kind = "audio";
            o.label = k + "k";
            o.tag = "MP3";
            o.descId = "a" + k;
            o.recommended = k == 192;
            o.perMinute = k * 1000L / 8 * 60;
            out.add(o);
        }
        return out;
    }

    /** How yt-dlp is asked for a list's quality key: {kind, label, format, sort, audio quality}; null if unknown. */
    static String[] format(String key) {
        if (key == null) return null;
        for (int q : HEIGHTS) {
            if (key.equals("v" + q)) {
                return new String[]{"video", Formats.qualityWords(q)[0], "bv*+ba/b", "res:" + q + ",vcodec:h264,acodec:aac", ""};
            }
        }
        for (int k : KBPS) {
            if (key.equals("mp3-" + k)) return new String[]{"audio", k + "k", "ba/b", "acodec:aac", k + "K"};
        }
        return null;
    }

    /** What a video of the list downloads, for its progress bar (YouTube's sound is about 130 kb/s). */
    static List<Formats.DlPart> parts(String kind, double duration) {
        long sound = (long) (duration * 130_000 / 8);
        List<Formats.DlPart> p = new ArrayList<Formats.DlPart>();
        if (!kind.equals("audio")) p.add(new Formats.DlPart("", 0, false));
        p.add(new Formats.DlPart("", sound, true));
        return p;
    }

    private static final String[] UNAVAILABLE = {"[private video]", "[deleted video]", "[unavailable video]", "[video unavailable]"};

    /** Reads yt-dlp's answer for a list (-J --flat-playlist). */
    static Info build(Json.Obj data) throws Formats.InfoException {
        List<Object> entries = J.arr(data, "entries");
        if (!J.str(data, "_type").equals("playlist") && entries.isEmpty()) throw new Formats.InfoException("novideo");
        Info l = new Info();
        l.id = J.str(data, "id");
        l.title = Str.trimSpace(J.str(data, "title"));
        l.channel = J.str(data, "channel");
        if (l.channel.length() == 0) l.channel = J.str(data, "uploader");
        int n = 0, usable = 0;
        for (Object raw : entries) {
            Map<String, Object> e = J.asObj(raw);
            if (e == null) {
                n++; // an entry that failed still has its place
                continue;
            }
            if (Str.equalFold(J.str(e, "ie_key"), "YoutubeTab") || J.str(e, "_type").equals("playlist")) continue; // a channel's tab
            n++;
            String id = J.str(e, "id");
            if (!Sites.VIDEO_ID.matcher(id).matches()) continue;
            if (l.entries.size() >= MAX_ENTRIES) {
                l.more = true;
                break;
            }
            String title = Str.trimSpace(J.str(e, "title"));
            boolean available = true;
            for (String u : UNAVAILABLE) if (Str.equalFold(title, u)) available = false;
            String av = J.str(e, "availability");
            if (av.equals("private") || av.equals("premium_only") || av.equals("subscriber_only") || av.equals("needs_auth")) available = false;
            String live = J.str(e, "live_status");
            if (live.equals("is_live") || live.equals("is_upcoming")) available = false;
            if (title.length() == 0) title = I18n.t("فيديو ", "Video ") + n;
            Entry x = new Entry();
            x.n = n;
            x.id = id;
            x.title = title;
            x.channel = J.str(e, "channel");
            if (x.channel.length() == 0) x.channel = J.str(e, "uploader");
            x.duration = J.num(e, "duration");
            x.durationText = Text.humanDuration(x.duration);
            x.thumbnail = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
            x.available = available;
            l.entries.add(x);
            if (available) usable++;
        }
        if (usable == 0) throw new Formats.InfoException("list_empty");
        if (l.title.length() == 0) l.title = I18n.t("قائمة تشغيل", "Playlist");
        return l;
    }

    private static final Pattern LIST_ID = Pattern.compile("^[A-Za-z0-9_-]{2,80}$");

    /**
     * The list a YouTube video link was opened from ("&list=…"), as the link to the whole list;
     * "" when there is none, or for the lists YouTube makes on its own (mixes, Watch later, Liked
     * videos), which belong to one person or never end.
     */
    public static String listOf(String raw) {
        GoUrl u;
        try {
            u = GoUrl.parse(raw == null ? "" : raw);
        } catch (GoUrl.ParseException e) {
            return "";
        }
        if (!Sites.YOUTUBE.equals(Sites.hostSite(u.hostname()))) return "";
        String id = u.queryGet("list");
        if (!LIST_ID.matcher(id).matches()) return "";
        String up = Str.upper(id);
        if (up.startsWith("RD") || up.equals("WL") || up.equals("LL") || up.equals("LM") || up.startsWith("UL")) return "";
        return "https://www.youtube.com/playlist?list=" + id;
    }

    /** The video a YouTube link is for ("" when it is not a video's link). */
    public static String videoId(String raw) {
        GoUrl u;
        try {
            u = GoUrl.parse(raw == null ? "" : raw);
        } catch (GoUrl.ParseException e) {
            return "";
        }
        if (!Sites.YOUTUBE.equals(Sites.hostSite(u.hostname()))) return "";
        String p = Str.trim(u.path, "/");
        String[] parts = p.length() == 0 ? new String[0] : p.split("/", -1);
        String id = "";
        if (Str.trimPrefix(Str.lower(u.hostname()), "www.").equals("youtu.be")) {
            if (parts.length >= 1) id = parts[0];
        } else if (parts.length == 1 && (parts[0].equals("watch") || parts[0].equals("watch_popup"))) {
            id = u.queryGet("v");
        } else if (parts.length >= 2 && Arrays.asList("shorts", "live", "embed", "v", "e").contains(parts[0])) {
            id = parts[1];
        }
        return Sites.VIDEO_ID.matcher(id).matches() ? id : "";
    }

    /**
     * Which YouTube videos were downloaded, as video or as sound, and where they were saved
     * (have.json): the downloads list keeps only the latest ones, and a course pasted again
     * should offer only its new lessons. A video counts while its file is still there.
     */
    static final class Ledger {
        private static final int MAX = 4000;
        private final File file;
        // video id → {"video": saved address, "audio": …}, oldest first
        private final LinkedHashMap<String, Map<String, Object>> ids = new LinkedHashMap<String, Map<String, Object>>();
        private boolean loaded;

        Ledger(File file) {
            this.file = file;
        }

        private void load() {
            if (loaded) return;
            loaded = true;
            if (!file.isFile()) return;
            try {
                Json.Obj o = Json.parseObject(new String(Http.readAll(new java.io.FileInputStream(file)), "UTF-8"));
                Map<String, Object> v = J.obj(o, "v");
                if (v == null) return;
                for (Map.Entry<String, Object> e : v.entrySet()) {
                    Map<String, Object> m = J.asObj(e.getValue());
                    if (m != null) ids.put(e.getKey(), m);
                }
            } catch (IOException e) {
                // a damaged file: start again
            }
        }

        synchronized void record(String id, String kind, String uri) {
            if (id.length() == 0 || uri.length() == 0) return;
            load();
            Map<String, Object> m = ids.remove(id);
            if (m == null) m = new LinkedHashMap<String, Object>();
            m.put(kind, uri);
            ids.put(id, m);
            Iterator<String> it = ids.keySet().iterator();
            while (ids.size() > MAX && it.hasNext()) {
                it.next();
                it.remove();
            }
            try {
                File tmp = new File(file.getPath() + ".tmp");
                Engine.writeText(tmp, Json.write(Json.obj("v", ids)));
                if (!tmp.renameTo(file)) throw new IOException("rename");
            } catch (IOException e) {
                // only a convenience: the next pasting of the list ticks this video again
            }
        }

        /** The saved addresses of a video, by kind ("video" → content://…). */
        synchronized Map<String, String> of(String id) {
            load();
            Map<String, String> out = new LinkedHashMap<String, String>();
            Map<String, Object> m = ids.get(id);
            if (m != null) for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
            return out;
        }
    }

    /** Kinds in a stable order. */
    static List<String> kinds(Set<String> s) {
        List<String> out = new ArrayList<String>();
        for (String k : new String[]{"video", "audio"}) if (s.contains(k)) out.add(k);
        return out;
    }

    static Set<String> set() {
        return new HashSet<String>();
    }
}
