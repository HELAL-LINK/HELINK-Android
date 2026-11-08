package com.alihelal.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns yt-dlp's answer for a link into the short list of choices the user sees. */
public final class Formats {
    private Formats() {}

    // ---------------------------------------------------------------------------------
    // yt-dlp's info JSON (the subset we need)
    // ---------------------------------------------------------------------------------

    static final class Thumb {
        String id, url;
        double width, height;
    }

    static final class Fmt {
        String formatId, ext, vcodecRaw, acodecRaw, formatNote, protocol, dynamicRange, language;
        double width, height, fps, tbr, abr, filesize, filesizeApprox;
        Double languagePreference;
        Object hasDrm;

        static Fmt from(Map<String, Object> m) {
            Fmt f = new Fmt();
            f.formatId = J.str(m, "format_id");
            f.ext = J.str(m, "ext");
            f.vcodecRaw = J.str(m, "vcodec");
            f.acodecRaw = J.str(m, "acodec");
            f.width = J.num(m, "width");
            f.height = J.num(m, "height");
            f.fps = J.num(m, "fps");
            f.tbr = J.num(m, "tbr");
            f.abr = J.num(m, "abr");
            f.filesize = J.num(m, "filesize");
            f.filesizeApprox = J.num(m, "filesize_approx");
            f.formatNote = J.str(m, "format_note");
            f.protocol = J.str(m, "protocol");
            f.dynamicRange = J.str(m, "dynamic_range");
            f.language = J.str(m, "language");
            f.languagePreference = J.optNum(m, "language_preference");
            f.hasDrm = J.raw(m, "has_drm");
            return f;
        }

        String id() {
            return formatId;
        }

        String vcodec() {
            return Str.lower(vcodecRaw);
        }

        String acodec() {
            return Str.lower(acodecRaw);
        }

        String note() {
            return Str.lower(formatNote);
        }

        /**
         * yt-dlp's convention: the string "none" means "definitely absent", an empty value
         * means "unknown", which yt-dlp itself treats as present.
         */
        boolean hasVideo() {
            String v = vcodec();
            if (v.equals("none")) return false;
            if (v.length() > 0 || width > 0 || height > 0) return true;
            return !AUDIO_ONLY_EXTS.contains(Str.lower(ext));
        }

        boolean hasAudio() {
            return !acodec().equals("none");
        }

        boolean drm() {
            return Boolean.TRUE.equals(hasDrm) || "maybe".equals(hasDrm);
        }

        boolean isUnwanted() {
            String n = note();
            String id = Str.lower(id());
            return ext.equals("mhtml") || protocol.equals("mhtml") || n.contains("storyboard")
                    || drm() || n.contains("premium")
                    || id.endsWith("-sr") || n.contains("ai-upscaled")
                    || n.contains("missing pot") || n.contains("unplayable")
                    || vcodec().contains("bytevc2");
        }

        boolean watermarked() {
            return note().contains("watermark");
        }

        /** The best known size of the format in bytes (0 if unknown). */
        double size(double duration) {
            if (filesize > 0) return filesize;
            if (filesizeApprox > 0) return filesizeApprox;
            if (tbr > 0 && duration > 0) return tbr * 1000 / 8 * duration;
            return 0;
        }
    }

    static final Set<String> AUDIO_ONLY_EXTS = new HashSet<String>(Arrays.asList(
            "m4a", "mp3", "opus", "ogg", "oga", "aac", "wav", "weba", "flac", "mka"));

    static final class Info {
        Map<String, Object> raw;
        String type, id, title, fullTitle, description, uploader, channel, thumbnail, liveStatus, webpageUrl, extractorKey;
        double duration, playlistIndex;
        boolean isLive;
        List<Thumb> thumbnails = new ArrayList<Thumb>();
        List<Fmt> formats = new ArrayList<Fmt>();
        List<Object> entries = new ArrayList<Object>();
        boolean hasEntries;

        static Info from(Map<String, Object> m) {
            Info i = new Info();
            i.raw = m;
            i.type = J.str(m, "_type");
            i.id = J.str(m, "id");
            i.title = J.str(m, "title");
            i.fullTitle = J.str(m, "fulltitle");
            i.description = J.str(m, "description");
            i.uploader = J.str(m, "uploader");
            i.channel = J.str(m, "channel");
            i.duration = J.num(m, "duration");
            i.thumbnail = J.str(m, "thumbnail");
            for (Object t : J.arr(m, "thumbnails")) {
                Map<String, Object> tm = J.asObj(t);
                if (tm == null) continue;
                Thumb th = new Thumb();
                th.id = J.str(tm, "id");
                th.url = J.str(tm, "url");
                th.width = J.num(tm, "width");
                th.height = J.num(tm, "height");
                i.thumbnails.add(th);
            }
            i.liveStatus = J.str(m, "live_status");
            i.isLive = J.bool(m, "is_live");
            i.webpageUrl = J.str(m, "webpage_url");
            i.extractorKey = J.str(m, "extractor_key");
            i.playlistIndex = J.num(m, "playlist_index");
            for (Object f : J.arr(m, "formats")) {
                Map<String, Object> fm = J.asObj(f);
                if (fm != null) i.formats.add(Fmt.from(fm));
            }
            i.entries = J.arr(m, "entries");
            i.hasEntries = !i.entries.isEmpty();
            return i;
        }
    }

    static int protoRank(String p) {
        if (p.equals("https") || p.equals("http") || p.length() == 0) return 0;
        if (p.startsWith("m3u8")) return 1;
        return 2;
    }

    /**
     * Codec preference for playing on any phone and sharing on WhatsApp: H.264 first, then
     * VP9, AV1, HEVC, HDR, and unknown codecs last.
     */
    static int videoCodecRank(Fmt f) {
        String dr = Str.upper(f.dynamicRange);
        String v = f.vcodec();
        if ((dr.length() > 0 && !dr.equals("SDR")) || v.startsWith("vp09.02") || f.note().contains("hdr")) return 4;
        if (v.startsWith("avc") || v.startsWith("h264")) return 0;
        if (v.startsWith("vp9") || v.startsWith("vp09")) return 1;
        if (v.startsWith("av01") || v.startsWith("av1")) return 2;
        if (v.startsWith("h265") || v.startsWith("hevc") || v.startsWith("hvc1") || v.startsWith("hev1")
                || v.startsWith("bytevc1")) return 3;
        return 5;
    }

    static String compatNote(int rank) {
        switch (rank) {
            case 1:
            case 2:
                return I18n.t("قد لا يعمل على الهواتف والشاشات القديمة", "May not play on older phones and TVs");
            case 3:
                return I18n.t("قد لا يعمل على بعض الأجهزة", "May not play on some devices");
            case 4:
                return I18n.t("فيديو HDR، قد تبدو ألوانه غريبة على الشاشات العادية", "HDR video: colors may look odd on regular screens");
            default:
                return "";
        }
    }

    /** What a quality is good for, in the current language (descId: q720, a192, vbest…). */
    static String descText(String id) {
        boolean en = I18n.english();
        if (id.equals("q4320")) return en ? "Highest possible quality, huge file" : "أعلى جودة ممكنة، والحجم ضخم جدًا";
        if (id.equals("q2160")) return en ? "Top quality, very large file" : "جودة فائقة، والحجم كبير جدًا";
        if (id.equals("q1440")) return en ? "Very high quality, large file" : "جودة عالية جدًا، والحجم كبير";
        if (id.equals("q1080")) return en ? "High quality for big screens" : "جودة عالية للشاشات الكبيرة";
        if (id.equals("q720")) return en ? "Good quality, reasonable size" : "جودة جيدة بحجم مناسب";
        if (id.equals("q480")) return en ? "Medium quality, smaller file" : "جودة متوسطة بحجم أصغر";
        if (id.equals("q360")) return en ? "Standard quality, fine for phones" : "جودة عادية تناسب الهاتف";
        if (id.equals("q240")) return en ? "Low quality, small file" : "جودة منخفضة بحجم صغير";
        if (id.equals("qlow")) return en ? "Lowest quality, smallest file" : "أقل جودة وأصغر حجم";
        if (id.equals("vbest")) return en ? "Best available quality" : "أفضل جودة متاحة";
        if (id.equals("vhd")) return en ? "High quality" : "جودة عالية";
        if (id.equals("vsd")) return en ? "Standard quality, smaller file" : "جودة عادية بحجم أصغر";
        if (id.equals("a320")) return en ? "Best sound, larger file" : "أعلى جودة للصوت، والحجم أكبر";
        if (id.equals("a192")) return en ? "Good quality, reasonable size" : "جودة جيدة بحجم مناسب";
        if (id.equals("a128")) return en ? "Small file that saves space" : "حجم صغير يوفّر المساحة";
        return "";
    }

    static int audioCodecRank(Fmt f) {
        String a = f.acodec();
        if (a.startsWith("mp4a") || a.startsWith("aac") || f.ext.equals("m4a")) return 0;
        if (a.startsWith("opus")) return 1;
        return 2;
    }

    /** Mirrors yt-dlp: original track 10, default 5, other tracks -1, descriptive audio -10. */
    static double langPref(Fmt f) {
        return f.languagePreference == null ? -1 : f.languagePreference;
    }

    static boolean isDRC(Fmt f) {
        return Str.lower(f.id()).contains("drc") || f.formatNote.contains("DRC");
    }

    private static final Pattern NOTE_HEIGHT = Pattern.compile("([0-9]{3,4})p");
    static final int[] QUALITY_LADDER = {144, 240, 360, 480, 720, 1080, 1440, 2160, 4320};

    /**
     * The "720" in "720p" for a video format, following the site's own label when present.
     * For vertical videos (TikTok, Reels, Shorts) the short side counts.
     */
    static int qualityClass(Fmt f) {
        Matcher m = NOTE_HEIGHT.matcher(f.formatNote);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 100) return snapToLadder(n);
        }
        double w = f.width, h = f.height;
        if (w > 0 && h > 0) return snapToLadder((int) Math.min(w, h));
        if (h > 0) return snapToLadder((int) h);
        return 0;
    }

    static int snapToLadder(int n) {
        // Anything far below 144p is not worth offering.
        if (n < 100) return 0;
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int l : QUALITY_LADDER) {
            double d = Math.abs((double) (n - l));
            if (d < bestDiff) {
                best = l;
                bestDiff = d;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------
    // What the UI shows
    // ---------------------------------------------------------------------------------

    public static final class VideoMeta implements Json.Value {
        public String id = "", title = "", channel = "", durationText = "", thumbnail = "", site = "", siteName = "";
        public double duration;

        @Override
        public Object toJson() {
            Map<String, Object> m = Json.obj("id", id, "title", title, "channel", channel);
            m.put("duration", duration);
            m.put("durationText", durationText);
            m.put("thumbnail", thumbnail);
            m.put("site", site);
            // Written when shown, so it follows the language.
            m.put("siteName", site.length() > 0 ? Sites.name(site) : siteName);
            return m;
        }
    }

    /** One file yt-dlp will download for an option (video, audio or both in one). */
    public static final class DlPart {
        public final String formatId;
        public final long bytes;
        public final boolean isAudio;

        DlPart(String formatId, long bytes, boolean isAudio) {
            this.formatId = formatId;
            this.bytes = bytes;
            this.isAudio = isAudio;
        }
    }

    public static final class QualityOption implements Json.Value {
        public String key = "", kind = "", label = "", tag = "", desc = "", sizeText = "", compatNote = "";
        /** desc and compatNote as codes, so they are written in the language in use when shown. */
        public String descId = "";
        public int compatRank;
        public int height;
        public long sizeBytes;
        public boolean recommended;
        // Not sent to the page:
        public String formatSpec = "", sortSpec = "", audioQuality = "";
        public List<DlPart> parts = new ArrayList<DlPart>();

        @Override
        public Object toJson() {
            String d = descId.length() > 0 ? descText(descId) : desc;
            Map<String, Object> m = Json.obj("key", key, "kind", kind, "label", labelText(), "tag", tag, "desc", d);
            m.put("height", height);
            m.put("sizeBytes", sizeBytes);
            m.put("sizeText", sizeBytes > 0 ? Text.humanSize(sizeBytes) : sizeText);
            m.put("recommended", recommended);
            String note = compatRank > 0 ? compatNote(compatRank) : compatNote;
            if (note.length() > 0) m.put("compatNote", note);
            return m;
        }

        /** The label shown ("720p", "4K", "192k"; "Video" for a file of unknown size of picture). */
        public String labelText() {
            return key.equals("vbest") ? I18n.t("فيديو", "Video") : label;
        }
    }

    /** One video of a post. Most links have one; a post on X or an Instagram carousel can have several. */
    public static final class Item implements Json.Value {
        public int index; // position inside the post (1-based); 0 for a plain video
        public VideoMeta meta;
        public List<QualityOption> options = new ArrayList<QualityOption>();
        public String selected = "";
        /** What yt-dlp printed for this video, saved for the download step. */
        public String raw = "";

        @Override
        public Object toJson() {
            Map<String, Object> m = Json.obj("index", index, "meta", meta);
            m.put("options", options);
            m.put("selected", selected);
            return m;
        }

        public QualityOption option(String key) {
            for (QualityOption o : options) if (o.key.equals(key)) return o;
            return null;
        }
    }

    /** A problem with the video itself (not a crash), shown to the user as-is. */
    public static final class InfoException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;

        public InfoException(String code) {
            super("info: " + code);
            this.code = code;
        }
    }

    public static final class Post {
        public final String site;
        public final List<Item> items;

        Post(String site, List<Item> items) {
            this.site = site;
            this.items = items;
        }
    }

    static String pickThumbnail(Info info, String site) {
        if (Sites.YOUTUBE.equals(site)) {
            String best = "";
            double bestW = Double.MAX_VALUE;
            for (Thumb t : info.thumbnails) {
                double w = t.width, h = t.height;
                if (t.url.length() == 0 || w <= 0 || h <= 0 || t.url.contains(".webp")) continue;
                double ratio = w / h;
                if (w >= 320 && ratio > 1.7 && ratio < 1.8 && w < bestW) {
                    best = t.url;
                    bestW = w;
                }
            }
            if (best.length() > 0) return best;
            if (Sites.VIDEO_ID.matcher(info.id).matches()) return "https://i.ytimg.com/vi/" + info.id + "/mqdefault.jpg";
            return info.thumbnail;
        }
        // Other sites: the smallest picture that is still sharp enough, never an animated cover.
        String best = "";
        double bestW = Double.MAX_VALUE;
        for (Thumb t : info.thumbnails) {
            if (t.url.length() == 0 || Str.lower(t.id).contains("dynamic")) continue;
            if (t.width >= 320 && t.width < bestW) {
                best = t.url;
                bestW = t.width;
            }
        }
        if (best.length() > 0) return best;
        if (info.thumbnail.length() > 0) return info.thumbnail;
        for (int i = info.thumbnails.size() - 1; i >= 0; i--) {
            Thumb t = info.thumbnails.get(i);
            if (t.url.length() > 0 && !Str.lower(t.id).contains("dynamic")) return t.url;
        }
        return "";
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\t\\n\\f\\r ]+");
    // not "#2", the number of a video in a post
    private static final Pattern HASHTAG = Pattern.compile("(?:^|[\\t\\n\\f\\r ])#[^\\t\\n\\f\\r #0-9][^\\t\\n\\f\\r #]*");
    // UNIX_LINES: like Go, only "\n" ends a line for "." and "$".
    private static final Pattern BY_USER = Pattern.compile("^(?:Video|Post|Reel) by (.+)$", Pattern.UNIX_LINES);
    private static final Pattern POST_NUMBER = Pattern.compile("[\\t\\n\\f\\r ]+#[0-9]+$", Pattern.UNIX_LINES);
    // (?![A-Za-z0-9_]) is Go's ASCII-only \b at the end of a word.
    private static final Pattern FB_COUNTS = Pattern.compile(
            "[0-9][0-9.,]*[\\t\\n\\f\\r ]*[KMB]?[\\t\\n\\f\\r ]+(?:views|reactions|comments|shares|plays)(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE);

    /**
     * Makes social-media captions usable as a title and a file name: no links, no wall of
     * hashtags, no "1.2K views | Facebook" noise.
     */
    static String cleanTitle(String site, String title, String uploader) {
        String t = Str.trimSpace(title);
        if (Sites.YOUTUBE.equals(site)) return t;
        t = URL_IN_TEXT.matcher(t).replaceAll(" ");
        Matcher by = BY_USER.matcher(Str.trimSpace(t));
        if (by.matches()) return Sites.name(site) + " - " + Str.trimSpace(by.group(1));
        if (Sites.FACEBOOK.equals(site)) {
            List<String> keep = new ArrayList<String>();
            for (String part : t.split("\\|", -1)) {
                String p = Str.trimSpace(part);
                if (p.length() == 0 || p.equalsIgnoreCase("facebook") || p.startsWith("By ") || FB_COUNTS.matcher(p).find()) {
                    continue;
                }
                keep.add(p);
            }
            t = Str.join(" - ", keep);
        }
        String noTags = HASHTAG.matcher(t).replaceAll(" ");
        if (Str.runeLen(Str.trimSpace(noTags)) >= 3) t = noTags;
        // "#2" is how yt-dlp numbers the videos of one post; the app shows its own numbers.
        t = POST_NUMBER.matcher(t).replaceAll("");
        t = Str.squash(t);
        t = Str.trim(t, " -|·•…");
        if (Str.runeLen(t) < 2) {
            if (uploader.length() > 0) return Sites.name(site) + " - " + uploader;
            return I18n.t("فيديو من " + Sites.name(site), Sites.name(site) + " video");
        }
        return t;
    }

    /** Whether a caption says something besides links and hashtags. */
    static boolean hasWords(String s) {
        s = URL_IN_TEXT.matcher(s).replaceAll(" ");
        s = HASHTAG.matcher(s).replaceAll(" ");
        return Str.runeLen(Str.squash(s)) >= 3;
    }

    /** The first line of a caption, cut at a word if it is long. */
    static String firstLine(String s, int max) {
        s = Str.trimSpace(s);
        int i = Str.indexAny(s, "\r\n");
        if (i >= 0) s = Str.trimSpace(s.substring(0, i));
        if (Str.runeLen(s) <= max) return s;
        String cut = Str.runePrefix(s, max);
        int sp = cut.lastIndexOf(' ');
        // Same rule as the Go version, which compares the space's byte offset with max/2.
        if (sp >= 0 && utf8Len(cut.substring(0, sp)) > max / 2) cut = cut.substring(0, sp);
        return Str.trimSpace(cut) + "…";
    }

    private static int utf8Len(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
        }
        return n;
    }

    static VideoMeta buildMeta(Info info, String site) {
        VideoMeta meta = new VideoMeta();
        meta.id = info.id;
        meta.site = site;
        meta.siteName = Sites.name(site);
        String channel = info.channel;
        if (channel.length() == 0) channel = info.uploader;
        meta.channel = channel;
        String title = info.title;
        if (title.length() == 0) title = info.fullTitle;
        // Instagram calls every video "Video by <user>"; the caption says much more.
        if (Sites.INSTAGRAM.equals(site) && BY_USER.matcher(Str.trimSpace(title)).matches()) {
            String c = firstLine(info.description, 90);
            if (hasWords(c)) title = c;
        }
        meta.title = cleanTitle(site, title, channel);
        // X puts the author's name in front of the post's text; the name is already shown under it.
        if (Sites.X.equals(site) && channel.length() > 0) {
            String rest = Str.trimPrefix(meta.title, channel + " - ");
            if (!rest.equals(meta.title) && Str.runeLen(rest) >= 2) meta.title = rest;
        }
        meta.duration = info.duration;
        meta.durationText = Text.humanDuration(meta.duration);
        meta.thumbnail = pickThumbnail(info, site);
        return meta;
    }

    // ---------------------------------------------------------------------------------
    // What the cut screen plays before downloading a part
    // ---------------------------------------------------------------------------------

    /**
     * The files the page's player can play for a video: one with picture and sound ("av"),
     * picture and sound apart ("split", played together), or sound only ("audio"); "" when
     * the site gives only streams in pieces (the part is then chosen by its times alone).
     */
    public static final class Preview {
        public String mode = "";
        public String videoUrl = "", audioUrl = "", videoType = "", audioType = "";
        public Map<String, String> videoHeaders = new HashMap<String, String>(), audioHeaders = new HashMap<String, String>();
    }

    private static final Set<String> PLAYABLE_EXTS = new HashSet<String>(Arrays.asList("mp4", "m4a", "m4v", "webm", "mov", "mp3"));

    /** A whole file the player can ask for in ranges (not a stream in pieces, not protected). */
    static boolean playable(Map<String, Object> fm, Fmt f) {
        String url = J.str(fm, "url");
        String proto = Str.lower(f.protocol);
        if (!url.startsWith("http")) return false;
        if (!(proto.equals("https") || proto.equals("http") || proto.length() == 0)) return false;
        return !f.drm() && !f.isUnwanted() && PLAYABLE_EXTS.contains(Str.lower(f.ext));
    }

    /** 3 for H.264 (plays everywhere), 2 VP9, 1 AV1, 0 others (H.265: not on every phone). */
    static int previewCodec(Fmt f) {
        String v = f.vcodec();
        if (v.startsWith("avc") || v.startsWith("h264")) return 3;
        if (v.startsWith("vp9") || v.startsWith("vp09")) return 2;
        if (v.startsWith("av01")) return 1;
        if (v.length() == 0) return 2; // unknown: most likely H.264 in an mp4
        return 0;
    }

    /** Better for a preview: 360p to 720p, an easy codec, close to 480p (quick to start). */
    private static double videoScore(Fmt f) {
        double h = f.height > 0 ? f.height : 360;
        int codec = previewCodec(f);
        if (codec == 0) return -1e9;
        return (h >= 300 && h <= 720 ? 1e6 : 0) + codec * 1e4 - Math.abs(h - 480);
    }

    private static double audioScore(Fmt f) {
        String a = f.acodec();
        int codec = a.startsWith("mp4a") || a.startsWith("aac") ? 2 : a.startsWith("opus") || a.startsWith("mp3") ? 1 : 0;
        double abr = f.abr > 0 ? f.abr : f.tbr > 0 ? f.tbr : 128;
        return (isDRC(f) ? 0 : 1e6) + codec * 1e4 - Math.abs(abr - 128);
    }

    /** The headers yt-dlp would send for a format, with the site's cookies for it. */
    static Map<String, String> headersOf(Map<String, Object> fm, Map<String, Object> info) {
        Map<String, String> h = new HashMap<String, String>();
        Map<String, Object> src = J.obj(fm, "http_headers");
        if (src == null) src = J.obj(info, "http_headers");
        if (src != null) {
            for (Map.Entry<String, Object> e : src.entrySet()) {
                if (e.getValue() instanceof String) h.put(e.getKey(), (String) e.getValue());
            }
        }
        String cookies = J.str(fm, "cookies");
        if (cookies.length() == 0) cookies = J.str(info, "cookies");
        String c = cookieHeader(cookies);
        if (c.length() > 0) h.put("Cookie", c);
        return h;
    }

    private static final Set<String> COOKIE_ATTRS = new HashSet<String>(Arrays.asList(
            "domain", "path", "expires", "max-age", "secure", "httponly", "samesite", "version", "comment"));

    /** yt-dlp's "cookies" of a format ("a=1; Domain=.x.com; Path=/; b=2") as a Cookie header ("a=1; b=2"). */
    static String cookieHeader(String cookies) {
        StringBuilder sb = new StringBuilder();
        for (String part : cookies.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            String name = (eq < 0 ? p : p.substring(0, eq)).trim();
            if (eq <= 0 || COOKIE_ATTRS.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(name).append('=').append(p.substring(eq + 1).trim());
        }
        return sb.toString();
    }

    private static String typeOf(Fmt f, boolean audio) {
        String e = Str.lower(f.ext);
        if (e.equals("webm")) return audio ? "audio/webm" : "video/webm";
        if (e.equals("mp3")) return "audio/mpeg";
        if (e.equals("m4a")) return "audio/mp4";
        if (e.equals("mov")) return "video/quicktime";
        return audio ? "audio/mp4" : "video/mp4";
    }

    /** What to play for the video whose yt-dlp info this is. */
    public static Preview preview(Map<String, Object> info) {
        Preview p = new Preview();
        List<Object> fl = J.arr(info, "formats");
        Map<String, Object> bestAV = null, bestV = null, bestA = null;
        Fmt fAV = null, fV = null, fA = null;
        double sAV = -1e18, sV = -1e18, sA = -1e18;
        if (fl != null) {
            for (Object o : fl) {
                Map<String, Object> fm = J.asObj(o);
                if (fm == null) continue;
                Fmt f = Fmt.from(fm);
                if (!playable(fm, f)) continue;
                boolean v = f.hasVideo(), a = f.hasAudio();
                if (v && a) {
                    double s = videoScore(f);
                    if (s > sAV) { sAV = s; bestAV = fm; fAV = f; }
                } else if (v) {
                    double s = videoScore(f);
                    if (s > sV) { sV = s; bestV = fm; fV = f; }
                } else if (a) {
                    double s = audioScore(f);
                    if (s > sA) { sA = s; bestA = fm; fA = f; }
                }
            }
        }
        // A video with no list of formats: its own address is the file.
        if (bestAV == null && (fl == null || fl.isEmpty()) && J.str(info, "url").startsWith("http")) {
            Fmt f = Fmt.from(info);
            if (playable(info, f)) {
                bestAV = info;
                fAV = f;
                sAV = videoScore(f);
            }
        }
        if (bestAV != null && sAV > -1e8) {
            p.mode = "av";
            p.videoUrl = J.str(bestAV, "url");
            p.videoHeaders = headersOf(bestAV, info);
            p.videoType = typeOf(fAV, false);
        } else if (bestV != null && sV > -1e8 && bestA != null) {
            p.mode = "split";
            p.videoUrl = J.str(bestV, "url");
            p.videoHeaders = headersOf(bestV, info);
            p.videoType = typeOf(fV, false);
        } else if (bestA != null) {
            p.mode = "audio";
        }
        if (bestA != null && !p.mode.equals("av")) {
            p.audioUrl = J.str(bestA, "url");
            p.audioHeaders = headersOf(bestA, info);
            p.audioType = typeOf(fA, true);
        }
        return p;
    }

    static String recommendedKey(List<QualityOption> opts, String kind) {
        for (QualityOption o : opts) if (o.recommended && (kind.length() == 0 || o.kind.equals(kind))) return o.key;
        for (QualityOption o : opts) if (kind.length() == 0 || o.kind.equals(kind)) return o.key;
        return "";
    }

    /** Reads yt-dlp's answer for a link: one video, or several for a post that has more. */
    public static Post buildPost(String data, String linkSite) throws InfoException, Json.ParseError {
        Object parsed = Json.parse(data);
        if (!(parsed instanceof Json.Obj)) throw new Json.ParseError("not an object", 0);
        return buildPost((Json.Obj) parsed, linkSite);
    }

    /** Same, from JSON already parsed (its raw() text is kept for the download step). */
    public static Post buildPost(Json.Obj m, String linkSite) throws InfoException {
        String data = m.raw();
        Info info = Info.from(m);
        String site = Sites.fromExtractor(info.extractorKey, linkSite);
        boolean isList = info.type.equals("playlist") || info.type.equals("multi_video") || info.hasEntries;
        if (!isList) {
            Item it = new Item();
            Object[] mo = buildOptions(info, site);
            it.meta = (VideoMeta) mo[0];
            @SuppressWarnings("unchecked")
            List<QualityOption> opts = (List<QualityOption>) mo[1];
            it.options = opts;
            it.selected = recommendedKey(opts, "");
            it.raw = data;
            return new Post(site, Collections.singletonList(it));
        }
        if (Sites.YOUTUBE.equals(site)) throw new InfoException("playlist");
        List<Item> items = new ArrayList<Item>();
        int refs = 0, live = 0;
        for (int i = 0; i < info.entries.size(); i++) {
            Object raw = info.entries.get(i);
            Map<String, Object> em = J.asObj(raw);
            if (em == null) continue; // a photo of the post, or an entry that failed
            Info e = Info.from(em);
            if (e.type.equals("url") || e.type.equals("url_transparent")) {
                refs++;
                continue;
            }
            Object[] mo;
            try {
                mo = buildOptions(e, Sites.fromExtractor(e.extractorKey, site));
            } catch (InfoException ie) {
                if (ie.code.equals("live")) live++;
                continue;
            }
            @SuppressWarnings("unchecked")
            List<QualityOption> opts = (List<QualityOption>) mo[1];
            if (opts.isEmpty()) continue;
            int idx = (int) e.playlistIndex;
            if (idx <= 0) idx = i + 1;
            Item it = new Item();
            it.index = idx;
            it.meta = (VideoMeta) mo[0];
            it.options = opts;
            it.selected = recommendedKey(opts, "");
            it.raw = em instanceof Json.Obj ? ((Json.Obj) em).raw() : Json.write(em);
            items.add(it);
            if (items.size() >= 30) break;
        }
        if (items.isEmpty()) {
            if (live > 0) throw new InfoException("live");
            if (refs > 0) throw new InfoException("profile");
            throw new InfoException("novideo");
        }
        return new Post(site, items);
    }

    private interface Keep {
        boolean keep(Fmt f);
    }

    private static List<Fmt> filter(List<Fmt> in, Keep k) {
        List<Fmt> out = new ArrayList<Fmt>();
        for (Fmt f : in) if (k.keep(f)) out.add(f);
        return out;
    }

    private static final Keep HAS_AUDIO = new Keep() {
        @Override
        public boolean keep(Fmt f) {
            return f.hasAudio();
        }
    };

    /** Returns {VideoMeta, List<QualityOption>}. */
    static Object[] buildOptions(Info info, String site) throws InfoException {
        VideoMeta meta = buildMeta(info, site);
        final double duration = meta.duration;

        if (info.liveStatus.equals("is_live")) throw new InfoException("live");
        if (info.liveStatus.equals("is_upcoming")) throw new InfoException("upcoming");
        if (info.isLive) throw new InfoException("live");

        List<Fmt> audios = new ArrayList<Fmt>(), videos = new ArrayList<Fmt>();
        for (Fmt f : info.formats) {
            if (f.formatId.length() == 0 || f.isUnwanted()) continue;
            if (f.hasVideo()) videos.add(f);
            else if (f.hasAudio()) audios.add(f);
        }
        // TikTok's watermarked copy only if there is nothing else.
        List<Fmt> clean = filter(videos, new Keep() {
            @Override
            public boolean keep(Fmt f) {
                return !f.watermarked();
            }
        });
        if (!clean.isEmpty()) videos = clean;
        // TikTok's separate "music" track is the song used, not necessarily what the video
        // sounds like; its videos always carry their own sound.
        if (Sites.TIKTOK.equals(site) && !filter(videos, HAS_AUDIO).isEmpty()) audios = new ArrayList<Fmt>();

        // Best audio: original language track, not DRC, plain https, AAC preferred (plays everywhere in MP4).
        Collections.sort(audios, new Comparator<Fmt>() {
            @Override
            public int compare(Fmt a, Fmt b) {
                double la = langPref(a), lb = langPref(b);
                if (la != lb) return la > lb ? -1 : 1;
                boolean da = isDRC(a), db = isDRC(b);
                if (da != db) return da ? 1 : -1;
                int pa = protoRank(a.protocol), pb = protoRank(b.protocol);
                if (pa != pb) return pa < pb ? -1 : 1;
                int ca = audioCodecRank(a), cb = audioCodecRank(b);
                if (ca != cb) return ca < cb ? -1 : 1;
                double ra = Math.max(a.abr, a.tbr), rb = Math.max(b.abr, b.tbr);
                if (ra != rb) return ra > rb ? -1 : 1;
                return 0;
            }
        });
        Fmt bestAudio = audios.isEmpty() ? null : audios.get(0);

        // A video with no sound at all (a GIF on X, for example): offer the picture alone and no MP3.
        boolean silent = bestAudio == null && filter(videos, HAS_AUDIO).isEmpty();
        if (Sites.X.equals(site) && bestAudio == null && duration == 0 && !videos.isEmpty()
                && filter(videos, new Keep() {
                    @Override
                    public boolean keep(Fmt f) {
                        return !f.id().equals("http");
                    }
                }).isEmpty()) {
            silent = true; // X serves GIFs as one soundless MP4 named "http"
        }

        Map<Integer, List<Fmt>> byQ = new HashMap<Integer, List<Fmt>>();
        List<Fmt> unknown = new ArrayList<Fmt>();
        for (Fmt f : videos) {
            int q = qualityClass(f);
            if (q > 0) {
                List<Fmt> l = byQ.get(q);
                if (l == null) {
                    l = new ArrayList<Fmt>();
                    byQ.put(q, l);
                }
                l.add(f);
            } else {
                unknown.add(f);
            }
        }
        List<Integer> qs = new ArrayList<Integer>(byQ.keySet());
        Collections.sort(qs, Collections.<Integer>reverseOrder());

        List<QualityOption> opts = new ArrayList<QualityOption>();
        for (int q : qs) {
            List<Fmt> cands = usableVideos(byQ.get(q), bestAudio, silent);
            if (cands.isEmpty()) continue;
            sortVideos(cands);
            QualityOption o = videoOption(cands.get(0), bestAudio, silent, duration);
            o.key = "v" + q;
            o.height = q;
            String[] w = qualityWords(q);
            o.label = w[0];
            o.tag = w[1];
            o.descId = w[2];
            o.desc = descText(o.descId);
            o.sortSpec = "res:" + q + ",vcodec:h264,acodec:aac";
            opts.add(o);
        }

        // Formats without a known size of picture (Facebook's "hd" and "sd" files, or a single
        // unnamed file) are only offered when nothing better is known.
        if (opts.isEmpty()) {
            List<Fmt> cands = usableVideos(unknown, bestAudio, silent);
            sortVideos(cands);
            Fmt hd = null, sd = null, other = null;
            for (Fmt f : cands) {
                String id = Str.lower(f.id());
                if (id.equals("hd")) {
                    if (hd == null) hd = f;
                } else if (id.equals("sd")) {
                    if (sd == null) sd = f;
                } else if (other == null) {
                    other = f;
                }
            }
            if (hd == null && sd == null && other != null) {
                QualityOption o = videoOption(other, bestAudio, silent, duration);
                o.key = "vbest";
                o.label = o.labelText();
                o.descId = "vbest";
                o.desc = descText(o.descId);
                opts.add(o);
            }
            if (hd != null) {
                QualityOption o = videoOption(hd, bestAudio, silent, duration);
                o.key = "vhd";
                o.label = "HD";
                o.tag = "";
                o.descId = "vhd";
                o.desc = descText(o.descId);
                opts.add(o);
            }
            if (sd != null) {
                QualityOption o = videoOption(sd, bestAudio, silent, duration);
                o.key = "vsd";
                o.label = "SD";
                o.tag = "";
                o.descId = "vsd";
                o.desc = descText(o.descId);
                opts.add(o);
            }
        }

        if (opts.isEmpty() && (bestAudio == null || silent)) throw new InfoException("noformats");

        // Recommended: 720p, otherwise the closest quality below it, otherwise the lowest available.
        int rec = -1;
        for (int i = 0; i < opts.size(); i++) {
            QualityOption o = opts.get(i);
            if (o.height > 0 && o.height <= 720) {
                rec = i;
                break;
            }
        }
        if (rec == -1 && !opts.isEmpty()) {
            rec = opts.size() - 1;
            if (opts.get(0).height == 0) rec = 0; // HD before SD
        }
        if (rec >= 0) opts.get(rec).recommended = true;

        if (!silent) opts.addAll(audioOptions(opts, rec, bestAudio, duration));
        return new Object[]{meta, opts};
    }

    /**
     * The formats that give a complete file: with sound, or without sound when a separate
     * sound track can be added (or when the video has no sound at all).
     */
    static List<Fmt> usableVideos(List<Fmt> cands, Fmt bestAudio, boolean silent) {
        if (bestAudio != null || silent) return new ArrayList<Fmt>(cands);
        return filter(cands, HAS_AUDIO);
    }

    private static int group(Fmt f) {
        boolean combined = f.hasAudio();
        int p = protoRank(f.protocol);
        if (!combined && p == 0) return 0; // separate video over https (merged with audio afterwards)
        if (combined && p == 0) return 1;  // ready-made video+audio file (e.g. 360p "18")
        if (!combined) return 2;
        return 3;                          // pre-merged HLS
    }

    static void sortVideos(List<Fmt> cands) {
        Collections.sort(cands, new Comparator<Fmt>() {
            @Override
            public int compare(Fmt a, Fmt b) {
                // A file whose codec is unknown might be one some phones cannot play (X serves some as HEVC).
                boolean ka = a.vcodec().length() > 0, kb = b.vcodec().length() > 0;
                if (ka != kb) return ka ? -1 : 1;
                int ga = group(a), gb = group(b);
                if (ga != gb) return ga < gb ? -1 : 1;
                boolean da = a.note().contains("damaged"), db = b.note().contains("damaged");
                if (da != db) return da ? 1 : -1;
                int ca = videoCodecRank(a), cb = videoCodecRank(b);
                if (ca != cb) return ca < cb ? -1 : 1;
                if (a.fps != b.fps) return a.fps > b.fps ? -1 : 1;
                if (a.tbr != b.tbr) return a.tbr > b.tbr ? -1 : 1;
                return 0;
            }
        });
    }

    static QualityOption videoOption(Fmt v, Fmt bestAudio, boolean silent, double duration) {
        QualityOption o = new QualityOption();
        o.kind = "video";
        long vSize = (long) v.size(duration);
        if (v.hasAudio() || silent) {
            o.formatSpec = v.id() + "/b";
            if (silent) o.formatSpec = v.id() + "/bv*/b";
            o.sizeBytes = vSize;
            o.parts.add(new DlPart(v.id(), vSize, false));
        } else {
            long aSize = (long) bestAudio.size(duration);
            o.formatSpec = v.id() + "+" + bestAudio.id() + "/bv*+ba/b";
            if (vSize > 0 && aSize > 0) o.sizeBytes = vSize + aSize;
            o.parts.add(new DlPart(v.id(), vSize, false));
            o.parts.add(new DlPart(bestAudio.id(), aSize, true));
        }
        o.sizeText = Text.humanSize(o.sizeBytes);
        o.compatRank = videoCodecRank(v);
        if (o.compatRank > 4) o.compatRank = 0;
        o.compatNote = compatNote(o.compatRank);
        return o;
    }

    /**
     * MP3 in three sizes. The sound comes from the separate sound track when there is one,
     * otherwise from the recommended video file.
     */
    static List<QualityOption> audioOptions(List<QualityOption> videoOpts, int rec, Fmt bestAudio, double duration) {
        String src = "";
        long srcSize = 0;
        String spec = "ba/b";
        if (bestAudio != null) {
            src = bestAudio.id();
            srcSize = (long) bestAudio.size(duration);
            spec = src + "/ba/b";
        } else if (rec >= 0 && videoOpts.get(rec).parts.size() == 1) {
            src = videoOpts.get(rec).parts.get(0).formatId;
            srcSize = videoOpts.get(rec).parts.get(0).bytes;
            spec = src + "/b";
        }
        int[] kbps = {320, 192, 128};
        List<QualityOption> out = new ArrayList<QualityOption>();
        for (int i = 0; i < kbps.length; i++) {
            QualityOption o = new QualityOption();
            o.key = "mp3-" + kbps[i];
            o.kind = "audio";
            o.label = kbps[i] + "k";
            o.tag = "MP3";
            o.descId = "a" + kbps[i];
            o.desc = descText(o.descId);
            o.recommended = kbps[i] == 192;
            o.formatSpec = spec;
            o.sortSpec = "acodec:aac";
            o.audioQuality = kbps[i] + "K";
            o.parts.add(new DlPart(src, srcSize, true));
            if (duration > 0) o.sizeBytes = (long) (duration * kbps[i] * 1000 / 8);
            o.sizeText = Text.humanSize(o.sizeBytes);
            out.add(o);
        }
        return out;
    }

    /** {label, tag, descId} */
    static String[] qualityWords(int q) {
        if (q >= 4320) return new String[]{"8K", q + "p", "q4320"};
        if (q >= 2160) return new String[]{"4K", "2160p", "q2160"};
        if (q >= 1440) return new String[]{"2K", "1440p", "q1440"};
        if (q >= 1080) return new String[]{"1080p", "Full HD", "q1080"};
        if (q >= 720) return new String[]{"720p", "HD", "q720"};
        if (q >= 480) return new String[]{"480p", "", "q480"};
        if (q >= 360) return new String[]{"360p", "", "q360"};
        if (q >= 240) return new String[]{"240p", "", "q240"};
        return new String[]{q + "p", "", "qlow"};
    }
}
