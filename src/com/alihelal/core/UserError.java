package com.alihelal.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What the user sees when something goes wrong: a plain message in Modern Standard Arabic or
 * English, no jargon. The words are produced when shown (title(), message()), so a message
 * already on screen or saved with a download follows a change of language.
 */
public final class UserError implements Json.Value {
    public String code = "";
    public String details = "";
    /** Offer a "try again" button. */
    public boolean retry = true;
    /** Best next step for the UI: "", "newLink", "chooseQuality", "setup". */
    public String action = "";
    /** A newer yt-dlp might fix it, so the app updates and retries once on its own. */
    public boolean outdated;
    /** The YouTube helper looks broken or too old. */
    public boolean jsRuntime;
    /** The site the message talks about ("" for "the site"). */
    public String site = "";
    /** For "disk": bytes the file needs and bytes free (-1 when unknown). */
    public long need = -1, free = -1;

    @Override
    public Object toJson() {
        Map<String, Object> m = Json.obj("code", code, "title", title(), "message", message());
        if (details.length() > 0) m.put("details", details);
        m.put("retry", retry);
        if (action.length() > 0) m.put("action", action);
        return m;
    }

    @Override
    public String toString() {
        return code + ": " + message();
    }

    public String title() {
        return fill(texts(code)[I18n.english() ? 2 : 0]);
    }

    public String message() {
        if (code.equals("disk") && need > 0 && free >= 0) {
            return I18n.t("يحتاج الملف إلى نحو " + Text.humanSize(need) + " من المساحة الفارغة، والمتاح على الهاتف "
                            + Text.humanSize(free) + " فقط. احذف بعض الملفات غير الضرورية، ثم اضغط «إعادة المحاولة».",
                    "This file needs about " + Text.humanSize(need) + " of free space, but only " + Text.humanSize(free)
                            + " is free on the phone. Delete some files you don't need, then tap Try again.");
        }
        return fill(texts(code)[I18n.english() ? 3 : 1]);
    }

    /** Puts the site's name in: {site}, or {Site} at the start of an English sentence. */
    private String fill(String t) {
        String name = Sites.name(site);
        String cap = name.length() > 0 ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
        return t.replace("{Site}", cap).replace("{site}", name);
    }

    private static final class Rule {
        final String code;
        final List<String> needles;

        Rule(String code, String... needles) {
            this.code = code;
            this.needles = Arrays.asList(needles);
        }
    }

    // Order matters: the first rule that matches wins.
    private static final List<Rule> RULES = Arrays.asList(
            // A share link that ended on a login page.
            new Rule("login", "facebook.com/login", "instagram.com/accounts/login", "tiktok.com/login"),
            new Rule("invalid_url", "is not a valid url", "unsupported url"),
            new Rule("novideo", "no video could be found in this tweet", "there is no video in this post", "is not a video",
                    "no video in this post"),
            new Rule("private", "private video", "this video is private", "video is private", "protected tweet",
                    "registered users who follow", "account is private", "do not have permission to view this post"),
            new Rule("age", "confirm your age", "age-restricted", "age restricted", "inappropriate for some users"),
            new Rule("members", "members-only", "members only", "join this channel", "channel's members",
                    "available to this channel"),
            new Rule("premium", "music premium", "youtube premium", "premium members"),
            new Rule("upcoming", "premieres in", "will begin in", "live event will begin", "premiere will begin",
                    "this live event will", "has not yet started", "not started yet"),
            new Rule("geo", "not available in your country", "geo restrict", "geo-restrict", "in your country",
                    "not made this video available in your", "ip address is blocked"),
            new Rule("drm", "drm protected", "is drm", "drm-protected"),
            new Rule("bot", "not a bot", "confirm you’re not", "confirm you're not", "unusual traffic"),
            new Rule("ig_limit", "rate-limit reached or login required", "rate-limit for accessing posts anonymously",
                    "instagram sent an empty media response"),
            new Rule("ratelimit", "http error 429", "too many requests", "rate-limit", "rate limit", "ratelimit"),
            new Rule("login", "--cookies", "login required", "log in to continue", "you must log in", "requires authentication",
                    "requiring login", "log in for access", "only available for registered users"),
            new Rule("clock", "certificate verify failed", "certificate_verify_failed", "certificate has expired",
                    "certificate is not yet valid", "not yet valid"),
            new Rule("disk", "no space left", "errno 28", "not enough space", "disk full", "winerror 112",
                    "there is not enough space", "disk quota exceeded"),
            new Rule("permission", "permission denied", "errno 13", "access is denied", "read-only file system",
                    "errno 30", "winerror 5]", "winerror 5)", "being used by another process", "winerror 32"),
            new Rule("noaudio", "does not contain any stream", "unable to obtain file audio codec", "matches no streams"),
            new Rule("unavailable", "video unavailable", "this video is unavailable", "this video has been removed",
                    "has been terminated", "no longer available", "does not exist", "removed for violating", "copyright",
                    "video is not available", "content isn't available", "requested tweet is unavailable", "facebook said",
                    "video not available", "post isn't available", "is unavailable", "broadcast no longer exists",
                    "space not found", "livestream has ended"),
            new Rule("ffmpeg", "ffmpeg not found", "ffprobe and ffmpeg not found", "ffmpeg is not installed",
                    "ffmpeg could not be found", "ffmpeg exited with code"),
            new Rule("jsruntime", "javascript runtime", "js runtime", "jsc", "challenge solving failed", "challenge solver",
                    "quickjs", "deno"),
            new Rule("network", "getaddrinfo failed", "could not resolve host", "name or service not known",
                    "failed to resolve", "temporary failure in name resolution", "nodename nor servname",
                    "no address associated with hostname", "network is unreachable", "no route to host",
                    "timed out", "timeout", "winerror 10060", "winerror 10061", "winerror 10065", "winerror 11001",
                    "winerror 10051", "winerror 10054", "connection refused", "connection reset", "connection aborted",
                    "software caused connection abort", "broken pipe", "eof occurred in violation of protocol",
                    "remote end closed connection", "unable to connect", "failed to establish a new connection",
                    "max retries exceeded", "network error", "curl: (6)", "curl: (7)", "curl: (28)", "curl: (35)",
                    "curl: (56)", "transporterror", "incompleteread"),
            new Rule("forbidden", "http error 403", "403: forbidden", "403 forbidden"),
            new Rule("format", "requested format is not available", "no video formats found", "format is not available"),
            new Rule("extractor", "unable to extract", "please report this issue", "unable to download api page",
                    "unable to download webpage", "failed to parse", "cannot parse data", "guest token", "while querying api",
                    "unexpected response from webpage", "unable to solve js challenge"));

    /** Maps yt-dlp's error output to a friendly message about the given site. */
    public static UserError classifyFor(String output, String site) {
        UserError e = classifyOutput(output);
        if (!Sites.YOUTUBE.equals(site)) {
            if (e.code.equals("jsruntime")) {
                // The YouTube helper is not involved on other sites.
                String d = e.details;
                e = make("extractor");
                e.details = d;
            } else if (e.code.equals("format") && Str.lower(output).contains("no video formats found")) {
                String d = e.details;
                e = make("novideo");
                e.details = d;
            }
        }
        return e.localize(site);
    }

    /** The site the message is about ("تعذّر الوصول إلى يوتيوب"). */
    public UserError localize(String site) {
        this.site = site == null ? "" : site;
        return this;
    }

    /** Maps yt-dlp's error output to a friendly Egyptian-Arabic message. */
    public static UserError classifyOutput(String output) {
        String lower = Str.lower(output);
        // Prefer what yt-dlp itself calls an ERROR; warnings are only a fallback.
        List<String> errLines = new ArrayList<String>();
        for (String l : lower.split("\n", -1)) {
            if (l.contains("error")) errLines.add(l);
        }
        String[] scopes = {Str.join("\n", errLines), lower};
        for (String scope : scopes) {
            if (Str.trimSpace(scope).length() == 0) continue;
            for (Rule r : RULES) {
                for (String n : r.needles) {
                    if (scope.contains(n)) {
                        UserError e = make(r.code);
                        e.details = Str.tailLines(output, 25);
                        return e;
                    }
                }
            }
        }
        UserError e = make("unknown");
        e.details = Str.tailLines(output, 25);
        return e;
    }

    /** An error for a known code (an unknown code becomes "unknown"). */
    public static UserError make(String code) {
        UserError e = new UserError();
        e.code = TEXTS.containsKey(code) ? code : code.equals("not_youtube") ? "unsupported_site" : "unknown";
        e.retry = true;
        String c = e.code;
        if (c.equals("invalid_url") || c.equals("unsupported_site") || c.equals("playlist") || c.equals("profile")
                || c.equals("novideo") || c.equals("private") || c.equals("login") || c.equals("age") || c.equals("members")
                || c.equals("premium") || c.equals("geo") || c.equals("drm") || c.equals("unavailable")
                || c.equals("channel") || c.equals("list_empty") || c.equals("clip_list")) {
            e.retry = false;
            e.action = "newLink";
        } else if (c.equals("update_none") || c.equals("update_manual") || c.equals("clip_range")) {
            e.retry = false;
        } else if (c.equals("noaudio")) {
            e.retry = false;
            e.action = "chooseQuality";
        } else if (c.equals("sep_no_audio") || c.equals("sep_unreadable") || c.equals("file_missing")) {
            e.retry = false;
        } else if (c.equals("upcoming") || c.equals("live")) {
            e.action = "newLink";
        } else if (c.equals("disk") || c.equals("format")) {
            e.action = "chooseQuality";
        } else if (c.equals("ffmpeg") || c.equals("tools_missing")) {
            e.action = "setup";
        }
        if (c.equals("jsruntime")) {
            e.jsRuntime = true;
            e.outdated = true;
        }
        if (c.equals("forbidden") || c.equals("format") || c.equals("noformats") || c.equals("extractor") || c.equals("unknown")) {
            e.outdated = true;
        }
        return e;
    }

    /** {Arabic title, Arabic message, English title, English message} for a code. */
    static String[] texts(String code) {
        String[] t = TEXTS.get(code);
        return t != null ? t : TEXTS.get("unknown");
    }

    private static final Map<String, String[]> TEXTS = new java.util.HashMap<String, String[]>();

    private static void text(String code, String arTitle, String arMessage, String enTitle, String enMessage) {
        TEXTS.put(code, new String[]{arTitle, arMessage, enTitle, enMessage});
    }

    static {
        text("invalid_url", "هذا الرابط غير صالح",
                "هذا ليس رابط فيديو. افتح الفيديو، واضغط «مشاركة» ثم «نسخ الرابط»، ثم الصقه هنا.",
                "This link doesn't work",
                "This isn't a video link. Open the video, tap Share, then Copy link, and paste it here.");
        text("unsupported_site", "هذا الموقع غير مدعوم",
                "يُنزّل التطبيق من يوتيوب وفيسبوك وإنستجرام وتيك توك وإكس (تويتر). انسخ رابط فيديو من أحدها والصقه هنا.",
                "This site isn't supported",
                "The app downloads from YouTube, Facebook, Instagram, TikTok and X (Twitter). Copy a video link from one of them and paste it here.");
        text("playlist", "هذا رابط قائمة وليس فيديو",
                "تنزيل القوائم كاملة متاح من يوتيوب فقط. من هذا الموقع، افتح الفيديو الذي تريده وانسخ رابطه هو.",
                "This is a playlist, not a video",
                "Whole lists can be downloaded from YouTube only. On this site, open the video you want and copy its own link.");
        text("channel", "هذا رابط قناة كاملة",
                "افتح قائمة التشغيل أو الكورس الذي تريده في القناة وانسخ رابطه، أو افتح الفيديو نفسه وانسخ رابطه.",
                "This is a whole channel",
                "Open the playlist or course you want on the channel and copy its link, or open the video itself and copy its link.");
        text("clip_list", "هذا رابط قائمة وليس فيديو",
                "لتنزيل جزء، افتح الفيديو الذي تريده وانسخ رابطه هو.",
                "This is a list, not a video",
                "To download a part, open the video you want and copy its own link.");
        text("clip_range", "اختر جزءًا أطول",
                "يجب أن تكون نهاية الجزء بعد بدايته، وأن يكون داخل مدة الفيديو.",
                "Choose a longer part",
                "The part must end after it starts, and be within the video.");
        text("list_empty", "لا توجد فيديوهات يمكن تنزيلها في هذه القائمة",
                "القائمة فارغة، أو كل فيديوهاتها خاصة أو محذوفة.",
                "No videos to download in this list",
                "The list is empty, or all its videos are private or removed.");
        text("update_check", "تعذّر التحقق من التحديثات",
                "تأكد من اتصالك بالإنترنت، ثم حاول مرة أخرى.",
                "Couldn't check for updates",
                "Check your internet connection, then try again.");
        text("update_verify", "ملف التحديث غير سليم",
                "لم يطابق ملف التحديث النسخة الأصلية، لذلك لم يُثبَّت حفاظًا على هاتفك. حاول مرة أخرى لاحقًا.",
                "The update file isn't right",
                "The update didn't match the original, so it wasn't installed, to keep your phone safe. Try again later.");
        text("update_network", "تعذّر تنزيل التحديث",
                "انقطع الاتصال أثناء تنزيل التحديث. تأكد من اتصالك بالإنترنت ثم حاول مرة أخرى، وسيُكمل التنزيل من حيث توقف.",
                "Couldn't download the update",
                "The connection dropped while the update was downloading. Check your internet connection and try again; it continues where it stopped.");
        text("update_disk", "المساحة غير كافية للتحديث",
                "لا توجد مساحة كافية على الهاتف لتنزيل التحديث. احذف بعض الملفات، ثم حاول مرة أخرى.",
                "Not enough space for the update",
                "There isn't enough space on the phone to download the update. Delete some files, then try again.");
        text("update_failed", "تعذّر تثبيت التحديث",
                "حدثت مشكلة أثناء تثبيت التحديث. حاول مرة أخرى، أو نزّل الإصدار الجديد من صفحة HELINK وثبّته.",
                "Couldn't install the update",
                "Something went wrong while installing the update. Try again, or download the new version from HELINK's page and install it.");
        text("update_manual", "لا يمكن تحديث HELINK من داخله على هذا الهاتف",
                "نزّل الإصدار الجديد من صفحة HELINK وثبّته.",
                "HELINK can't update itself on this phone",
                "Download the new version from HELINK's page and install it.");
        text("update_none", "لا يوجد تحديث الآن",
                "لديك أحدث إصدار من HELINK.",
                "No update right now",
                "You have the latest version of HELINK.");
        text("profile", "هذا رابط حساب وليس فيديو",
                "هذا الرابط لصفحة أو لحساب كامل. افتح الفيديو نفسه وانسخ رابطه.",
                "This is a profile, not a video",
                "This link is for a page or a whole account. Open the video itself and copy its link.");
        text("novideo", "لا يحتوي هذا المنشور على فيديو",
                "هذا الرابط لمنشور يضم صورًا أو نصًا فقط. إذا كان المنشور يحتوي على فيديو، فافتح الفيديو نفسه وانسخ رابطه.",
                "This post has no video",
                "This link is for a post with only photos or text. If the post has a video, open the video itself and copy its link.");
        text("noaudio", "هذا الفيديو بلا صوت",
                "لذلك لا يمكن تنزيله بصيغة MP3، لكن يمكنك تنزيله فيديو بدون صوت.",
                "This video has no sound",
                "So it can't be saved as MP3, but you can download it as a video without sound.");
        text("private", "هذا الفيديو خاص",
                "جعله صاحبه متاحًا له ولمن يختارهم فقط، لذلك لا يمكن تنزيله.",
                "This video is private",
                "Its owner shared it only with people they chose, so it can't be downloaded.");
        text("login", "يتطلب هذا الفيديو تسجيل الدخول",
                "لا يسمح {site} بتنزيل هذا الفيديو دون حساب. يُنزّل التطبيق الفيديوهات التي يستطيع أي شخص مشاهدتها دون تسجيل الدخول.",
                "This video requires signing in",
                "{Site} doesn't allow downloading this video without an account. The app downloads videos that anyone can watch without signing in.");
        text("ig_limit", "يرفض إنستجرام إتاحة الفيديو الآن",
                "يحدث هذا إذا كان الحساب خاصًا، أو عندما يطلب إنستجرام تسجيل الدخول بعد طلبات كثيرة متتالية. إذا كان الحساب عامًا، فانتظر ربع ساعة ثم حاول مرة أخرى.",
                "Instagram won't share the video right now",
                "This happens when the account is private, or when Instagram asks for a sign-in after many requests in a row. If the account is public, wait 15 minutes and try again.");
        text("age", "يتطلب هذا الفيديو تسجيل الدخول",
                "يطلب {site} التحقق من عمر المشاهد لهذا الفيديو، لذلك لا يمكن تنزيله دون حساب.",
                "This video requires signing in",
                "{Site} needs to check the viewer's age for this video, so it can't be downloaded without an account.");
        text("members", "هذا الفيديو للمشتركين فقط",
                "هذا الفيديو متاح لأعضاء القناة المشتركين فقط، لذلك لا يمكن تنزيله.",
                "This video is for members only",
                "This video is only for the channel's paying members, so it can't be downloaded.");
        text("premium", "هذا الفيديو لمشتركي بريميوم",
                "هذا الفيديو متاح لمشتركي يوتيوب بريميوم فقط، لذلك لا يمكن تنزيله.",
                "This video is for Premium members",
                "This video is only for YouTube Premium members, so it can't be downloaded.");
        text("upcoming", "لم يُعرض الفيديو بعد",
                "لم يبدأ عرض هذا الفيديو حتى الآن. انتظر حتى يبدأ ثم حاول مرة أخرى.",
                "This video isn't out yet",
                "This video hasn't premiered yet. Wait until it starts, then try again.");
        text("live", "هذا بث مباشر جارٍ",
                "لا يمكن تنزيل البث المباشر أثناء عرضه. انتظر حتى ينتهي البث ثم حاول مرة أخرى.",
                "This is a live stream",
                "A live stream can't be downloaded while it's on. Wait until it ends, then try again.");
        text("geo", "الفيديو غير متاح في بلدك",
                "منع صاحب الفيديو أو {site} عرضه في بلدك، لذلك لا يمكن تنزيله من هنا.",
                "This video isn't available in your country",
                "The owner or {site} blocked it in your country, so it can't be downloaded here.");
        text("drm", "هذا الفيديو محمي",
                "هذا الفيديو محمي ضد النسخ (مثل الأفلام المدفوعة)، ولا يمكن تنزيله.",
                "This video is protected",
                "This video has copy protection (like paid movies) and can't be downloaded.");
        text("unavailable", "الفيديو غير موجود",
                "حُذف هذا الفيديو أو أصبح غير متاح على {site}. تأكد أولًا من أنه يعمل لديك.",
                "Video not found",
                "This video was removed or isn't available on {site}. Check that it opens for you first.");
        text("bot", "يريد {site} التأكد من أنك لست روبوتًا",
                "أوقف {site} هذا الطلب مؤقتًا. انتظر قليلًا ثم حاول مرة أخرى، وإذا استمرت المشكلة فجرّب التبديل بين الواي فاي وبيانات الهاتف.",
                "{Site} wants to check you're not a robot",
                "{Site} paused this request for now. Wait a little and try again. If it keeps happening, switch between Wi-Fi and mobile data.");
        text("ratelimit", "يطلب {site} الانتظار قليلًا",
                "أُرسلت طلبات كثيرة متتالية فأوقفها {site} مؤقتًا. انتظر ربع ساعة ثم حاول مرة أخرى.",
                "{Site} asks us to wait a bit",
                "Too many requests in a row, so {site} paused them for a while. Wait 15 minutes and try again.");
        text("clock", "توجد مشكلة في تاريخ الهاتف",
                "غالبًا تاريخ الهاتف أو ساعته غير صحيح، وهذا يمنع الاتصال الآمن بالمواقع. اضبط التاريخ والساعة على «تلقائي» من الإعدادات ثم حاول مرة أخرى.",
                "There's a problem with the phone's date",
                "The phone's date or time is probably wrong, which blocks secure connections to websites. Set date and time to Automatic in Settings, then try again.");
        text("network", "تعذّر الوصول إلى {site}",
                "تأكد من اتصالك بالإنترنت، ثم حاول مرة أخرى.",
                "Can't reach {site}",
                "Check your internet connection, then try again.");
        text("disk", "المساحة غير كافية",
                "المساحة الفارغة على الهاتف لا تكفي. احذف بعض الملفات غير الضرورية، أو اختر جودة أقل.",
                "Not enough space",
                "There isn't enough free space on the phone. Delete some files you don't need, or choose a lower quality.");
        text("permission", "تعذّر حفظ الملف",
                "لم يسمح الهاتف للتطبيق بحفظ الملف. تأكد من وجود مساحة فارغة ثم حاول مرة أخرى.",
                "Couldn't save the file",
                "The phone didn't let the app save the file. Make sure there's free space, then try again.");
        String[] missing = {"جزء من التطبيق مفقود",
                "بعض أدوات التطبيق مفقودة أو تالفة. ستُجهَّز من جديد، ولن يستغرق ذلك أكثر من دقيقة.",
                "Part of the app is missing",
                "Some of the app's tools are missing or damaged. They'll be set up again, which takes less than a minute."};
        text("ffmpeg", missing[0], missing[1], missing[2], missing[3]);
        text("tools_missing", missing[0], missing[1], missing[2], missing[3]);
        text("jsruntime", "مساعد يوتيوب يحتاج إلى تحديث",
                "مساعد يوتيوب في التطبيق لا يعمل جيدًا. حاول مرة أخرى وسنحاول تحديثه.",
                "The YouTube helper needs an update",
                "The app's YouTube helper isn't working well. Try again and we'll try to update it.");
        text("forbidden", "رفض {site} التنزيل",
                "رفض {site} هذا الطلب، وغالبًا سيحل التحديث المشكلة. حاول مرة أخرى.",
                "{Site} refused the download",
                "{Site} refused this request. An update will probably fix it, so try again.");
        text("format", "هذه الجودة غير متاحة",
                "لم تعد الجودة التي اخترتها متاحة. حاول مرة أخرى أو اختر جودة أخرى.",
                "This quality isn't available",
                "The quality you chose is no longer available. Try again or choose another quality.");
        text("noformats", "لا توجد جودات لهذا الفيديو",
                "لم يُرجع {site} أي جودة لهذا الفيديو. حاول مرة أخرى بعد قليل.",
                "No qualities found for this video",
                "{Site} didn't return any quality for this video. Try again in a little while.");
        text("extractor", "غيّر {site} طريقة عمله",
                "يغيّر {site} طريقة عمله من وقت لآخر. سيُحدَّث التطبيق، فحاول مرة أخرى.",
                "{Site} changed something",
                "{Site} changes how it works from time to time. The app will update, so try again.");
        text("setup_failed", "لم يكتمل التجهيز",
                "غالبًا انقطع الإنترنت أثناء تنزيل أدوات التطبيق. تأكد من اتصالك بالإنترنت ومن وجود مساحة فارغة على الهاتف (نحو 200 ميجابايت)، ثم اضغط «إعادة المحاولة» ليُستأنف التنزيل من حيث توقف.",
                "Setup didn't finish",
                "The internet probably dropped while the app's tools were downloading. Check your connection and that the phone has about 200 MB free, then tap Try again to continue where it stopped.");
        text("setup_blocked", "أدوات التطبيق لا تعمل",
                "جُهّزت أدوات التطبيق لكنها لا تعمل على هذا الهاتف. حاول مرة أخرى، وإذا استمرت المشكلة فأرسل التفاصيل إلى فريق الدعم.",
                "The app's tools won't run",
                "The app's tools were set up but won't run on this phone. Try again; if it keeps happening, send the details to support.");
        text("folder_missing", "مكان الحفظ غير موجود",
                "تعذّر العثور على مكان الحفظ. حاول مرة أخرى.",
                "Save folder not found",
                "The save location couldn't be found. Try again.");
        text("sep_download", "تعذّر تنزيل أداة إزالة الموسيقى",
                "تُنزَّل هذه الأداة مرة واحدة فقط (نحو 67 ميجابايت). تأكد من اتصالك بالإنترنت، ثم اضغط «إعادة المحاولة» ليُستأنف تنزيلها من حيث توقف، وملفك محفوظ ولن يُنزَّل من جديد.",
                "Couldn't download the music remover",
                "This tool is downloaded only once (about 67 MB). Check your internet connection, then tap Try again to continue where it stopped. Your file is kept and won't be downloaded again.");
        text("sep_setup", "تعذّر تجهيز أداة إزالة الموسيقى",
                "لم يكتمل تجهيز أداة إزالة الموسيقى على هذا الهاتف. اضغط «إعادة المحاولة» لتُجهَّز من جديد، وإذا استمرت المشكلة فأرسل التفاصيل إلى فريق الدعم.",
                "Couldn't set up the music remover",
                "The music remover couldn't be set up on this phone. Tap Try again to set it up again; if it keeps happening, send the details to support.");
        text("sep_failed", "تعذّرت إزالة الموسيقى",
                "حدثت مشكلة أثناء إزالة الموسيقى من هذا الملف. حاول مرة أخرى، وإذا استمرت المشكلة فأرسل التفاصيل إلى فريق الدعم.",
                "Couldn't remove the music",
                "Something went wrong while removing the music from this file. Try again; if it keeps happening, send the details to support.");
        text("sep_memory", "ذاكرة الهاتف لا تكفي",
                "توقفت إزالة الموسيقى لأن ذاكرة الهاتف امتلأت. أغلق التطبيقات المفتوحة الأخرى، ثم اضغط «إعادة المحاولة» لتُستأنف من حيث توقفت.",
                "Not enough memory",
                "The music removal stopped because the phone's memory was full. Close other open apps, then tap Try again to continue where it stopped.");
        text("sep_no_audio", "لا يوجد صوت في هذا الملف",
                "هذا الملف بلا صوت، لذلك لا توجد فيه موسيقى لإزالتها.",
                "This file has no sound",
                "This file has no sound, so there's no music to remove.");
        text("sep_unreadable", "تعذّرت قراءة هذا الملف",
                "قد يكون الملف تالفًا، أو ليس ملف صوت أو فيديو. جرّب ملفًا آخر.",
                "Can't read this file",
                "The file may be damaged, or it isn't an audio or video file. Try another file.");
        text("file_missing", "الملف غير موجود",
                "لم يعد الملف الأصلي متاحًا؛ ربما حُذف أو نُقل. اختر الملف مرة أخرى من مكانه الحالي.",
                "File not found",
                "The original file is no longer available: it may have been deleted or moved. Choose the file again from where it is now.");
        text("unknown", "حدثت مشكلة",
                "تعذّر الإكمال هذه المرة. حاول مرة أخرى، وإذا استمرت المشكلة فأرسل التفاصيل إلى فريق الدعم.",
                "Something went wrong",
                "We couldn't finish this time. Try again; if it keeps happening, send the details to support.");
    }
}
