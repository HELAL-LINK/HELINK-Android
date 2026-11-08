package com.alihelal.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The supported sites and how links to them are recognised and cleaned up. */
public final class Sites {
    private Sites() {}

    public static final String YOUTUBE = "youtube";
    public static final String FACEBOOK = "facebook";
    public static final String INSTAGRAM = "instagram";
    public static final String TIKTOK = "tiktok";
    public static final String X = "x";

    private static final Map<String, String> NAMES = new HashMap<String, String>();
    private static final Map<String, String> NAMES_EN = new HashMap<String, String>();

    static {
        // The names people use for each site, in Arabic and in English.
        NAMES_EN.put(YOUTUBE, "YouTube");
        NAMES_EN.put(FACEBOOK, "Facebook");
        NAMES_EN.put(INSTAGRAM, "Instagram");
        NAMES_EN.put(TIKTOK, "TikTok");
        NAMES_EN.put(X, "X");
        NAMES.put(YOUTUBE, "يوتيوب");
        NAMES.put(FACEBOOK, "فيسبوك");
        NAMES.put(INSTAGRAM, "إنستجرام");
        NAMES.put(TIKTOK, "تيك توك");
        NAMES.put(X, "إكس");
    }

    /** The site's name in the current language ("الموقع" / "the site" when unknown). */
    public static String name(String id) {
        if (I18n.english()) {
            String e = id == null ? null : NAMES_EN.get(id);
            return e != null ? e : "the site";
        }
        String n = id == null ? null : NAMES.get(id);
        return n != null ? n : "الموقع";
    }

    /**
     * Maps yt-dlp's extractor name to one of our sites. A tweet that only links to a
     * YouTube video comes back from the YouTube extractor, for example.
     */
    public static String fromExtractor(String key, String fallback) {
        String k = Str.lower(key);
        if (k.startsWith("youtube")) return YOUTUBE;
        if (k.startsWith("facebook")) return FACEBOOK;
        if (k.startsWith("instagram")) return INSTAGRAM;
        if (k.startsWith("tiktok") || k.startsWith("vm.tiktok")) return TIKTOK;
        if (k.startsWith("twitter")) return X;
        return fallback;
    }

    // ---------------------------------------------------------------------------------
    // Links
    // ---------------------------------------------------------------------------------

    public static final String KIND_VIDEO = "video";
    public static final String KIND_PLAYLIST = "playlist"; // a YouTube playlist
    public static final String KIND_PROFILE = "profile";   // a channel, page or account, not one video
    public static final String KIND_LIVE = "live";         // a live stream page that is not a video yet
    public static final String KIND_SHORT = "short";       // a short or share link we open first
    public static final String KIND_OTHER = "other";       // a page we don't recognise; yt-dlp decides

    public static final int FOUND = 0;
    public static final int NONE = 1;        // no web address at all
    public static final int UNSUPPORTED = 2; // a web address, but not from a site we support
    public static final int INVALID = 3;     // a supported site, but not a video page

    public static final class Link {
        public String url = "";
        public String site = "";
        public String kind = "";

        Link() {}

        Link(String url, String kind) {
            this.url = url;
            this.kind = kind;
        }
    }

    public static final class LinkResult {
        public final Link link;
        public final int result;

        LinkResult(Link link, int result) {
            this.link = link;
            this.result = result;
        }
    }

    // Go's \s is [\t\n\f\r ] and \d is [0-9]; Android's regex engine would match more.
    private static final Pattern ANY_LINK = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(?::[0-9]+)?(?:/[^\\t\\n\\f\\r <>\"'،]*)?",
            Pattern.CASE_INSENSITIVE);
    static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern TIKTOK_V = Pattern.compile("^([0-9]+)(?:\\.html)?$");

    private static boolean hostIs(String h, String... domains) {
        for (String d : domains) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    static String hostSite(String host) {
        String h = Str.trimPrefix(Str.lower(host), "www.");
        if (hostIs(h, "youtube.com", "youtu.be", "youtube-nocookie.com")) return YOUTUBE;
        if (hostIs(h, "facebook.com", "fb.watch", "fb.com", "fb.me")) return FACEBOOK;
        if (hostIs(h, "instagram.com", "instagr.am")) return INSTAGRAM;
        if (hostIs(h, "tiktok.com")) return TIKTOK;
        if (hostIs(h, "x.com", "twitter.com", "t.co", "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com")) return X;
        return "";
    }

    /**
     * Finds the first supported link inside arbitrary pasted text (people often paste a
     * whole WhatsApp message) and returns it cleaned up.
     */
    public static LinkResult parseLink(String text) {
        int result = NONE;
        Matcher m = ANY_LINK.matcher(text);
        int found = 0;
        while (found < 20 && m.find()) {
            found++;
            String s = Str.trimRight(m.group(), ".,;:!?)]}»\"'" + "،؛؟");
            if (!Str.lower(s).startsWith("http")) s = "https://" + s;
            GoUrl u;
            try {
                u = GoUrl.parse(s);
            } catch (GoUrl.ParseException e) {
                continue;
            }
            if (u.host.length() == 0) continue;
            String site = hostSite(u.hostname());
            if (site.length() == 0) {
                if (result == NONE) result = UNSUPPORTED;
                continue;
            }
            u.scheme = "https";
            u.fragment = "";
            Link link = null;
            if (site.equals(YOUTUBE)) link = parseYouTube(u);
            else if (site.equals(FACEBOOK)) link = parseFacebook(u);
            else if (site.equals(INSTAGRAM)) link = parseInstagram(u);
            else if (site.equals(TIKTOK)) link = parseTikTok(u);
            else if (site.equals(X)) link = parseX(u);
            if (link != null) {
                link.site = site;
                return new LinkResult(link, FOUND);
            }
            result = INVALID;
        }
        return new LinkResult(new Link(), result);
    }

    private static String[] pathParts(GoUrl u) {
        String p = Str.trim(u.path, "/");
        if (p.length() == 0) return new String[0];
        return p.split("/", -1);
    }

    private static String join(String[] parts) {
        return Str.join("/", Arrays.asList(parts));
    }

    private static Link parseYouTube(GoUrl u) {
        String host = Str.lower(u.hostname());
        String[] parts = pathParts(u);
        String path = join(parts);
        if (host.equals("youtu.be")) {
            if (parts.length >= 1 && VIDEO_ID.matcher(parts[0]).matches()) return new Link(u.toString(), KIND_VIDEO);
            return null;
        }
        if (path.equals("watch") || path.equals("watch_popup")) {
            if (VIDEO_ID.matcher(u.queryGet("v")).matches()) return new Link(u.toString(), KIND_VIDEO);
            return null;
        }
        if (parts.length >= 2 && (parts[0].equals("shorts") || parts[0].equals("live") || parts[0].equals("embed")
                || parts[0].equals("v") || parts[0].equals("e"))) {
            if (VIDEO_ID.matcher(parts[1]).matches()) return new Link(u.toString(), KIND_VIDEO);
            return null;
        }
        if (parts.length >= 2 && parts[0].equals("clip")) return new Link(u.toString(), KIND_VIDEO);
        if (path.equals("playlist")) return new Link(u.toString(), KIND_PLAYLIST);
        if (parts.length > 0 && (parts[0].startsWith("@") || parts[0].equals("channel") || parts[0].equals("c")
                || parts[0].equals("user"))) {
            return new Link(u.toString(), KIND_PROFILE);
        }
        if (path.length() == 0) return null;
        return new Link(u.toString(), KIND_OTHER);
    }

    // Facebook paths that look like a page name but are not one.
    private static final Set<String> FB_NOT_PROFILE = new HashSet<String>(Arrays.asList(
            "photo", "photos", "groups", "events", "stories", "live", "videos", "video", "watchparty", "media",
            "permalink", "posts"));

    private static Link parseFacebook(GoUrl u) {
        String host = Str.lower(u.hostname());
        String[] parts = pathParts(u);
        if (host.equals("fb.watch") || host.equals("fb.me")) {
            if (parts.length == 0) return null;
            return new Link(u.toString(), KIND_SHORT);
        }
        if (host.equals("fb.com") || host.equals("www.fb.com")) u.host = "www.facebook.com";
        if (parts.length == 0) return null;
        String first = Str.lower(parts[0]);
        if (first.equals("share")) return new Link(u.toString(), KIND_SHORT);
        if (first.equals("reel") && parts.length >= 2) return new Link(u.toString(), KIND_VIDEO);
        if (first.equals("watch")) {
            if (u.queryGet("v").length() > 0) return new Link(u.toString(), KIND_VIDEO);
            return null;
        }
        if (first.equals("login") || first.equals("login.php") || first.equals("home.php") || first.equals("reels")
                || first.equals("marketplace") || first.equals("gaming")) {
            return null;
        }
        if (first.equals("profile.php") || first.equals("people")) return new Link(u.toString(), KIND_PROFILE);
        if (FB_NOT_PROFILE.contains(first)) return new Link(u.toString(), KIND_OTHER);
        if (parts.length >= 3 && Str.lower(parts[1]).equals("videos")) return new Link(u.toString(), KIND_VIDEO);
        if (parts.length == 1 && !first.contains(".php")) return new Link(u.toString(), KIND_PROFILE);
        if (parts.length == 2 && (parts[1].equals("videos") || parts[1].equals("reels") || parts[1].equals("photos"))) {
            return new Link(u.toString(), KIND_PROFILE);
        }
        return new Link(u.toString(), KIND_OTHER);
    }

    private static boolean igPost(String seg) {
        String s = Str.lower(seg);
        return s.equals("p") || s.equals("reel") || s.equals("reels") || s.equals("tv");
    }

    private static Link parseInstagram(GoUrl u) {
        u.host = "www.instagram.com";
        String[] parts = pathParts(u);
        if (parts.length == 0) return null;
        String first = Str.lower(parts[0]);
        if (first.equals("share")) return new Link(u.toString(), KIND_SHORT);
        if (first.equals("reels") && parts.length >= 2 && Str.lower(parts[1]).equals("audio")) {
            return new Link(u.toString(), KIND_PROFILE);
        }
        if (igPost(first) && parts.length >= 2) return new Link(u.toString(), KIND_VIDEO);
        if (parts.length >= 3 && igPost(parts[1])) return new Link(u.toString(), KIND_VIDEO); // instagram.com/<user>/reel/<code>
        if (first.equals("stories")) return new Link(u.toString(), KIND_OTHER);
        if (first.equals("accounts") || first.equals("explore") || first.equals("direct") || igPost(first)) return null;
        if (parts.length <= 2) return new Link(u.toString(), KIND_PROFILE);
        return new Link(u.toString(), KIND_OTHER);
    }

    private static Link tiktokVideo(String[] parts, String id) {
        String user = "@";
        if (parts[0].startsWith("@")) user = parts[0];
        return new Link("https://www.tiktok.com/" + user + "/video/" + id, KIND_VIDEO);
    }

    private static Link parseTikTok(GoUrl u) {
        String host = Str.lower(u.hostname());
        String[] parts = pathParts(u);
        if (host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com")) {
            if (parts.length == 0) return null;
            return new Link(u.toString(), KIND_SHORT);
        }
        // yt-dlp only recognises www.tiktok.com.
        u.host = "www.tiktok.com";
        if (parts.length == 0) return null;
        String first = Str.lower(parts[0]);
        if (first.equals("t") && parts.length >= 2) return new Link(u.toString(), KIND_SHORT);
        if (first.startsWith("@") && parts.length >= 3 && (parts[1].equals("video") || parts[1].equals("photo"))
                && DIGITS.matcher(parts[2]).matches()) {
            // Photo posts are read through the video page: that gives their music.
            return tiktokVideo(parts, parts[2]);
        }
        if (first.startsWith("@") && parts.length >= 2 && parts[1].equals("live")) return new Link(u.toString(), KIND_LIVE);
        if (first.startsWith("@") && parts.length <= 2) return new Link(u.toString(), KIND_PROFILE);
        if ((first.equals("v") || first.equals("embed")) && parts.length >= 2) {
            Matcher m = TIKTOK_V.matcher(parts[parts.length - 1]);
            if (m.matches()) return tiktokVideo(parts, m.group(1));
            return null;
        }
        if ((first.equals("share") || first.equals("video")) && parts.length >= 2) {
            String last = parts[parts.length - 1];
            if (DIGITS.matcher(last).matches()) return tiktokVideo(parts, last);
            return null;
        }
        if (first.equals("music") || first.equals("tag") || first.equals("discover") || first.equals("explore")
                || first.equals("foryou") || first.equals("following") || first.equals("live")) {
            return new Link(u.toString(), KIND_PROFILE);
        }
        return new Link(u.toString(), KIND_OTHER);
    }

    private static final Pattern X_STATUS = Pattern.compile(
            "^/(?:[^/]+/status(?:es)?|i/(?:web/)?status|statuses)/([0-9]+)(/video/[0-9]+)?", Pattern.CASE_INSENSITIVE);

    private static Link parseX(GoUrl u) {
        String host = Str.lower(u.hostname());
        String[] parts = pathParts(u);
        if (host.equals("t.co")) {
            if (parts.length == 0) return null;
            return new Link(u.toString(), KIND_OTHER);
        }
        if (parts.length == 0) return null;
        Matcher m = X_STATUS.matcher(u.path);
        if (m.find()) {
            // A link to one photo of the post would fail; ask for the whole post instead.
            String path = Str.trimSuffix(u.path.substring(0, m.end()), "/");
            return new Link("https://x.com" + path, KIND_VIDEO);
        }
        String first = Str.lower(parts[0]);
        if (first.equals("i") && parts.length >= 3 && (parts[1].equals("spaces") || parts[1].equals("broadcasts"))) {
            return new Link("https://x.com" + u.path, KIND_OTHER);
        }
        if (first.equals("i") || first.equals("home") || first.equals("explore") || first.equals("search")
                || first.equals("notifications") || first.equals("messages") || first.equals("settings")
                || first.equals("login")) {
            return null;
        }
        if (parts.length <= 2) return new Link("https://x.com/" + parts[0], KIND_PROFILE);
        return new Link("https://x.com" + u.path, KIND_OTHER);
    }

    // ---------------------------------------------------------------------------------
    // Short links
    // ---------------------------------------------------------------------------------

    private static final Pattern OG_URL = Pattern.compile(
            "<meta[^>]+property=[\"']og:url[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_URL2 = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:url[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANONICAL = Pattern.compile(
            "<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static boolean isRealPost(String url) {
        LinkResult r = parseLink(url);
        return r.result == FOUND && !r.link.kind.equals(KIND_SHORT);
    }

    /**
     * Opens a short or share link (fb.watch/…, vm.tiktok.com/…, facebook.com/share/…) and
     * returns the address of the real post.
     */
    public static String resolveShortLink(String raw, Cancel cancel) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        // Sites answer link-preview robots with a plain redirect or a page that names the post.
        h.put("User-Agent", "facebookexternalhit/1.1");
        h.put("Accept-Language", "en-US,en;q=0.8");
        Http.Response r = Http.get(raw, h, 15000, 2 << 20, true, new Http.RedirectCheck() {
            @Override
            public boolean stopAt(String url) {
                // Stop as soon as a redirect names the post: no need to load its heavy page.
                return isRealPost(url);
            }
        }, cancel);
        if (r.stoppedAt != null) return r.stoppedAt;
        String fin = r.url;
        if (isRealPost(fin)) return fin;
        String body = new String(r.body, Charset.forName("UTF-8"));
        for (Pattern p : new Pattern[]{OG_URL, OG_URL2, CANONICAL}) {
            Matcher m = p.matcher(body);
            if (m.find()) {
                String u = m.group(1).replace("&amp;", "&");
                if (isRealPost(u)) return u;
            }
        }
        return fin;
    }

    // ---------------------------------------------------------------------------------
    // Thumbnails
    // ---------------------------------------------------------------------------------

    public static String referer(String site) {
        if (TIKTOK.equals(site)) return "https://www.tiktok.com/";
        if (INSTAGRAM.equals(site)) return "https://www.instagram.com/";
        if (FACEBOOK.equals(site)) return "https://www.facebook.com/";
        if (X.equals(site)) return "https://x.com/";
        return "";
    }

    /** Downloads a small picture and returns it as a data: address the page can show directly. */
    public static String fetchImageDataURL(String src, String referer, Cancel cancel) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", Http.BROWSER_UA);
        h.put("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
        if (referer.length() > 0) h.put("Referer", referer);
        Http.Response r = Http.get(src, h, 12000, (4 << 20) + 1, true, null, cancel);
        if (r.code != 200) throw new Http.StatusException(r.code);
        if (r.body.length > 4 << 20 || r.body.length == 0) throw new IOException("picture too large or empty");
        String ct = Str.trimSpace(r.contentType.split(";", 2)[0]);
        if (!ct.startsWith("image/")) ct = Http.sniffImage(r.body);
        if (!ct.startsWith("image/")) throw new IOException("not a picture: " + ct);
        return "data:" + ct + ";base64," + java.util.Base64.getEncoder().encodeToString(r.body);
    }

    /** All supported site ids, in the order they are shown. */
    public static final List<String> ALL = Arrays.asList(YOUTUBE, FACEBOOK, INSTAGRAM, TIKTOK, X);
}
