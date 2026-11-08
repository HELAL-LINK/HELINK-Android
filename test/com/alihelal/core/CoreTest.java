package com.alihelal.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Desktop tests for the engine: the same cases as the Go version, plus end-to-end runs. */
public class CoreTest {
    static int failures, checks;
    static final File TESTDATA = new File("test/testdata");

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object got, Object want, String what) {
        check(want == null ? got == null : want.equals(got), what + ": got <" + got + "> want <" + want + ">");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--preview-data")) {
            writePreviewData(new File(args[1]), new File(args[2]));
            return;
        }
        List<String> only = Arrays.asList(args);
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("parseLink", CoreTest::testParseLink);
        tests.put("safeFileBase", CoreTest::testSafeFileBase);
        tests.put("humanText", CoreTest::testHumanText);
        tests.put("language", CoreTest::testLanguage);
        tests.put("buildOptionsYouTube", CoreTest::testBuildOptionsYouTube);
        tests.put("shortAndDegraded", CoreTest::testShortAndDegraded);
        tests.put("buildPostSocial", CoreTest::testBuildPostSocial);
        tests.put("captionTitles", CoreTest::testCaptionTitles);
        tests.put("lenientJSON", CoreTest::testLenientJSON);
        tests.put("json", CoreTest::testJson);
        tests.put("progress", CoreTest::testProgress);
        tests.put("classify", CoreTest::testClassify);
        tests.put("goUrl", CoreTest::testGoUrl);
        tests.put("resolveShortLink", () -> rethrow(CoreTest::testResolveShortLink));
        tests.put("zip", () -> rethrow(CoreTest::testZip));
        tests.put("versions", CoreTest::testVersions);
        tests.put("sepPlans", () -> rethrow(CoreTest::testSepPlans));
        tests.put("fetchVerified", () -> rethrow(CoreTest::testFetchVerified));
        tests.put("e2eDownload", () -> rethrow(CoreTest::testEndToEndDownload));
        tests.put("e2ePost", () -> rethrow(CoreTest::testEndToEndPost));
        tests.put("e2eQueue", () -> rethrow(CoreTest::testQueue));
        tests.put("e2eNoMusic", () -> rethrow(CoreTest::testNoMusic));
        tests.put("e2eSetup", () -> rethrow(CoreTest::testSetup));
        tests.put("e2eUpdate", () -> rethrow(CoreTest::testUpdate));
        tests.put("ed25519", () -> rethrow(CoreTest::testEd25519));
        tests.put("appManifest", () -> rethrow(CoreTest::testAppManifest));
        tests.put("e2eAppUpdate", () -> rethrow(CoreTest::testAppUpdate));
        tests.put("playlist", () -> rethrow(CoreTest::testPlaylist));
        tests.put("e2eList", () -> rethrow(CoreTest::testEndToEndList));
        tests.put("clipPreview", () -> rethrow(CoreTest::testClipPreview));
        tests.put("previewServer", () -> rethrow(CoreTest::testPreviewServer));
        tests.put("e2eClip", () -> rethrow(CoreTest::testEndToEndClip));
        for (Map.Entry<String, Runnable> t : tests.entrySet()) {
            if (!only.isEmpty() && !only.contains(t.getKey())) continue;
            int before = failures;
            long start = System.currentTimeMillis();
            try {
                t.getValue().run();
            } catch (Throwable e) {
                failures++;
                System.out.println("FAIL: " + t.getKey() + " threw " + e);
                e.printStackTrace(System.out);
            }
            System.out.println((failures == before ? "ok   " : "FAIL ") + t.getKey() + " (" + (System.currentTimeMillis() - start) + " ms)");
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Body {
        void run() throws Exception;
    }

    static void rethrow(Body b) {
        try {
            b.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------------------------------- music removal: formats

    /** What ffprobe says, the format of the result, ffmpeg's options (as HELINK for Windows). */
    static void testSepPlans() throws Exception {
        I18n.set("ar");
        try {
            // An MP3 with a cover and tags.
            MediaSep.Probe p = MediaSep.parseProbe("{\"streams\":["
                    + "{\"index\":0,\"codec_type\":\"audio\",\"codec_name\":\"mp3\",\"bit_rate\":\"191873\",\"channels\":2,"
                    + "\"start_time\":\"0.025057\",\"duration\":\"30.04\",\"disposition\":{\"attached_pic\":0},\"tags\":{}},"
                    + "{\"index\":1,\"codec_type\":\"video\",\"codec_name\":\"png\",\"disposition\":{\"attached_pic\":1}}],"
                    + "\"format\":{\"format_name\":\"mp3\",\"duration\":\"30.041\",\"start_time\":\"0.025057\",\"bit_rate\":\"193000\","
                    + "\"tags\":{\"Title\":\"أغنية\",\"artist\":\"فنان\"}}}");
            check(!p.video && p.audio && p.cover == 1 && p.acodec.equals("mp3") && p.abitrate == 191873 && p.duration == 30.04
                    && p.title.equals("أغنية") && !p.titleOfStream, "mp3 probe");
            MediaSep.Plan pl = MediaSep.planFor(p, "MP3", "");
            check(!pl.video && pl.ext.equals("mp3") && pl.muxer.equals("mp3") && String.join(" ", pl.codec).equals("-c:a libmp3lame -b:a 192k")
                    && pl.cover && pl.title.equals("أغنية (بدون موسيقى)") && !pl.mono, "mp3 plan " + pl.codec);
            String args = String.join(" ", MediaSep.encodeArgs(p, pl, new File("/a/song.mp3"), new File("/a/out.mp3"), null));
            for (String want : new String[]{"-f f32le -ar 44100 -ac 2 -i pipe:0 -i file:/a/song.mp3 -map 0:a:0 -map 1:1 -c:v copy -disposition:v:0 attached_pic",
                    "-map_metadata 1 -map_metadata:s:a:0 1:s:a:0", "-metadata title=أغنية (بدون موسيقى)", "-id3v2_version 3 -f mp3 -y file:/a/out.mp3"}) {
                check(args.contains(want), "mp3 args lack <" + want + ">: " + args);
            }
            check(!args.contains("-progress"), "no progress lines from the pipe");
            // From the voices kept in a file: the same, read from it, with progress.
            String fromFile = String.join(" ", MediaSep.encodeArgs(p, pl, new File("/a/song.mp3"), new File("/a/out.mp3"), new File("/w/voice.f32")));
            check(fromFile.contains("-progress pipe:1 -nostats -f f32le -ar 44100 -ac 2 -i file:/w/voice.f32 -i file:/a/song.mp3"), fromFile);
            // A download in MP3 from what yt-dlp gave, at the chosen rate.
            MediaSep.Probe o = new MediaSep.Probe();
            o.audio = true;
            o.acodec = "opus";
            o.abitrate = 130000;
            o.title = "x";
            o.titleOfStream = true;
            pl = MediaSep.planFor(o, "webm", "128K");
            check(pl.ext.equals("mp3") && String.join(" ", pl.codec).equals("-c:a libmp3lame -b:a 128k") && !pl.titleOfStream
                    && pl.title.equals("x (بدون موسيقى)"), "download plan");
            long[][] rates = {{0, 192000}, {32000, 64000}, {129523, 128000}, {135000, 160000}, {192000, 192000}, {245000, 256000}, {999000, 320000}};
            for (long[] r : rates) eq(MediaSep.mp3Rate(r[0]), r[1], "mp3Rate " + r[0]);
            // Ogg keeps its title on the sound; a title already ending so is left alone.
            MediaSep.Probe og = new MediaSep.Probe();
            og.audio = true;
            og.acodec = "vorbis";
            og.channels = 1;
            og.title = "حديث";
            og.titleOfStream = true;
            pl = MediaSep.planFor(og, "ogg", "");
            check(pl.muxer.equals("ogg") && pl.codec.get(1).equals("libvorbis") && pl.mono && pl.titleOfStream
                    && pl.title.equals("حديث (بدون موسيقى)"), "ogg plan");
            args = String.join(" ", MediaSep.encodeArgs(new MediaSep.Probe(), pl, new File("a"), new File("b"), null));
            check(args.contains("-ac 1") && args.contains("-metadata:s:a:0 title=حديث (بدون موسيقى)"), "ogg args " + args);
            MediaSep.Probe twice = new MediaSep.Probe();
            twice.audio = true;
            twice.title = "حديث (بدون موسيقى)";
            pl = MediaSep.planFor(twice, "flac", "");
            check(pl.title.isEmpty() && pl.codec.get(1).equals("flac"), "title twice");
            // Kinds ffmpeg writes badly become MP3; sound-only MP4 becomes M4A.
            String[][] kinds = {{"wma", "mp3/mp3/libmp3lame"}, {"ape", "mp3/mp3/libmp3lame"}, {"m4a", "m4a/ipod/aac"},
                    {"mp4", "m4a/ipod/aac"}, {"aac", "aac/adts/aac"}, {"opus", "opus/ogg/libopus"}, {"wav", "wav/wav/pcm_s16le"},
                    {"aif", "aiff/aiff/pcm_s16be"}, {"webm", "webm/webm/libopus"}, {"mka", "mka/matroska/aac"}};
            for (String[] k : kinds) {
                MediaSep.Probe a = new MediaSep.Probe();
                a.audio = true;
                MediaSep.Plan x = MediaSep.planFor(a, k[0], "");
                eq(x.ext + "/" + x.muxer + "/" + x.codec.get(1), k[1], "sound " + k[0]);
            }
            // A video: its own kind of file, the sound joined afterwards; others become MKV.
            MediaSep.Probe v = MediaSep.parseProbe("{\"streams\":["
                    + "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\",\"start_time\":\"0\",\"disposition\":{\"attached_pic\":0}},"
                    + "{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\",\"bit_rate\":\"128000\",\"channels\":2,\"start_time\":\"0.5\",\"disposition\":{}},"
                    + "{\"index\":2,\"codec_type\":\"subtitle\",\"codec_name\":\"mov_text\"}],"
                    + "\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"10\",\"start_time\":\"0\",\"tags\":{\"title\":\"فيلم\"}}}");
            check(v.video && v.subs && v.duration == 10 && v.astart == 0.5, "video probe");
            String[][] vk = {{"mp4", "mp4/mp4/aac"}, {"M4V", "m4v/mp4/aac"}, {"mov", "mov/mov/aac"}, {"mkv", "mkv/matroska/aac"},
                    {"webm", "webm/webm/libopus"}, {"avi", "avi/avi/libmp3lame"}, {"flv", "mkv/matroska/aac"}, {"ts", "mkv/matroska/aac"},
                    {"3gp", "mp4/mp4/aac"}};
            for (String[] k : vk) {
                MediaSep.Plan x = MediaSep.planFor(v, k[0], "");
                check((x.ext + "/" + x.muxer + "/" + x.codec.get(1)).equals(k[1]) && x.video && x.offset == 0.5,
                        "video " + k[0] + ": " + x.ext + "/" + x.muxer + "/" + x.codec.get(1));
            }
            MediaSep.Plan vpl = MediaSep.planFor(v, "mp4", "");
            args = String.join(" ", MediaSep.encodeArgs(v, vpl, new File("/in.mp4"), new File("/voice.mka"), null));
            check(args.endsWith("-map 0:a:0 -c:a aac -b:a 128k -f matroska -y file:/voice.mka"), "voice args " + args);
            args = String.join(" ", MediaSep.muxArgs(vpl, new File("/in.mp4"), new File("/voice.mka"), new File("/out.mp4")));
            check(args.contains("-i file:/in.mp4 -itsoffset 0.500 -i file:/voice.mka -map 0:V -map 1:a:0 -map 0:s? -c copy -map_metadata 0 -map_metadata:s:a:0 0:s:a:0 -map_chapters 0")
                    && args.endsWith("-metadata title=فيلم (بدون موسيقى) -f mp4 -y file:/out.mp4"), "mux args " + args);
            MediaSep.Probe mk = new MediaSep.Probe();
            mk.video = true;
            mk.audio = true;
            mk.acodec = "opus";
            MediaSep.Plan mpl = MediaSep.planFor(mk, "mkv", "");
            check(mpl.codec.get(1).equals("libopus") && mpl.attach, "mkv keeps opus and fonts");
            long n = MediaSep.spaceNeeded(v, vpl, 100L << 20);
            check(n >= (100L << 20) + 2 * (10 * 128000 / 8) && n <= (100L << 20) + (40L << 20), "space " + n);
            eq(MediaSep.rawBytes(v), 441000L * 8, "the voices of 10 s");
            I18n.set("en");
            eq(MediaSep.suffix(), " (no music)", "suffix in English");
        } finally {
            I18n.set("ar");
        }
        // Files of the phone: sound and video by their type or name; the name without its extension.
        Host.Content f = new Host.Content();
        f.name = "درس الفجر.MP4";
        check(Engine.looksLikeMedia(f), "mp4 by name");
        f.name = "notes.txt";
        f.mime = "text/plain";
        check(!Engine.looksLikeMedia(f), "not a text file");
        f.mime = "audio/x-whatever";
        check(Engine.looksLikeMedia(f), "audio by type");
        eq(Engine.baseOf("درس الفجر.MP4"), "درس الفجر", "base");
        eq(Engine.baseOf("a:b?.mp3"), "a - b", "base made safe");
        // A music removal is kept in the list like a download.
        DownloadTask t = new DownloadTask();
        t.id = "d1";
        t.type = "sep";
        t.source = "content://media/external/audio/media/12";
        t.srcName = "درس.mp4";
        t.srcSize = 1234;
        t.title = "درس";
        t.base = "درس";
        t.kind = "video";
        t.noMusic = true;
        t.state = DownloadTask.PAUSED;
        DownloadTask back = DownloadTask.fromStore(J.asObj(Json.parse(Json.write(t.toStore()))));
        check(back.valid() && back.isSep() && back.source.equals(t.source) && back.noMusic && back.srcSize == 1234
                && back.srcName.equals("درس.mp4"), "stored sep task");
        Map<String, Object> page = back.toJson(false);
        check("sep".equals(page.get("type")) && "درس.mp4".equals(page.get("srcName")) && Boolean.TRUE.equals(page.get("noMusic")), "page " + page);
        t.source = "";
        check(!t.valid(), "an entry without its file is dropped");
        DownloadTask video = new DownloadTask();
        video.id = "x";
        video.url = "u";
        video.kind = "video";
        video.base = "b";
        video.state = DownloadTask.DONE;
        video.noMusic = true;
        check(DownloadTask.fromStore(J.asObj(Json.parse(Json.write(video.toStore())))).noMusic, "a video without music stays so");
    }

    // --------------------------------------------------------------------------- links

    static void testParseLink() {
        String V = Sites.KIND_VIDEO, P = Sites.KIND_PLAYLIST, PR = Sites.KIND_PROFILE, S = Sites.KIND_SHORT,
                O = Sites.KIND_OTHER, L = Sites.KIND_LIVE;
        Object[][] cases = {
                {"https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtube", V, Sites.FOUND},
                {"شوف الفيديو ده https://youtu.be/dQw4w9WgXcQ?si=abc123 جامد", "https://youtu.be/dQw4w9WgXcQ?si=abc123", "youtube", V, Sites.FOUND},
                {"youtube.com/shorts/dQw4w9WgXcQ", "https://youtube.com/shorts/dQw4w9WgXcQ", "youtube", V, Sites.FOUND},
                {"https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234&index=2", "https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234&index=2", "youtube", V, Sites.FOUND},
                {"https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share", "https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share", "youtube", V, Sites.FOUND},
                {"(https://youtu.be/dQw4w9WgXcQ).", "https://youtu.be/dQw4w9WgXcQ", "youtube", V, Sites.FOUND},
                {"https://www.youtube.com/playlist?list=PL123", "https://www.youtube.com/playlist?list=PL123", "youtube", P, Sites.FOUND},
                {"https://www.youtube.com/@SomeChannel", "https://www.youtube.com/@SomeChannel", "youtube", PR, Sites.FOUND},
                {"HTTP://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ", "https://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ", "youtube", V, Sites.FOUND},
                {"https://www.youtube.com/watch?v=short", "", "", "", Sites.INVALID},
                {"https://www.youtube.com/", "", "", "", Sites.INVALID},
                {"https://www.facebook.com/watch/?v=1234567890", "https://www.facebook.com/watch/?v=1234567890", "facebook", V, Sites.FOUND},
                {"https://www.facebook.com/reel/1234567890?mibextid=abc", "https://www.facebook.com/reel/1234567890?mibextid=abc", "facebook", V, Sites.FOUND},
                {"https://m.facebook.com/MahmoudKitchen/videos/1234567890/", "https://m.facebook.com/MahmoudKitchen/videos/1234567890/", "facebook", V, Sites.FOUND},
                {"https://fb.watch/abCDef12/", "https://fb.watch/abCDef12/", "facebook", S, Sites.FOUND},
                {"https://www.facebook.com/share/v/1AbCdEfGh/", "https://www.facebook.com/share/v/1AbCdEfGh/", "facebook", S, Sites.FOUND},
                {"https://www.facebook.com/share/r/1AbCdEfGh/", "https://www.facebook.com/share/r/1AbCdEfGh/", "facebook", S, Sites.FOUND},
                {"https://www.facebook.com/story.php?story_fbid=123&id=456", "https://www.facebook.com/story.php?story_fbid=123&id=456", "facebook", O, Sites.FOUND},
                {"https://www.facebook.com/MahmoudKitchen", "https://www.facebook.com/MahmoudKitchen", "facebook", PR, Sites.FOUND},
                {"https://www.facebook.com/photo/?fbid=123&set=a.456", "https://www.facebook.com/photo/?fbid=123&set=a.456", "facebook", O, Sites.FOUND},
                {"https://www.facebook.com/groups/123/permalink/456/", "https://www.facebook.com/groups/123/permalink/456/", "facebook", O, Sites.FOUND},
                {"https://web.facebook.com/", "", "", "", Sites.INVALID},
                {"https://www.instagram.com/reel/C1abcdEFGhi/?igsh=xyz", "https://www.instagram.com/reel/C1abcdEFGhi/?igsh=xyz", "instagram", V, Sites.FOUND},
                {"instagram.com/p/C1abcdEFGhi", "https://www.instagram.com/p/C1abcdEFGhi", "instagram", V, Sites.FOUND},
                {"https://www.instagram.com/sara.cooks/reel/C1abcdEFGhi/", "https://www.instagram.com/sara.cooks/reel/C1abcdEFGhi/", "instagram", V, Sites.FOUND},
                {"https://www.instagram.com/share/reel/BAabc123/", "https://www.instagram.com/share/reel/BAabc123/", "instagram", S, Sites.FOUND},
                {"https://www.instagram.com/sara.cooks/", "https://www.instagram.com/sara.cooks/", "instagram", PR, Sites.FOUND},
                {"https://www.instagram.com/reels/", "", "", "", Sites.INVALID},
                {"https://www.tiktok.com/@ahmed.ali/video/7412345678901234567?is_from_webapp=1", "https://www.tiktok.com/@ahmed.ali/video/7412345678901234567", "tiktok", V, Sites.FOUND},
                {"https://tiktok.com/@ahmed.ali/video/7412345678901234567", "https://www.tiktok.com/@ahmed.ali/video/7412345678901234567", "tiktok", V, Sites.FOUND},
                {"https://www.tiktok.com/@mona/photo/7400000000000000001", "https://www.tiktok.com/@mona/video/7400000000000000001", "tiktok", V, Sites.FOUND},
                {"https://m.tiktok.com/v/7412345678901234567.html", "https://www.tiktok.com/@/video/7412345678901234567", "tiktok", V, Sites.FOUND},
                {"https://vm.tiktok.com/ZMabc123/", "https://vm.tiktok.com/ZMabc123/", "tiktok", S, Sites.FOUND},
                {"https://www.tiktok.com/t/ZTRabc123/", "https://www.tiktok.com/t/ZTRabc123/", "tiktok", S, Sites.FOUND},
                {"https://www.tiktok.com/@ahmed.ali", "https://www.tiktok.com/@ahmed.ali", "tiktok", PR, Sites.FOUND},
                {"https://www.tiktok.com/@ahmed.ali/live", "https://www.tiktok.com/@ahmed.ali/live", "tiktok", L, Sites.FOUND},
                {"https://x.com/egfootball/status/1840000000000000000?s=46&t=abc", "https://x.com/egfootball/status/1840000000000000000", "x", V, Sites.FOUND},
                {"https://twitter.com/egfootball/status/1840000000000000000", "https://x.com/egfootball/status/1840000000000000000", "x", V, Sites.FOUND},
                {"https://mobile.twitter.com/i/web/status/1840000000000000000", "https://x.com/i/web/status/1840000000000000000", "x", V, Sites.FOUND},
                {"https://x.com/egfootball/status/1840000000000000000/photo/1", "https://x.com/egfootball/status/1840000000000000000", "x", V, Sites.FOUND},
                {"https://x.com/egfootball/status/1840000000000000000/video/2", "https://x.com/egfootball/status/1840000000000000000/video/2", "x", V, Sites.FOUND},
                {"https://fxtwitter.com/egfootball/status/1840000000000000000", "https://x.com/egfootball/status/1840000000000000000", "x", V, Sites.FOUND},
                {"https://t.co/AbCdEf123", "https://t.co/AbCdEf123", "x", O, Sites.FOUND},
                {"https://x.com/egfootball", "https://x.com/egfootball", "x", PR, Sites.FOUND},
                {"https://x.com/home", "", "", "", Sites.INVALID},
                {"https://www.dailymotion.com/video/x8abc", "", "", "", Sites.UNSUPPORTED},
                {"مرحبا", "", "", "", Sites.NONE},
                {"ابعتلي على ali@gmail.com وبعدين https://vm.tiktok.com/ZMabc123/", "https://vm.tiktok.com/ZMabc123/", "tiktok", S, Sites.FOUND},
                // Android share sheets add text around the link.
                {"Watch \"أغنية\" on YouTube\nhttps://youtu.be/dQw4w9WgXcQ?si=Xy", "https://youtu.be/dQw4w9WgXcQ?si=Xy", "youtube", V, Sites.FOUND},
                {"https://www.facebook.com/share/v/1AbC/?mibextid=w8EBqM", "https://www.facebook.com/share/v/1AbC/?mibextid=w8EBqM", "facebook", S, Sites.FOUND},
        };
        for (Object[] c : cases) {
            Sites.LinkResult r = Sites.parseLink((String) c[0]);
            boolean ok = r.result == (Integer) c[4] && r.link.url.equals(c[1]) && r.link.site.equals(c[2]) && r.link.kind.equals(c[3]);
            check(ok, "parseLink(" + c[0] + ") = " + r.link.url + " " + r.link.site + " " + r.link.kind + " " + r.result
                    + "; want " + c[1] + " " + c[2] + " " + c[3] + " " + c[4]);
        }
    }

    static void testGoUrl() {
        try {
            GoUrl u = GoUrl.parse("https://example.com/a b/ج?x=1#frag");
            eq(u.path, "/a b/ج", "decoded path");
            eq(u.toString(), "https://example.com/a%20b/%D8%AC?x=1#frag", "re-escaped");
            GoUrl u2 = GoUrl.parse("https://example.com/%D8%AC%20x?q=a+b&v=%41");
            eq(u2.toString(), "https://example.com/%D8%AC%20x?q=a+b&v=%41", "raw path kept");
            eq(u2.queryGet("q"), "a b", "query plus");
            eq(u2.queryGet("v"), "A", "query escape");
            boolean bad = false;
            try {
                GoUrl.parse("https://example.com/%zz");
            } catch (GoUrl.ParseException e) {
                bad = true;
            }
            check(bad, "bad escape rejected");
            eq(GoUrl.parse("https://h.com:8080/x").hostname(), "h.com", "hostname");
        } catch (GoUrl.ParseException e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------------------------------- text

    static void testSafeFileBase() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("أغنية \"تجربة\" | الحلقة 1: 100% كوميدي? <نسخة> *جديدة*", "أغنية 'تجربة' - الحلقة 1 - 100% كوميدي نسخة جديدة");
        cases.put("CON", "_CON");
        cases.put("  ...  ", "فيديو");
        cases.put("a/b\\c", "a - b - c");
        cases.put("\u202bنص\u202c مع علامات", "نص مع علامات");
        cases.put("trailing dots...", "trailing dots");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            eq(Text.safeFileBase(c.getKey(), 90), c.getValue(), "safeFileBase(" + c.getKey() + ")");
        }
        String lng = "ب".repeat(200);
        eq(Str.runeLen(Text.safeFileBase(lng, 50)), 50, "long title cut");
        String emoji = "😂".repeat(100);
        check(Text.limitBytes(emoji, 170).getBytes(StandardCharsets.UTF_8).length <= 170, "limitBytes emoji");
        check(!Text.limitBytes(emoji, 170).contains("\uFFFD"), "no broken surrogates");
        eq(Text.limitBytes("ب".repeat(100), 170), "ب".repeat(85), "limitBytes arabic");
    }

    static void testHumanText() {
        Map<Long, String> sizes = new LinkedHashMap<>();
        sizes.put(500L * 1024, "500 كيلوبايت");
        sizes.put(6_500_000L, "6.2 ميجابايت");
        sizes.put(85L * 1024 * 1024, "85 ميجابايت");
        sizes.put(1503238553L, "1.4 جيجابايت");
        sizes.put(2L * 1024 * 1024 * 1024, "2 جيجابايت");
        for (Map.Entry<Long, String> e : sizes.entrySet()) eq(Text.humanSize(e.getKey()), e.getValue(), "humanSize " + e.getKey());
        Map<Double, String> etas = new LinkedHashMap<>();
        etas.put(5.0, "متبقٍ بضع ثوانٍ");
        etas.put(40.0, "متبقٍ أقل من دقيقة");
        etas.put(70.0, "متبقٍ نحو دقيقة");
        etas.put(125.0, "متبقٍ نحو دقيقتين");
        etas.put(300.0, "متبقٍ نحو 5 دقائق");
        etas.put(900.0, "متبقٍ نحو 15 دقيقة");
        etas.put(3700.0, "متبقٍ نحو ساعة");
        etas.put(4800.0, "متبقٍ نحو ساعة و20 دقيقة");
        etas.put(7300.0, "متبقٍ نحو ساعتين");
        for (Map.Entry<Double, String> e : etas.entrySet()) eq(Text.humanETA(e.getKey()), e.getValue(), "humanETA " + e.getKey());
        eq(Text.humanDuration(3723), "1:02:03", "duration h");
        eq(Text.humanDuration(245), "4:05", "duration m");
        eq(Text.humanSpeed(2.3 * 1024 * 1024), "2.3 ميجابايت/ث", "speed");
        // Exactly halfway values round like Go (to even).
        eq(Text.humanSize((long) (6.25 * 1024 * 1024)), "6.2 ميجابايت", "half to even");
        // An Arabic phone must still get Western digits.
        java.util.Locale old = java.util.Locale.getDefault();
        java.util.Locale.setDefault(new java.util.Locale("ar", "EG"));
        try {
            eq(Text.humanDuration(245), "4:05", "duration on an Arabic phone");
            eq(Text.humanSize(85L * 1024 * 1024), "85 ميجابايت", "size on an Arabic phone");
        } finally {
            java.util.Locale.setDefault(old);
        }
        // English
        I18n.set("en");
        try {
            eq(Text.humanSize(85L * 1024 * 1024), "85 MB", "size en");
            eq(Text.humanSize(500L * 1024), "500 KB", "size en kb");
            eq(Text.humanSize(1503238553L), "1.4 GB", "size en gb");
            eq(Text.humanSpeed(2.3 * 1024 * 1024), "2.3 MB/s", "speed en");
            eq(Text.humanETA(5), "A few seconds left", "eta en seconds");
            eq(Text.humanETA(70), "About 1 minute left", "eta en 1 min");
            eq(Text.humanETA(300), "About 5 minutes left", "eta en minutes");
            eq(Text.humanETA(4800), "About 1 hour 20 min left", "eta en hours");
            eq(Text.humanETA(7300), "About 2 hours left", "eta en 2 hours");
            eq(Sites.name(Sites.TIKTOK), "TikTok", "site en");
            eq(Sites.name("other"), "the site", "unknown site en");
        } finally {
            I18n.set("ar");
        }
        eq(Sites.name(Sites.TIKTOK), "تيك توك", "site ar");
    }

    /** Words made when shown: an error or a download already created follows a change of language. */
    static void testLanguage() {
        rethrow(CoreTest::checkLanguage);
    }

    static void checkLanguage() throws Exception {
        UserError e = UserError.make("network").localize(Sites.INSTAGRAM);
        eq(e.title(), "تعذّر الوصول إلى إنستجرام", "network title ar");
        UserError d = UserError.make("disk");
        d.need = 300L << 20;
        d.free = 100L << 20;
        check(d.message().contains("300 ميجابايت") && d.message().contains("100 ميجابايت"), "disk message ar " + d.message());
        DownloadTask t = new DownloadTask();
        t.id = "x";
        t.url = "https://youtu.be/x";
        t.site = Sites.YOUTUBE;
        t.kind = "video";
        t.key = "vbest";
        t.label = "فيديو";
        t.base = "b";
        t.sizeBytes = 69L << 20;
        t.state = DownloadTask.RUNNING;
        t.done = 31L << 20;
        t.total = 69L << 20;
        t.speed = 2.4 * 1024 * 1024;
        t.eta = 40;
        Map<String, Object> ar = t.toJson(false);
        eq(J.str(ar, "sizeText") + "|" + J.str(ar, "doneText") + "|" + J.str(ar, "label") + "|" + J.str(ar, "siteName"),
                "69 ميجابايت|31 ميجابايت|فيديو|يوتيوب", "task in Arabic");
        I18n.set("en");
        try {
            eq(e.title(), "Can't reach Instagram", "same error in English");
            eq(UserError.make("extractor").localize("").title(), "The site changed something", "capitalised site en");
            check(d.message().startsWith("This file needs about 300 MB") && d.message().contains("100 MB"), "disk message en " + d.message());
            Map<String, Object> en = J.asObj(Json.parse(Json.write(t.toJson(false))));
            eq(J.str(en, "sizeText") + "|" + J.str(en, "doneText") + "|" + J.str(en, "speedText") + "|" + J.str(en, "etaText")
                    + "|" + J.str(en, "label") + "|" + J.str(en, "siteName"),
                    "69 MB|31 MB|2.4 MB/s|Less than a minute left|Video|YouTube", "same task in English");
            t.state = DownloadTask.ERROR;
            t.error = UserError.make("network").localize(Sites.YOUTUBE);
            DownloadTask back = DownloadTask.fromStore(J.asObj(Json.parse(Json.write(t.toStore()))));
            eq(J.str(J.obj(J.asObj(Json.parse(Json.write(back.toJson(false)))), "error"), "title"), "Can't reach YouTube", "saved error in English");
            I18n.set("ar");
            eq(J.str(J.obj(J.asObj(Json.parse(Json.write(back.toJson(false)))), "error"), "title"), "تعذّر الوصول إلى يوتيوب", "saved error back in Arabic");
            for (String code : new String[]{"login", "novideo", "network", "extractor", "bot", "disk", "unknown", "tools_missing"}) {
                for (String lang : new String[]{"ar", "en"}) {
                    I18n.set(lang);
                    UserError u = UserError.make(code).localize(Sites.X);
                    check(!u.title().isEmpty() && !u.message().isEmpty() && !(u.title() + u.message()).contains("{"), code + " " + lang);
                }
            }
        } finally {
            I18n.set("ar");
        }
    }

    // --------------------------------------------------------------------------- formats

    static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Formats.Info fixture(String name) {
        try {
            return Formats.Info.from(J.asObj(Json.parse(read(new File(TESTDATA, name)))));
        } catch (Json.ParseError e) {
            throw new RuntimeException(e);
        }
    }

    static Formats.QualityOption opt(List<Formats.QualityOption> opts, String k) {
        for (Formats.QualityOption o : opts) if (o.key.equals(k)) return o;
        return null;
    }

    static String keys(List<Formats.QualityOption> opts) {
        List<String> k = new ArrayList<>();
        for (Formats.QualityOption o : opts) k.add(o.key);
        return String.join(" ", k);
    }

    @SuppressWarnings("unchecked")
    static Object[] options(String name, String site) {
        try {
            return Formats.buildOptions(fixture(name), site);
        } catch (Formats.InfoException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static void testBuildOptionsYouTube() {
        Object[] r = options("youtube_like.json", Sites.YOUTUBE);
        Formats.VideoMeta meta = (Formats.VideoMeta) r[0];
        List<Formats.QualityOption> opts = (List<Formats.QualityOption>) r[1];
        eq(meta.thumbnail, "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", "thumbnail");
        eq(meta.durationText, "5:00", "duration");
        eq(meta.channel, "قناة القناة", "channel");
        eq(meta.siteName, "يوتيوب", "site name");
        eq(keys(opts), "v2160 v1440 v1080 v720 v480 v360 v240 v144 mp3-320 mp3-192 mp3-128", "keys");
        eq(opt(opts, "v1080").formatSpec, "137+140-1/bv*+ba/b", "v1080");
        eq(opt(opts, "v720").formatSpec, "136+140-1/bv*+ba/b", "v720");
        eq(opt(opts, "v360").formatSpec, "134+140-1/bv*+ba/b", "v360");
        eq(opt(opts, "v1440").formatSpec, "271+140-1/bv*+ba/b", "v1440");
        eq(opt(opts, "v2160").formatSpec, "313+140-1/bv*+ba/b", "v2160");
        eq(opt(opts, "mp3-192").formatSpec, "140-1/ba/b", "mp3");
        Formats.QualityOption o720 = opt(opts, "v720");
        check(o720.recommended && o720.compatNote.isEmpty() && o720.label.equals("720p") && o720.tag.equals("HD"), "720 option");
        Formats.QualityOption o2160 = opt(opts, "v2160");
        check(!o2160.compatNote.isEmpty() && o2160.label.equals("4K"), "2160 option");
        Formats.QualityOption o = opt(opts, "v1080");
        long wantSize = (long) (3500 * 1000 / 8 * 300) + (long) (129 * 1000 / 8 * 300);
        eq(o.sizeBytes, wantSize, "1080 size");
        check(o.parts.size() == 2 && o.parts.get(0).formatId.equals("137") && o.parts.get(1).isAudio, "parts");
        eq(o.sortSpec, "res:1080,vcodec:h264,acodec:aac", "sort spec");
        Formats.QualityOption a = opt(opts, "mp3-192");
        check(a.recommended && a.kind.equals("audio") && a.audioQuality.equals("192K") && a.sizeBytes == 300 * 192000 / 8
                && a.label.equals("192k"), "mp3 option");
        Formats.QualityOption b = opt(opts, "mp3-320");
        check(!b.recommended && b.audioQuality.equals("320K"), "mp3-320");
    }

    @SuppressWarnings("unchecked")
    static void testShortAndDegraded() {
        List<Formats.QualityOption> opts = (List<Formats.QualityOption>) options("short.json", Sites.YOUTUBE)[1];
        Formats.QualityOption o = opt(opts, "v1080");
        check(o != null && o.formatSpec.equals("137+140/bv*+ba/b") && o.label.equals("1080p"), "short 1080");
        check(opt(opts, "v1920") == null, "vertical classified by short side");
        opts = (List<Formats.QualityOption>) options("only18.json", Sites.YOUTUBE)[1];
        check(keys(opts).equals("v360 mp3-320 mp3-192 mp3-128") && opts.get(0).formatSpec.equals("18/b") && opts.get(0).recommended,
                "only18 " + keys(opts));
        Formats.QualityOption a = opt(opts, "mp3-192");
        check(a.formatSpec.equals("18/b") && a.parts.get(0).formatId.equals("18"), "audio from combined");
        String[][] lists = {{"playlist.json", "playlist"}, {"live.json", "live"}};
        for (String[] l : lists) {
            try {
                Formats.buildPost(read(new File(TESTDATA, l[0])), Sites.YOUTUBE);
                check(false, l[0] + " should fail");
            } catch (Formats.InfoException e) {
                eq(e.code, l[1], l[0]);
            } catch (Json.ParseError e) {
                check(false, l[0] + " parse " + e);
            }
        }
    }

    static Formats.Post post(String name, String site) {
        try {
            return Formats.buildPost(read(new File(TESTDATA, name)), site);
        } catch (Exception e) {
            throw new RuntimeException(name + ": " + e, e);
        }
    }

    static void testBuildPostSocial() {
        Formats.Post p = post("tiktok.json", Sites.TIKTOK);
        Formats.Item it = p.items.get(0);
        check(p.site.equals("tiktok") && p.items.size() == 1 && it.index == 0, "tiktok basics");
        eq(keys(it.options), "v1080 v720 v480 mp3-320 mp3-192 mp3-128", "tiktok keys");
        Formats.QualityOption o = it.option("v720");
        check(o.formatSpec.equals("h264_720p_912345-0/b") && o.recommended && o.sizeBytes == 2850000, "tiktok 720 " + o.formatSpec);
        o = it.option("v1080");
        check(o.formatSpec.equals("bytevc1_1080p_1512345-0/b") && !o.compatNote.isEmpty(), "tiktok 1080");
        eq(it.option("mp3-192").formatSpec, "h264_720p_912345-0/b", "tiktok mp3 from video");
        eq(it.meta.title, "ده أحلى فيديو هتشوفه النهارده 😂", "tiktok title");
        eq(it.meta.thumbnail, "https://p16.tiktokcdn.com/origin.jpeg", "tiktok thumb");
        eq(it.meta.siteName, "تيك توك", "tiktok site name");
        eq(it.meta.channel, "Ahmed Ali", "tiktok channel");

        p = post("tiktok_photo.json", Sites.TIKTOK);
        eq(keys(p.items.get(0).options), "mp3-320 mp3-192 mp3-128", "photo keys");
        o = p.items.get(0).option("mp3-192");
        check(o.formatSpec.equals("audio/ba/b") && o.recommended, "photo mp3");

        p = post("instagram_reel.json", Sites.INSTAGRAM);
        eq(keys(p.items.get(0).options), "v1080 v720 v480 mp3-320 mp3-192 mp3-128", "reel keys");
        eq(p.items.get(0).option("v720").formatSpec, "dash-720v+dash-audio/bv*+ba/b", "reel 720");
        eq(p.items.get(0).option("v480").formatSpec, "102/b", "reel 480");
        eq(p.items.get(0).meta.title, "إنستجرام - sara.cooks", "reel title");
        eq(p.items.get(0).meta.thumbnail, "https://scontent.cdninstagram.com/t320.jpg", "reel thumb");
        eq(p.items.get(0).meta.channel, "sara.cooks", "reel channel");

        p = post("instagram_carousel.json", Sites.INSTAGRAM);
        check(p.items.size() == 2 && p.items.get(0).index == 1 && p.items.get(1).index == 3 && !p.items.get(1).raw.isEmpty(),
                "carousel");
        // The raw text of an entry is exactly yt-dlp's, readable again.
        Map<String, Object> e1 = Json.parseObject(p.items.get(1).raw);
        check(e1 != null && J.num(e1, "playlist_index") == 3, "carousel raw entry");

        p = post("x_single.json", Sites.X);
        eq(keys(p.items.get(0).options), "v720 v360 v240 mp3-320 mp3-192 mp3-128", "x keys");
        o = p.items.get(0).option("v720");
        check(o.formatSpec.equals("hls-2176+hls-audio-128000-Audio/bv*+ba/b") && o.recommended, "x 720 " + o.formatSpec);
        eq(p.items.get(0).meta.title, "هدف رائع في الدقيقة 90", "x title");
        p = post("x_multi.json", Sites.X);
        check(p.site.equals("x") && p.items.size() == 2 && p.items.get(0).index == 1 && p.items.get(1).index == 2, "x multi");
        eq(p.items.get(1).meta.title, "هدف رائع في الدقيقة 90", "x multi title");

        p = post("x_gif.json", Sites.X);
        check(keys(p.items.get(0).options).equals("v240") && p.items.get(0).options.get(0).formatSpec.equals("http/bv*/b"), "gif");

        p = post("facebook_dash.json", Sites.FACEBOOK);
        eq(keys(p.items.get(0).options), "v720 v360 mp3-320 mp3-192 mp3-128", "fb keys");
        eq(p.items.get(0).option("v720").formatSpec, "1111v+3333a/bv*+ba/b", "fb 720");
        eq(p.items.get(0).meta.title, "وصفة الكشري المصري الأصلية في البيت", "fb title");
        p = post("facebook_sdhd.json", Sites.FACEBOOK);
        eq(keys(p.items.get(0).options), "vhd vsd mp3-320 mp3-192 mp3-128", "fb sdhd keys");
        o = p.items.get(0).option("vhd");
        check(o.formatSpec.equals("hd/b") && o.recommended, "fb hd");
        eq(p.items.get(0).option("mp3-192").formatSpec, "hd/b", "fb mp3");
        eq(p.items.get(0).meta.title, "فيسبوك - Cairo News", "fb fallback title");
    }

    static Formats.Info info(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return Formats.Info.from(m);
    }

    static void testCaptionTitles() {
        Formats.Info i = info("title", "Video by sara.cooks", "channel", "sara.cooks",
                "description", "أسهل طريقة لعمل البسبوسة في البيت 😍 #حلويات #رمضان\n\nالمقادير: ...");
        eq(Formats.buildMeta(i, Sites.INSTAGRAM).title, "أسهل طريقة لعمل البسبوسة في البيت 😍", "caption title");
        i = info("title", "Video by sara.cooks", "channel", "sara.cooks", "description", "#reels #explore");
        eq(Formats.buildMeta(i, Sites.INSTAGRAM).title, "إنستجرام - sara.cooks", "hashtag-only caption");
        String lng = "كلمة ".repeat(40);
        String got = Formats.firstLine(lng, 90);
        check(Str.runeLen(got) <= 91 && got.endsWith("…"), "firstLine " + got);
        Formats.Info x = info("title", "Egypt Football - Egypt Football", "uploader", "Egypt Football");
        eq(Formats.buildMeta(x, Sites.X).title, "Egypt Football", "x self title");
    }

    static void testLenientJSON() {
        String data = "{\"id\": 12345, \"title\": \"x\", \"duration\": \"61.5\", \"uploader\": null, \"is_live\": false,"
                + "\"formats\": [{\"format_id\": 18, \"width\": \"640\", \"height\": 360, \"vcodec\": \"avc1\", \"acodec\": \"mp4a\", \"tbr\": null}]}";
        try {
            Formats.Post p = Formats.buildPost(data, Sites.FACEBOOK);
            Formats.Item it = p.items.get(0);
            check(it.meta.id.equals("12345") && it.meta.duration == 61.5 && it.options.get(0).formatSpec.equals("18/b"), "lenient");
        } catch (Exception e) {
            check(false, "lenient threw " + e);
        }
    }

    static void testJson() {
        try {
            Object v = Json.parse("{\"a\":[1,2.5,\"x\\u00e9\\n\",true,null,{\"b\":-3e2}],\"big\":18400000000000000001,\"id\":1840000000000000000}");
            Map<String, Object> m = J.asObj(v);
            eq(J.str(m, "id"), "1840000000000000000", "19-digit id kept exactly");
            eq(Json.write(J.arr(m, "a").get(2)), "\"xé\\n\"", "string escape");
            check(J.arr(m, "a").get(4) == Json.NULL, "null");
            eq(Json.write(Json.obj("s", "\u2028<")), "{\"s\":\"\\u2028<\"}", "line separator escaped");
            Object nan = Json.parse("{\"x\": NaN, \"y\": -Infinity}");
            eq(J.num(J.asObj(nan), "x"), 0.0, "NaN read leniently");
            Json.Obj o = (Json.Obj) Json.parse("  {\"k\": {\"inner\": [1, 2]}, \"z\": 1}  ");
            eq(((Json.Obj) o.get("k")).raw(), "{\"inner\": [1, 2]}", "raw span");
            boolean bad = false;
            try {
                Json.parse("{\"a\":1,}");
            } catch (Json.ParseError e) {
                bad = true;
            }
            check(bad, "trailing comma rejected");
        } catch (Json.ParseError e) {
            check(false, "json threw " + e);
        }
    }

    // --------------------------------------------------------------------------- progress & errors

    static void testProgress() {
        String[] lines = {
                "NZLP|downloading|1024|3020458|NA|NA|NA|136",
                "NZLP|downloading|1566989|3020458|NA|1573699.32|0|136",
                "NZLP|finished|3020458|3020458|NA|1463961.02|NA|136",
                "NZLP|downloading|1024|NA|129542.5|45638.7|2|140",
                "NZLP|finished|129542|129542|NA|1592634.85|NA|140",
        };
        Progress.Tracker tr = new Progress.Tracker("video", Arrays.asList(
                new Formats.DlPart("136", 3_000_000, false), new Formats.DlPart("140", 130_000, true)));
        Progress.Overall last = null;
        List<Double> pcts = new ArrayList<>();
        for (String l : lines) {
            Progress.DlLine d = Progress.parseDownloadLine(l);
            check(d != null, "parsed " + l);
            last = tr.onDownload(d);
            pcts.add(last.percent);
        }
        for (int i = 1; i < pcts.size(); i++) check(pcts.get(i) >= pcts.get(i - 1) - 0.01, "monotonic " + pcts);
        check(last.percent == 100 && last.stage.equals("audio") && last.partIndex == 2, "final progress");
        check(pcts.get(1) >= 49 && pcts.get(1) <= 53, "midway " + pcts.get(1));
        String[] pl = Progress.parsePostLine("NZLPP|started|Merger");
        check(pl != null && pl[0].equals("started") && pl[1].equals("Merger"), "post line");
        Progress.Overall o = tr.onPost(pl[0], pl[1]);
        check(o.stage.equals("merge") && o.percent == -1, "post stage");
        String done = Progress.parseDoneLine("NZLDONE|/data/user/0/x/اسم الملف 100% - 720p.mp4");
        check(done != null && done.endsWith("720p.mp4"), "done line");
    }

    static void testClassify() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("ERROR: [generic] 'not a url' is not a valid URL", "invalid_url");
        cases.put("ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video", "private");
        cases.put("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.", "age");
        cases.put("ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies", "bot");
        cases.put("ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader", "unavailable");
        cases.put("ERROR: [youtube] abc: Join this channel to get access to members-only content like this video", "members");
        cases.put("ERROR: [youtube] abc: Unable to download API page: <urlopen error [WinError 11001] getaddrinfo failed> (caused by...)", "network");
        cases.put("ERROR: unable to download video data: HTTP Error 403: Forbidden", "forbidden");
        cases.put("ERROR: unable to download video data: HTTP Error 429: Too Many Requests", "ratelimit");
        cases.put("ERROR: [Errno 28] No space left on device", "disk");
        cases.put("ERROR: unable to open for writing: [Errno 13] Permission denied: '/x'", "permission");
        cases.put("ERROR: [youtube] abc: Requested format is not available. Use --list-formats for a list of available formats", "format");
        cases.put("ERROR: [youtube] abc: The uploader has not made this video available in your country", "geo");
        cases.put("ERROR: [youtube] abc: This live event will begin in 3 hours.", "upcoming");
        cases.put("WARNING: something odd\nERROR: [youtube] abc: Unable to extract yt initial data; please report this issue", "extractor");
        cases.put("ERROR: certificate verify failed: certificate is not yet valid", "clock");
        cases.put("ERROR: [twitter] 1840: No video could be found in this tweet", "novideo");
        cases.put("ERROR: [Instagram] C1abc: There is no video in this post", "novideo");
        cases.put("ERROR: [twitter] 1840: NSFW tweet requires authentication. Use --cookies, --cookies-from-browser, --username and --password", "login");
        cases.put("ERROR: [twitter] 1840: You are not authorized to view this protected tweet. Use --cookies, --cookies-from-browser", "private");
        cases.put("ERROR: [twitter] 1840: Requested tweet is unavailable", "unavailable");
        cases.put("ERROR: [Instagram] C1abc: Requested content is not available, rate-limit reached or login required. Use --cookies", "ig_limit");
        cases.put("ERROR: [Instagram] C1abc: Instagram sent an empty media response. Check if this post is accessible in your browser. Use --cookies", "ig_limit");
        cases.put("ERROR: [Instagram] C1abc: This content is only available for registered users who follow this account. Use --cookies", "private");
        cases.put("ERROR: [TikTok] 741: You do not have permission to view this post. Log into an account that has access. Use --cookies", "private");
        cases.put("ERROR: [TikTok] 741: This post may not be comfortable for some audiences. Log in for access. Use --cookies-from-browser", "login");
        cases.put("ERROR: [TikTok] 741: Your IP address is blocked from accessing this post", "geo");
        cases.put("ERROR: [TikTok] 741: Video not available, status code 10231", "unavailable");
        cases.put("ERROR: [facebook] 123: The video is not available, Facebook said: \"This content isn't available right now\"", "unavailable");
        cases.put("ERROR: [facebook] 123: Cannot parse data; please report this issue on  https://github.com/yt-dlp/yt-dlp/issues", "extractor");
        cases.put("ERROR: Unsupported URL: https://www.facebook.com/login/?next=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fv%2F1Ab", "login");
        cases.put("ERROR: Postprocessing: audio conversion failed: Output file #0 does not contain any stream", "noaudio");
        cases.put("something completely unexpected", "unknown");
        // Android
        cases.put("ERROR: [youtube] abc: Unable to download API page: <urlopen error [Errno 7] No address associated with hostname>", "network");
        cases.put("ERROR: unable to download video data: [Errno 103] Software caused connection abort", "network");
        cases.put("ERROR: unable to open for writing: [Errno 30] Read-only file system: '/x'", "permission");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            eq(UserError.classifyOutput(c.getKey()).code, c.getValue(), "classify(" + c.getKey() + ")");
        }
        check(UserError.classifyOutput("ERROR: HTTP Error 403: Forbidden").outdated, "403 triggers update");
        UserError e = UserError.make("private");
        check(!e.retry && !e.title().isEmpty() && !e.message().isEmpty(), "private");
        eq(UserError.classifyFor("ERROR: [Instagram] x: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>",
                Sites.INSTAGRAM).title(), "تعذّر الوصول إلى إنستجرام", "network title");
        e = UserError.classifyFor("ERROR: x: Unable to extract data", Sites.TIKTOK);
        check(e.code.equals("extractor") && e.title().equals("غيّر تيك توك طريقة عمله"), "extractor title " + e.title());
        eq(UserError.make("network").localize("").title(), "تعذّر الوصول إلى الموقع", "generic site");
        eq(UserError.classifyFor("ERROR: [TikTok] 741: Requested format is not available. No video formats found!", Sites.TIKTOK).code,
                "novideo", "no formats on social");
        e = UserError.classifyFor("ERROR: [youtube] abc: no supported javascript runtime, deno", Sites.FACEBOOK);
        check(e.code.equals("extractor") && !e.jsRuntime, "js runtime on other site");
        for (String code : new String[]{"login", "novideo", "noaudio", "profile", "unsupported_site", "ig_limit"}) {
            UserError u = UserError.make(code).localize(Sites.X);
            check(!u.code.equals("unknown") && !(u.title() + u.message()).contains("{site"), code);
        }
        String js = Json.write(UserError.make("private"));
        check(js.contains("\"retry\":false") && js.contains("\"action\":\"newLink\""), "error json " + js);
    }

    static void testVersions() {
        check(YtdlpTool.isNewer("2026.10.12.233056", "2026.10.10.233141"), "newer nightly");
        check(!YtdlpTool.isNewer("2026.10.10.233141", "2026.10.10.233141"), "same");
        check(YtdlpTool.isNewer("2026.10.10.1", "2026.10.10"), "nightly after stable");
        check(YtdlpTool.isNewer("2026.10.10", ""), "anything beats nothing");
    }

    // --------------------------------------------------------------------------- network pieces

    static HttpServer server(Map<String, HttpHandler> routes) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (Map.Entry<String, HttpHandler> r : routes.entrySet()) s.createContext(r.getKey(), r.getValue());
        s.start();
        return s;
    }

    static void reply(HttpExchange x, int code, String body, String... headers) throws IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) x.getResponseHeaders().add(headers[i], headers[i + 1]);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            OutputStream o = x.getResponseBody();
            o.write(b);
            o.close();
        }
        x.close();
    }

    static void testResolveShortLink() throws Exception {
        final List<String> agents = new ArrayList<>();
        Map<String, HttpHandler> routes = new HashMap<>();
        routes.put("/redirect", x -> {
            agents.add(x.getRequestHeaders().getFirst("User-Agent"));
            reply(x, 301, "", "Location", "/hop");
        });
        routes.put("/hop", x -> reply(x, 302, "", "Location", "https://www.tiktok.com/@mona/photo/7400000000000000001?_r=1"));
        routes.put("/page", x -> reply(x, 200, "<html><head><meta property=\"og:title\" content=\"x\"><meta property=\"og:url\" content=\"https://www.facebook.com/MahmoudKitchen/videos/1234567890/\"></head></html>"));
        routes.put("/canonical", x -> reply(x, 200, "<link rel=\"canonical\" href=\"https://www.instagram.com/reel/C1abcdEFGhi/\">"));
        routes.put("/nothing", x -> reply(x, 200, "<html></html>"));
        HttpServer s = server(routes);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            Map<String, String> cases = new LinkedHashMap<>();
            cases.put("/redirect", "https://www.tiktok.com/@mona/photo/7400000000000000001?_r=1");
            cases.put("/page", "https://www.facebook.com/MahmoudKitchen/videos/1234567890/");
            cases.put("/canonical", "https://www.instagram.com/reel/C1abcdEFGhi/");
            cases.put("/nothing", base + "/nothing");
            for (Map.Entry<String, String> c : cases.entrySet()) {
                eq(Sites.resolveShortLink(base + c.getKey(), new Cancel()), c.getValue(), "resolve " + c.getKey());
            }
            check(agents.size() == 1 && "facebookexternalhit/1.1".equals(agents.get(0)), "user agent " + agents);
            Sites.LinkResult l = Sites.parseLink(cases.get("/redirect"));
            check(l.link.url.equals("https://www.tiktok.com/@mona/video/7400000000000000001") && l.link.kind.equals("video"), "resolved link");
        } finally {
            s.stop(0);
        }
    }

    static void testZip() throws Exception {
        File py = new File("/home/claude/android-deps/ydl-orig/library/src/main/jniLibs/arm64-v8a/libpython.zip.so");
        if (!py.isFile()) {
            System.out.println("  (skipped: no python bundle)");
            return;
        }
        Path tmp = Files.createTempDirectory("ziptest");
        DesktopHost h = new DesktopHost(tmp.toFile(), null);
        File dest = new File(tmp.toFile(), "python");
        long[] last = {0};
        Zip.extract(py, dest, null, h, (done, total) -> last[0] = done, new Cancel());
        int[] links = {0}, files = {0};
        Files.walk(dest.toPath()).forEach(p -> {
            if (Files.isSymbolicLink(p)) links[0]++;
            else if (Files.isRegularFile(p)) files[0]++;
        });
        eq(links[0], 49, "symlinks");
        check(files[0] > 800, "files " + files[0]);
        check(Files.isRegularFile(dest.toPath().resolve("usr/lib/python3.12/os.py")), "stdlib unpacked");
        check(Files.readSymbolicLink(dest.toPath().resolve("usr/lib/libz.so")).toString().equals("libz.so.1.3.1"), "link target");
        check(last[0] > 40_000_000, "progress bytes " + last[0]);
        // Compare one file with java.util.zip.
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(py)) {
            byte[] want = z.getInputStream(z.getEntry("usr/lib/python3.12/os.py")).readAllBytes();
            byte[] got = Files.readAllBytes(dest.toPath().resolve("usr/lib/python3.12/os.py"));
            check(Arrays.equals(want, got), "content matches");
        }
        // The yt-dlp zipapp starts with "#!/usr/bin/env python3".
        File app = new File("assets/ytdlp.zip");
        File out = new File(tmp.toFile(), "y");
        Zip.extract(app, out, null, h, null, null);
        check(new File(out, "yt_dlp/version.py").isFile() && new File(out, "__main__.py").isFile(), "zipapp unpacked");
        // A path that tries to escape the folder is refused.
        File evil = new File(tmp.toFile(), "evil.zip");
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(evil))) {
            zo.putNextEntry(new java.util.zip.ZipEntry("../escape.txt"));
            zo.write("x".getBytes());
            zo.closeEntry();
        }
        boolean refused = false;
        try {
            Zip.extract(evil, new File(tmp.toFile(), "e"), null, h, null, null);
        } catch (IOException e) {
            refused = true;
        }
        check(refused && !new File(tmp.toFile(), "escape.txt").exists(), "zip slip refused");
        Zip.deleteTree(tmp.toFile());
    }

    /** A big download that breaks halfway continues, a wrong file is refused, a dead address is skipped. */
    static void testFetchVerified() throws Exception {
        byte[] data = new byte[3_000_000];
        new java.util.Random(7).nextBytes(data);
        String sum = sha256(data);
        final int[] calls = {0};
        final List<String> ranges = new ArrayList<>();
        Map<String, HttpHandler> routes = new HashMap<>();
        routes.put("/flaky", x -> {
            calls[0]++;
            String range = x.getRequestHeaders().getFirst("Range");
            ranges.add(String.valueOf(range));
            int start = range == null ? 0 : Integer.parseInt(range.replace("bytes=", "").replace("-", ""));
            int end = calls[0] == 1 ? 1_000_000 : data.length; // the first answer is cut short
            if (range != null) x.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
            x.sendResponseHeaders(range == null ? 200 : 206, data.length - start);
            try (OutputStream o = x.getResponseBody()) {
                o.write(data, start, end - start);
            } catch (IOException ignored) {
                // the cut
            }
            x.close();
        });
        routes.put("/dead", x -> reply(x, 404, ""));
        routes.put("/wrong", x -> {
            byte[] w = data.clone();
            w[5] ^= 1;
            x.sendResponseHeaders(200, w.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(w);
            }
            x.close();
        });
        HttpServer s = server(routes);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        Path tmp = Files.createTempDirectory("fetch");
        try {
            File dest = new File(tmp.toFile(), "libpython.zip");
            long[] last = {0};
            Http.fetchVerified(Arrays.asList(base + "/dead", base + "/flaky"), dest, data.length, sum, (d, t) -> last[0] = d, new Cancel());
            check(Arrays.equals(Files.readAllBytes(dest.toPath()), data), "content after resume");
            check(ranges.size() == 2 && ranges.get(0).equals("null") && ranges.get(1).equals("bytes=1000000-"), "resumed with a range " + ranges);
            eq(last[0], (long) data.length, "progress reached the end");
            check(!new File(dest.getPath() + ".part").exists(), "no partial file left");
            File bad = new File(tmp.toFile(), "bad.zip");
            boolean refused = false;
            try {
                Http.fetchVerified(Arrays.asList(base + "/wrong"), bad, data.length, sum, null, new Cancel());
            } catch (IOException e) {
                refused = e.getMessage().contains("checksum");
            }
            check(refused && !bad.exists(), "wrong file refused");
        } finally {
            s.stop(0);
            Zip.deleteTree(tmp.toFile());
        }
    }

    // --------------------------------------------------------------------------- end to end

    /** Records events and lets the test wait for one. */
    static final class Rec implements Engine.Events {
        final BlockingQueue<String[]> q = new LinkedBlockingQueue<>();

        @Override
        public void emit(String name, Object data) {
            q.add(new String[]{name, Json.write(data)});
        }

        /** Waits until download id reaches state (an "error" state fails unless asked for). */
        Map<String, Object> waitTask(String id, String state, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new AssertionError("timeout waiting for task " + id + " " + state);
                String[] e = q.poll(left, TimeUnit.MILLISECONDS);
                if (e == null || !e[0].equals("task")) continue;
                Map<String, Object> m = J.asObj(Json.parse(e[1]));
                if (state.equals("removed")) {
                    if (J.str(m, "op").equals("remove") && J.str(m, "id").equals(id)) return m;
                    continue;
                }
                Map<String, Object> t = J.obj(m, "task");
                if (t == null || !J.str(t, "id").equals(id)) continue;
                String st = J.str(t, "state");
                if (st.equals(state)) return t;
                if (st.equals("error")) throw new AssertionError("unexpected error: " + e[1]);
            }
        }

        Map<String, Object> waitForAny(String event, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new AssertionError("timeout waiting for " + event);
                String[] e = q.poll(left, TimeUnit.MILLISECONDS);
                if (e != null && e[0].equals(event)) {
                    Map<String, Object> m = J.asObj(Json.parse(e[1]));
                    if (!J.str(m, "status").equals("loading")) return m;
                }
            }
        }

        Map<String, Object> waitFor(String event, String status, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new AssertionError("timeout waiting for " + event + "/" + status);
                String[] e = q.poll(left, TimeUnit.MILLISECONDS);
                if (e == null) continue;
                if (!e[0].equals(event)) continue;
                Map<String, Object> m = J.asObj(Json.parse(e[1]));
                String st = J.str(m, "status");
                if (st.isEmpty()) st = J.str(m, "phase");
                if (st.equals(status)) return m;
                if (st.equals("error")) throw new AssertionError("unexpected error event: " + e[1]);
            }
        }
    }

    /** Serves files with Range support at a limited speed so progress is visible. */
    /** "path range" of every request the media server answered, and the bytes it sent per path. */
    static final List<String> served = java.util.Collections.synchronizedList(new ArrayList<>());
    static final Map<String, Long> sentBytes = new java.util.concurrent.ConcurrentHashMap<>();

    static HttpServer mediaServer(File dir, int bytesPerSec) throws IOException {
        Map<String, HttpHandler> routes = new HashMap<>();
        routes.put("/", x -> {
            String reqPath = x.getRequestURI().getPath().substring(1);
            File f = new File(dir, reqPath);
            served.add(reqPath + " " + x.getRequestHeaders().getFirst("Range"));
            if (!f.isFile()) {
                reply(x, 404, "");
                return;
            }
            long len = f.length(), start = 0, end = len - 1;
            String range = x.getRequestHeaders().getFirst("Range");
            int code = 200;
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-", 2);
                start = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) end = Math.min(end, Long.parseLong(p[1]));
                code = 206;
                x.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + len);
            }
            x.getResponseHeaders().add("Accept-Ranges", "bytes");
            x.getResponseHeaders().add("Content-Type", "application/octet-stream");
            long n = end - start + 1;
            x.sendResponseHeaders(code, n);
            try (RandomAccessFile r = new RandomAccessFile(f, "r"); OutputStream o = x.getResponseBody()) {
                r.seek(start);
                byte[] buf = new byte[16384];
                long left = n;
                while (left > 0) {
                    int k = r.read(buf, 0, (int) Math.min(buf.length, left));
                    if (k <= 0) break;
                    o.write(buf, 0, k);
                    left -= k;
                    sentBytes.merge(reqPath, (long) k, Long::sum);
                    try {
                        Thread.sleep(Math.max(1, k * 1000L / bytesPerSec));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } catch (IOException ignored) {
                // client went away (cancel test)
            }
            x.close();
        });
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        for (Map.Entry<String, HttpHandler> r : routes.entrySet()) s.createContext(r.getKey(), r.getValue());
        s.start();
        return s;
    }

    static File makeInfo(File tmp, int port, String extractor) throws Exception {
        File out = new File(tmp, "info.json");
        Process p = new ProcessBuilder("python3", "test/makeinfo.py", "/home/claude/ytest/media", String.valueOf(port),
                out.getAbsolutePath(), extractor).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException("makeinfo: " + log);
        return out;
    }

    /** The first picture stream: its kind, size and number of frames. */
    static String videoStream(File f) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,width,height,nb_frames", "-of", "csv=p=0", f.getAbsolutePath()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out;
    }

    static List<String> ffprobe(File f, String entries) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", entries, "-of", "csv=p=0",
                f.getAbsolutePath()).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return Str.fields(out);
    }

    @SuppressWarnings("unchecked")
    static void testEndToEndDownload() throws Exception {
        if (!new File("/home/claude/ytest/media").isDirectory()) {
            System.out.println("  (skipped: no local media)");
            return;
        }
        Path tmp = Files.createTempDirectory("e2e");
        HttpServer srv = mediaServer(new File("/home/claude/ytest/media"), 1_500_000);
        File save = new File(tmp.toFile(), "Downloads");
        try {
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), save);
            Engine app = new Engine(host, rec);
            Map<String, Object> init = app.init();
            eq(J.str(init, "screen"), "setup", "first start needs setup");
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            eq(J.str(app.init(), "screen"), "ready", "ready after setup");
            File infoFile = makeInfo(tmp.toFile(), srv.getAddress().getPort(), "youtube");
            app.setDevInfoJson(infoFile);

            app.fetch("https://youtu.be/TESTVID0001");
            Map<String, Object> info = rec.waitFor("info", "done", 60_000);
            Map<String, Object> item0 = J.asObj(J.arr(info, "items").get(0));
            List<String> keys = new ArrayList<>();
            for (Object o : J.arr(item0, "options")) keys.add(J.str(J.asObj(o), "key"));
            eq(String.join(",", keys), "v1080,v720,v360,mp3-320,mp3-192,mp3-128", "options");
            eq(J.str(item0, "selected"), "v720", "selected");
            eq(J.str(info, "siteName"), "يوتيوب", "site name");
            Map<String, Object> missed = J.obj(app.init(), "fetch");
            check(missed != null && J.str(missed, "status").equals("done") && J.num(missed, "seq") == J.num(info, "seq"),
                    "a page that missed the result can catch up");
            // Switching the language rewrites what is already on screen, and is remembered.
            app.setLang("en");
            Map<String, Object> en = J.asObj(Json.parse(Json.write(app.init())));
            Map<String, Object> again = J.obj(J.obj(en, "restore"), "info");
            Map<String, Object> o720 = J.asObj(J.arr(J.asObj(J.arr(again, "items").get(0)), "options").get(1));
            eq(J.str(again, "siteName") + "|" + J.str(o720, "desc") + "|" + J.str(en, "lang"),
                    "YouTube|Good quality, reasonable size|en", "post shown again in English");
            eq(new Prefs(new File(tmp.toFile(), "data/settings.json")).getString("lang"), "en", "language remembered");
            app.setLang("ar");
            eq(J.str(J.obj(J.obj(J.asObj(Json.parse(Json.write(app.init()))), "restore"), "info"), "siteName"), "يوتيوب", "back to Arabic");

            // 1) 720p: H.264 + AAC merged into MP4
            String id1 = J.str(app.download(0, "v720"), "id");
            check(!id1.isEmpty(), "download added");
            boolean sawVideo = false, sawAudio = false, sawMerge = false, sawQueuedOrRunning = false;
            Map<String, Object> done = null;
            long end = System.currentTimeMillis() + 90_000;
            while (done == null) {
                String[] e = rec.q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (e == null) throw new AssertionError("download timeout");
                if (!e[0].equals("task")) continue;
                Map<String, Object> t = J.obj(J.asObj(Json.parse(e[1])), "task");
                if (t == null || !J.str(t, "id").equals(id1)) continue;
                String st = J.str(t, "state");
                if (st.equals("queued") || st.equals("running")) sawQueuedOrRunning = true;
                if (st.equals("running")) {
                    String stage = J.str(t, "stage");
                    if (stage.equals("video")) sawVideo = true;
                    if (stage.equals("audio")) sawAudio = true;
                    if (stage.equals("merge")) sawMerge = true;
                } else if (st.equals("done")) {
                    done = t;
                } else if (st.equals("error")) {
                    throw new AssertionError("download error: " + e[1]);
                }
            }
            check(sawQueuedOrRunning, "reported while running");
            check(sawVideo && sawAudio && sawMerge, "stages video=" + sawVideo + " audio=" + sawAudio + " merge=" + sawMerge);
            Map<String, Object> res = J.obj(done, "result");
            File path = new File(J.str(res, "path"));
            check(path.getParentFile().equals(save) && path.getName().endsWith(" - 720p.mp4"), "path " + path);
            eq(J.str(res, "name"), "أغنية 'تجربة' - الحلقة 1 - 100% كوميدي نسخة جديدة - 720p.mp4", "file name");
            eq(J.num(done, "percent"), 100.0, "percent at the end");
            eq(String.join(",", ffprobe(path, "stream=codec_name")), "h264,aac", "streams");
            check(!new File(tmp.toFile(), "data/work/jobs/" + id1).exists(), "work folder cleaned");

            // 2) Same quality again gets a new name instead of overwriting.
            String id2 = J.str(app.download(0, "v720"), "id");
            check(!id2.equals(id1), "a finished download can be downloaded again");
            Map<String, Object> done2 = J.obj(rec.waitTask(id2, "done", 90_000), "result");
            check(J.str(done2, "path").endsWith(" - 720p (2).mp4"), "second path " + J.str(done2, "path"));

            // 3) MP3 at 128 kb/s
            String id3 = J.str(app.download(0, "mp3-128"), "id");
            Map<String, Object> done3 = J.obj(rec.waitTask(id3, "done", 90_000), "result");
            File p3 = new File(J.str(done3, "path"));
            check(p3.getName().endsWith(".mp3") && J.str(done3, "kind").equals("audio"), "audio " + done3);
            String br = String.join("", ffprobe(p3, "format=bit_rate"));
            check(br.startsWith("12") || br.startsWith("13"), "mp3 bitrate " + br);
            eq(J.str(app.init(), "lastKind"), "audio", "last kind");
            // The post stays available for a page that was recreated.
            check(J.obj(J.obj(app.init(), "restore"), "info") != null, "restore keeps the post");
            List<Object> list = J.arr(app.init(), "tasks");
            eq(list.size(), 3, "three finished downloads in the list");
            eq(J.str(J.asObj(list.get(0)), "id"), id3, "newest first");

            // 4) Cancel & delete in the middle of a download.
            String id4 = J.str(app.download(0, "v1080"), "id");
            long until = System.currentTimeMillis() + 30_000;
            while (true) {
                Map<String, Object> t = rec.waitTask(id4, "running", Math.max(1, until - System.currentTimeMillis()));
                if (J.num(t, "percent") > 0) break;
            }
            Thread.sleep(300);
            check(app.busy() && app.downloading(), "busy while downloading");
            app.delete(id4);
            rec.waitTask(id4, "removed", 30_000);
            Thread.sleep(300);
            check(!app.busy(), "not busy after delete");
            check(!new File(tmp.toFile(), "data/work/jobs/" + id4).exists(), "partial work removed after delete");
            File[] saved = save.listFiles();
            for (File f : saved) check(!f.getName().contains("1080p"), "partial file left: " + f.getName());
            check(!processAlive(tmp.toString()), "no yt-dlp left running");
            // Removing a finished download from the list keeps its file.
            app.delete(id1);
            rec.waitTask(id1, "removed", 5_000);
            check(path.isFile(), "file kept after removing it from the list");
            app.clearFinished();
            eq(J.arr(app.init(), "tasks").size(), 0, "list cleared");

            // 5) A link from another site is rejected without running anything.
            app.fetch("https://www.dailymotion.com/video/x8abc");
            String[] e;
            do {
                e = rec.q.poll(5, TimeUnit.SECONDS);
            } while (e != null && !e[0].equals("info"));
            Map<String, Object> m = e == null ? null : J.asObj(Json.parse(e[1]));
            check(m != null && "unsupported_site".equals(J.str(J.obj(m, "error"), "code")), "other site " + (e == null ? null : e[1]));
            app.shutdown();
        } finally {
            srv.stop(0);
            Zip.deleteTree(tmp.toFile());
        }
    }

    /** RMS of channel 0 of a sound file between two times (seconds), through ffmpeg. */
    static double rms(File f, double from, double to) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-i", f.getAbsolutePath(), "-f", "f32le", "-ac", "2",
                "-ar", "44100", "pipe:1").start();
        byte[] b = p.getInputStream().readAllBytes();
        p.waitFor();
        java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int a = (int) (from * 44100), z = Math.min(fb.capacity() / 2, (int) (to * 44100));
        double s = 0;
        for (int i = a; i < z; i++) s += (double) fb.get(2 * i) * fb.get(2 * i);
        return Math.sqrt(s / Math.max(1, z - a));
    }

    /** The music remover built for this computer by tools/test.sh (from native/sep). */
    static final File SEP_BIN = new File("build/sep/helinksep");

    /** Follows a download until it is done: the stages it went through (running), and the task. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> untilDone(Rec rec, String id, List<String> stages, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            String[] e = rec.q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (e == null) throw new AssertionError("timeout: " + id + " " + stages);
            if (!e[0].equals("task")) continue;
            Map<String, Object> t = J.obj(J.asObj(Json.parse(e[1])), "task");
            if (t == null || !J.str(t, "id").equals(id)) continue;
            String st = J.str(t, "state");
            if (st.equals("error")) throw new AssertionError("error: " + e[1]);
            if (st.equals("done")) return t;
            if (stages != null && st.equals("running") && (stages.isEmpty() || !stages.get(stages.size() - 1).equals(J.str(t, "stage")))) {
                stages.add(J.str(t, "stage"));
            }
        }
    }

    /**
     * Without music, end to end, with the real voice network (Kim_Vocal_2) and the music
     * remover built for this computer: the remover is downloaded (the first addresses are
     * gone), converted and checked, and HELINK 1.4's remover deleted; an MP3 and a video lose
     * their music and keep the rest; a removal paused half-way continues from its checkpoint;
     * finished downloads and files of the phone get copies without music; a phone short of
     * memory uses smaller chunks; one removal at a time, downloads go on meanwhile.
     * Needs HELINK_SEP_ARCHIVES=dir with Kim_Vocal_2.onnx.
     */
    @SuppressWarnings("unchecked")
    static void testNoMusic() throws Exception {
        String dir = System.getenv("HELINK_SEP_ARCHIVES");
        File onnx = dir == null ? null : new File(dir, "Kim_Vocal_2.onnx");
        if (!new File("/home/claude/ytest/media").isDirectory() || onnx == null || !onnx.isFile() || !SEP_BIN.canExecute()) {
            System.out.println("  (skipped: needs the local media, HELINK_SEP_ARCHIVES and " + SEP_BIN + ")");
            return;
        }
        Path tmp = Files.createTempDirectory("e2e-nomusic");
        File media = new File(tmp.toFile(), "media"), models = new File(tmp.toFile(), "models");
        media.mkdirs();
        models.mkdirs();
        for (File f : new File("/home/claude/ytest/media").listFiles()) Files.copy(f.toPath(), new File(media, f.getName()).toPath());
        // The post's sound: speech over music (music alone for the first 3 seconds).
        File mix = new File(TESTDATA, "sep/speech_music.m4a");
        Files.copy(mix.toPath(), new File(media, "a_aac.m4a").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Process enc = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-y", "-i", mix.getAbsolutePath(), "-c:a", "libopus",
                "-b:a", "128k", new File(media, "a_opus.webm").getAbsolutePath()).inheritIO().start();
        check(enc.waitFor() == 0, "opus copy of the test sound");
        // A video with that sound, as a file of the phone.
        File phoneVideo = new File(tmp.toFile(), "phone-video.mp4");
        enc = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-y", "-i", new File(media, "v360_avc.mp4").getAbsolutePath(),
                "-i", mix.getAbsolutePath(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-shortest", phoneVideo.getAbsolutePath())
                .inheritIO().start();
        check(enc.waitFor() == 0, "the phone's video");
        Files.createSymbolicLink(new File(models, "Kim_Vocal_2.onnx").toPath(), onnx.getAbsoluteFile().toPath());
        HttpServer srv = mediaServer(media, 1_500_000);
        HttpServer msrv = mediaServer(models, 400_000_000);
        SepTool.Source[] saved = SepTool.sources;
        SepTool.Source real = saved[0];
        String mbase = "http://127.0.0.1:" + msrv.getAddress().getPort() + "/";
        SepTool.sources = new SepTool.Source[]{
                new SepTool.Source("gone.onnx", 1000, "00", "11", mbase + "gone.onnx"),
                new SepTool.Source(real.file, real.size, real.sha256, real.weightsSha256, mbase + "moved.onnx", mbase + "Kim_Vocal_2.onnx"),
        };
        File save = new File(tmp.toFile(), "Downloads");
        try {
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), save);
            // What HELINK 1.4's music remover left: deleted when the new one is set up.
            File sepDir = new File(tmp.toFile(), "data/sep");
            sepDir.mkdirs();
            Files.writeString(new File(sepDir, "spleeter-2stems.hlw").toPath(), "old");
            Files.writeString(new File(sepDir, "spleeter-2stems.hlw.ok").toPath(), "old");
            Engine app = new Engine(host, rec);
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            check(!J.str(app.init(), "sepSizeText").isEmpty(), "first time: the size of the music remover is shown");
            check(J.bool(app.init(), "sepFiles"), "the page may offer files of the phone");
            app.setDevInfoJson(makeInfo(tmp.toFile(), srv.getAddress().getPort(), "youtube"));
            app.fetch("https://youtu.be/TESTVID0001");
            rec.waitFor("info", "done", 60_000);
            String title = "أغنية 'تجربة' - الحلقة 1 - 100% كوميدي نسخة جديدة";

            // 1) an MP3 without music, the first time: the remover is set up, then used
            String id = J.str(app.download(0, "mp3-128", true), "id");
            check(J.bool(app.init(), "noMusic"), "the switch is remembered");
            List<String> stages = new ArrayList<>();
            Map<String, Object> done = untilDone(rec, id, stages, 900_000);
            check(J.bool(done, "noMusic"), "the task says no music");
            String seen = String.join(",", stages);
            check(seen.contains("sepmodel") && seen.contains("sepprep") && seen.contains("separate") && seen.endsWith("save"),
                    "stages " + seen);
            check(seen.indexOf("convert") < seen.indexOf("sepmodel"), "the MP3 first, then the remover: " + seen);
            Map<String, Object> res = J.obj(done, "result");
            File out = new File(J.str(res, "path"));
            eq(J.str(res, "name"), title + " (بدون موسيقى).mp3", "file name");
            eq(String.join(",", ffprobe(out, "stream=codec_name")), "mp3", "an MP3");
            String tag = String.join(" ", ffprobe(out, "format_tags=title"));
            if (tag.startsWith("\"") && tag.endsWith("\"")) tag = tag.substring(1, tag.length() - 1).replace("\"\"", "\""); // CSV
            eq(tag, "أغنية \"تجربة\" | الحلقة 1: 100% كوميدي? <نسخة> *جديدة* (بدون موسيقى)", "title tag says no music");
            eq(String.join(" ", ffprobe(out, "format_tags=artist")), "قناة التجربة", "artist tag kept");
            String br = String.join("", ffprobe(out, "format=bit_rate"));
            check(br.startsWith("12") || br.startsWith("13"), "same bit rate as chosen: " + br);
            double dur = Double.parseDouble(String.join("", ffprobe(out, "format=duration")));
            check(Math.abs(dur - 12.0) < 0.2, "same length: " + dur);
            double musicIn = rms(mix, 0.5, 2.5), musicOut = rms(out, 0.5, 2.5);
            double speechIn = rms(mix, 4, 11), speechOut = rms(out, 4, 11);
            System.out.printf("  music alone: %.4f -> %.5f (%.1f dB); with speech: %.4f -> %.4f%n", musicIn, musicOut,
                    20 * Math.log10(musicOut / musicIn), speechIn, speechOut);
            check(musicOut < musicIn / 30, "the music is gone: " + musicIn + " -> " + musicOut);
            check(speechOut > speechIn / 3, "the speech stays: " + speechIn + " -> " + speechOut);
            check(!new File(save, title + ".mp3").exists(), "no MP3 with music is saved");
            String[] left = sepDir.list();
            eq(left == null ? "" : String.join(",", new java.util.TreeSet<>(Arrays.asList(left))),
                    SepTool.FILE + "," + SepTool.FILE + ".ok", "only the weights are left (HELINK 1.4's remover is gone)");
            check(new File(sepDir, SepTool.FILE).length() == SepTool.WEIGHTS_SIZE, "the remover's weights are kept");
            eq(J.str(app.init(), "sepSizeText"), "", "nothing more to download");
            check(!new File(tmp.toFile(), "data/work/jobs/" + id).exists(), "work folder cleaned");
            check(host.logs().contains("dimt 256"), "UVR's chunks when there is memory");

            // 2) a video without music: the picture as it was, the sound without music
            String vid = J.str(app.download(0, "v720", true), "id");
            Map<String, Object> vdone = J.obj(untilDone(rec, vid, null, 900_000), "result");
            File vout = new File(J.str(vdone, "path"));
            eq(J.str(vdone, "name"), title + " - 720p (بدون موسيقى).mp4", "video file name");
            eq(String.join(",", ffprobe(vout, "stream=codec_type,codec_name")), "h264,video,aac,audio", "picture and sound");
            eq(videoStream(vout), videoStream(new File(media, "v720_avc.mp4")), "the picture is copied as it was");
            double vm = rms(vout, 0.5, 2.5);
            check(vm < musicIn / 30, "the video's music is gone: " + vm);
            check(rms(vout, 4, 7.5) > speechIn / 3, "the video's speech stays");

            // 3) paused while removing the music: it continues from its checkpoint
            String id2 = J.str(app.download(0, "mp3-192", true), "id");
            long until = System.currentTimeMillis() + 300_000;
            while (true) {
                Map<String, Object> t = rec.waitTask(id2, "running", Math.max(1, until - System.currentTimeMillis()));
                check(!J.str(t, "stage").equals("sepmodel"), "the remover is not downloaded twice");
                if (J.str(t, "stage").equals("separate") && J.num(t, "percent") >= 50) break;
            }
            app.pause(id2);
            rec.waitTask(id2, "paused", 30_000);
            File job2 = new File(tmp.toFile(), "data/work/jobs/" + id2);
            check(new File(job2, "src.done").isFile(), "the MP3 is kept while paused");
            check(new File(job2, "voice/voice.state").isFile() && new File(job2, "voice/voice.f32").length() > 0, "a checkpoint is kept");
            int requests = served.size();
            int logsBefore = host.logList.size();
            app.resume(id2);
            Map<String, Object> done2 = J.obj(rec.waitTask(id2, "done", 300_000), "result");
            eq(served.size(), requests, "nothing downloaded again after resuming");
            String after = String.join("\n", host.logList.subList(logsBefore, host.logList.size()));
            check(after.contains("continuing from"), "continued from the checkpoint: " + after);
            File out2 = new File(J.str(done2, "path"));
            String br2 = String.join("", ffprobe(out2, "format=bit_rate"));
            check(br2.startsWith("19"), "192 kb/s: " + br2);
            dur = Double.parseDouble(String.join("", ffprobe(out2, "format=duration")));
            check(Math.abs(dur - 12.0) < 0.2, "same length after resuming: " + dur);
            check(rms(out2, 0.5, 2.5) < musicIn / 30 && rms(out2, 4, 11) > speechIn / 3, "resumed: the music is gone, the speech stays");
            check(!job2.exists(), "work folder cleaned after resuming");

            // 4) the same MP3 with its music, then its music removed from the list
            String id3 = J.str(app.download(0, "mp3-192", false), "id");
            check(!id3.equals(id2), "with music is a different download");
            Map<String, Object> done3 = J.obj(rec.waitTask(id3, "done", 120_000), "result");
            check(!J.str(done3, "name").contains("بدون"), "normal name " + J.str(done3, "name"));
            check(!J.bool(app.init(), "noMusic"), "the switch is remembered off");
            Map<String, Object> rm = app.removeMusic(id3);
            String id4 = J.str(rm, "id");
            check(!id4.isEmpty() && !id4.equals(id3), "a new entry removes the music: " + rm);
            eq(J.str(app.removeMusic(id3), "id"), id4, "asked again: the same entry");
            List<String> stages4 = new ArrayList<>();
            Map<String, Object> t4 = untilDone(rec, id4, stages4, 300_000);
            check(String.join(",", stages4).contains("separate"), "stages " + stages4);
            eq(J.str(t4, "type"), "sep", "a music removal");
            Map<String, Object> done4 = J.obj(t4, "result");
            String n4 = J.str(done4, "name");
            // (the Download folder has that name already: the system adds a number)
            check(n4.startsWith(title + " (بدون موسيقى)") && n4.endsWith(".mp3"), "name of the copy: " + n4);
            check(String.join("", ffprobe(new File(J.str(done4, "path")), "format=bit_rate")).startsWith("19"), "its own quality kept");
            check(new File(J.str(done3, "path")).isFile(), "the file with music stays");

            // 5) files of the phone, on a phone short of memory: a sound, a video and something else
            host.mem = new long[]{300L << 20, 2000L << 20};
            Host.Content opus = new Host.Content();
            opus.uri = new File(media, "a_opus.webm").getAbsolutePath();
            opus.name = "تلاوة.webm";
            opus.mime = "audio/webm";
            opus.size = new File(media, "a_opus.webm").length();
            Host.Content video = new Host.Content();
            video.uri = phoneVideo.getAbsolutePath();
            video.name = "مقطع.MP4";
            video.mime = "video/mp4";
            video.size = phoneVideo.length();
            Host.Content text = new Host.Content();
            text.uri = "/etc/hostname";
            text.name = "notes.txt";
            text.mime = "text/plain";
            int logs5 = host.logList.size();
            Map<String, Object> added = app.addFiles(Arrays.asList(opus, video, text));
            List<Object> ids = J.arr(added, "ids");
            check(ids != null && ids.size() == 2 && J.num(added, "skipped") == 1, "two added, one skipped: " + added);
            Map<String, Object> d5 = J.obj(untilDone(rec, (String) ids.get(0), null, 300_000), "result");
            eq(J.str(d5, "name"), "تلاوة (بدون موسيقى).webm", "the sound keeps its kind");
            eq(String.join(",", ffprobe(new File(J.str(d5, "path")), "stream=codec_name")), "opus", "still Opus");
            Map<String, Object> t6 = untilDone(rec, (String) ids.get(1), null, 300_000);
            Map<String, Object> d6 = J.obj(t6, "result");
            eq(J.str(d6, "name"), "مقطع (بدون موسيقى).mp4", "the video keeps its kind");
            eq(J.str(t6, "kind"), "video", "a video");
            eq(String.join(",", ffprobe(new File(J.str(d6, "path")), "stream=codec_type")), "video,audio", "picture and sound");
            check(rms(new File(J.str(d6, "path")), 0.5, 2.5) < musicIn / 30, "the phone video's music is gone");
            String logs5s = String.join("\n", host.logList.subList(logs5, host.logList.size()));
            check(logs5s.contains("dimt 64"), "smaller chunks when memory is short: " + logs5s);
            check(phoneVideo.isFile() && new File(media, "a_opus.webm").isFile(), "the phone's files stay as they are");
            check(host.forgotten.contains(opus.uri) && host.forgotten.contains(video.uri), "their permissions are given back: " + host.forgotten);
            host.mem = new long[]{-1, -1};

            // 6) a file that is gone meanwhile
            Host.Content gone = new Host.Content();
            gone.uri = new File(tmp.toFile(), "not-there.mp3").getAbsolutePath();
            gone.name = "not-there.mp3";
            gone.mime = "audio/mpeg";
            String gid = (String) J.arr(app.addFiles(Arrays.asList(gone)), "ids").get(0);
            Map<String, Object> ge = rec.waitTask(gid, "error", 60_000);
            eq(J.str(J.obj(ge, "error"), "code"), "file_missing", "a file that is gone");
            check(!J.bool(J.obj(ge, "error"), "retry"), "no use trying again");
            // ... and one that is not sound at all
            File broken = new File(tmp.toFile(), "broken.mp3");
            byte[] junk = new byte[50_000];
            new java.util.Random(1).nextBytes(junk);
            Files.write(broken.toPath(), junk);
            Host.Content bad = new Host.Content();
            bad.uri = broken.getAbsolutePath();
            bad.name = "broken.mp3";
            bad.mime = "audio/mpeg";
            String bid = (String) J.arr(app.addFiles(Arrays.asList(bad)), "ids").get(0);
            Map<String, Object> be = rec.waitTask(bid, "error", 60_000);
            eq(J.str(J.obj(be, "error"), "code"), "sep_unreadable", "a file that is not sound");

            // 7) two at once, the remover not there yet (slower this time): one sets it up while
            // the other waits, and a plain download still runs meanwhile
            Zip.deleteTree(sepDir);
            HttpServer slow = mediaServer(models, 8_000_000);
            SepTool.sources = new SepTool.Source[]{new SepTool.Source(real.file, real.size, real.sha256, real.weightsSha256,
                    "http://127.0.0.1:" + slow.getAddress().getPort() + "/Kim_Vocal_2.onnx")};
            try {
                String a = J.str(app.download(0, "mp3-320", true), "id");
                String b = J.str(app.download(0, "v360", true), "id");
                List<String> stagesA = new ArrayList<>(), stagesB = new ArrayList<>();
                boolean bothInSep = false;
                String c = null;
                int finished = 0;
                long until3 = System.currentTimeMillis() + 900_000;
                Map<String, String> stage = new HashMap<>();
                while (finished < 3) {
                    String[] e = rec.q.poll(Math.max(1, until3 - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (e == null) throw new AssertionError("timeout (two at once): " + stagesA + " " + stagesB);
                    if (!e[0].equals("task")) continue;
                    Map<String, Object> t = J.obj(J.asObj(Json.parse(e[1])), "task");
                    if (t == null) continue;
                    String tid = J.str(t, "id"), st = J.str(t, "state");
                    if (!tid.equals(a) && !tid.equals(b) && !tid.equals(c)) continue;
                    if (st.equals("error")) throw new AssertionError("error: " + e[1]);
                    if (st.equals("done")) finished++;
                    stage.put(tid, st.equals("running") ? J.str(t, "stage") : st);
                    if (!tid.equals(c)) {
                        List<String> l = tid.equals(a) ? stagesA : stagesB;
                        if (st.equals("running") && (l.isEmpty() || !l.get(l.size() - 1).equals(J.str(t, "stage")))) l.add(J.str(t, "stage"));
                    }
                    String sa = stage.getOrDefault(a, ""), sb = stage.getOrDefault(b, "");
                    if (c == null && sa.startsWith("sep") && sb.startsWith("sep")) {
                        // Both wait for the remover: a third download is not held up by them.
                        bothInSep = true;
                        c = J.str(app.download(0, "mp3-128"), "id");
                    }
                }
                System.out.println("  a: " + stagesA + "\n  b: " + stagesB);
                check(bothInSep, "both waited for the remover");
                check(stagesA.contains("sepwait") || stagesB.contains("sepwait"), "one removal at a time");
            } finally {
                slow.stop(0);
            }
            eq(new File(sepDir, SepTool.FILE).length(), SepTool.WEIGHTS_SIZE, "set up once more");
            app.shutdown();
        } finally {
            SepTool.sources = saved;
            srv.stop(0);
            msrv.stop(0);
            Zip.deleteTree(tmp.toFile());
        }
    }

    static boolean processAlive(String marker) {
        return ProcessHandle.allProcesses().anyMatch(p -> p.info().commandLine().map(c -> c.contains(marker)).orElse(false));
    }

    @SuppressWarnings("unchecked")
    static void testEndToEndPost() throws Exception {
        if (!new File("/home/claude/ytest/media").isDirectory()) {
            System.out.println("  (skipped: no local media)");
            return;
        }
        Path tmp = Files.createTempDirectory("e2epost");
        HttpServer srv = mediaServer(new File("/home/claude/ytest/media"), 3_000_000);
        File save = new File(tmp.toFile(), "Downloads");
        try {
            File src = makeInfo(tmp.toFile(), srv.getAddress().getPort(), "youtube");
            Map<String, Object> entry = J.asObj(Json.parse(read(src)));
            List<Object> entries = new ArrayList<>();
            for (int n = 1; n <= 2; n++) {
                Map<String, Object> e = new LinkedHashMap<>(entry);
                e.put("id", "184000000000000000" + n);
                e.put("title", "Egypt Football - هدف #" + n);
                e.put("extractor", "twitter");
                e.put("extractor_key", "Twitter");
                e.put("webpage_url", "https://x.com/egfootball/status/1840000000000000000");
                e.remove("_type");
                entries.add(e);
            }
            Map<String, Object> post = Json.obj("_type", "playlist", "id", "1840000000000000000", "title", "Egypt Football - هدفين",
                    "extractor", "twitter", "extractor_key", "Twitter", "webpage_url", "https://x.com/egfootball/status/1840000000000000000",
                    "entries", entries);
            File postFile = new File(tmp.toFile(), "post.json");
            Engine.writeText(postFile, Json.write(post));

            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), save);
            Engine app = new Engine(host, rec);
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            app.setDevInfoJson(postFile);
            app.fetch("https://x.com/egfootball/status/1840000000000000000?s=20");
            Map<String, Object> info = rec.waitFor("info", "done", 60_000);
            List<Object> items = J.arr(info, "items");
            check(items.size() == 2 && J.str(info, "site").equals("x") && J.str(info, "siteName").equals("إكس"), "post info " + items.size());
            eq(J.num(J.asObj(items.get(1)), "index"), 2.0, "second item index");
            // Both at once: the two run side by side.
            String a = J.str(app.download(1, "v720"), "id");
            String b = J.str(app.download(0, "mp3-192"), "id");
            Map<String, Object> done = J.obj(rec.waitTask(a, "done", 90_000), "result");
            String p = J.str(done, "path");
            check(p.endsWith("Egypt Football - هدف - 2 - 720p.mp4") && new File(p).length() > 0, "post download " + p);
            Map<String, Object> list0 = null;
            for (Object o : J.arr(app.init(), "tasks")) if (J.str(J.asObj(o), "id").equals(b)) list0 = J.asObj(o);
            if (list0 == null || !J.str(list0, "state").equals("done")) done = J.obj(rec.waitTask(b, "done", 90_000), "result");
            else done = J.obj(list0, "result");
            check(J.str(done, "path").endsWith("Egypt Football - هدف - 1.mp3"), "post mp3 " + J.str(done, "path"));
            app.shutdown();
        } finally {
            srv.stop(0);
            Zip.deleteTree(tmp.toFile());
        }
    }

    static String id(Map<String, Object> m) {
        return J.str(m, "id");
    }

    static Map<String, Object> taskIn(Engine app, String id) {
        for (Object o : J.arr(app.init(), "tasks")) if (J.str(J.asObj(o), "id").equals(id)) return J.asObj(o);
        return null;
    }

    static File findPart(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File f = findPart(k);
                if (f != null) return f;
            } else if (k.getName().endsWith(".part")) {
                return k;
            }
        }
        return null;
    }

    /** Two downloads at a time, pause and resume (continuing the partial file), and the list surviving a restart. */
    static void testQueue() throws Exception {
        if (!new File("/home/claude/ytest/media").isDirectory()) {
            System.out.println("  (skipped: no local media)");
            return;
        }
        Path tmp = Files.createTempDirectory("e2equeue");
        HttpServer srv = mediaServer(new File("/home/claude/ytest/media"), 400_000);
        File save = new File(tmp.toFile(), "Downloads");
        try {
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), save);
            Engine app = new Engine(host, rec);
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            File infoFile = makeInfo(tmp.toFile(), srv.getAddress().getPort(), "youtube");
            app.setDevInfoJson(infoFile);
            app.fetch("https://youtu.be/TESTVID0001");
            rec.waitFor("info", "done", 60_000);

            // 1) Three downloads: two run, the third waits for its turn.
            String a = id(app.download(0, "v720")), b = id(app.download(0, "v360")), c = id(app.download(0, "v1080"));
            int running = 0;
            for (long until = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < until; Thread.sleep(50)) {
                running = 0;
                for (Object o : J.arr(app.init(), "tasks")) if (J.str(J.asObj(o), "state").equals("running")) running++;
                if (running == Engine.PARALLEL) break;
            }
            eq(running, Engine.PARALLEL, "two run at once");
            eq(J.str(taskIn(app, c), "state"), "queued", "the third waits");
            Map<String, String> states = new HashMap<>();
            Map<String, Long> revs = new HashMap<>();
            int maxRunning = 0;
            boolean outOfOrder = false;
            long end = System.currentTimeMillis() + 120_000;
            while (!("done".equals(states.get(a)) && "done".equals(states.get(b)) && "done".equals(states.get(c)))) {
                String[] e = rec.q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (e == null) throw new AssertionError("queue timeout " + states);
                if (!e[0].equals("task")) continue;
                Map<String, Object> t = J.obj(J.asObj(Json.parse(e[1])), "task");
                if (t == null) continue;
                String tid = J.str(t, "id");
                long rev = (long) J.num(t, "rev");
                if (revs.containsKey(tid) && rev <= revs.get(tid)) outOfOrder = true;
                revs.put(tid, rev);
                states.put(tid, J.str(t, "state"));
                if (J.str(t, "state").equals("error")) throw new AssertionError("error: " + e[1]);
                int n = 0;
                for (String st : states.values()) if (st.equals("running")) n++;
                maxRunning = Math.max(maxRunning, n);
            }
            check(maxRunning <= Engine.PARALLEL, "never more than two at once: " + maxRunning);
            check(!outOfOrder, "revisions only grow");

            // 2) Pause in the middle: the part stays on disk and nothing more is downloaded.
            String p = id(app.download(0, "v720"));
            double pct;
            end = System.currentTimeMillis() + 60_000;
            while (true) {
                Map<String, Object> t = rec.waitTask(p, "running", Math.max(1, end - System.currentTimeMillis()));
                if (J.num(t, "percent") >= 30 && J.str(t, "stage").equals("video")) break;
            }
            app.pause(p);
            Map<String, Object> paused = rec.waitTask(p, "paused", 30_000);
            pct = J.num(paused, "percent");
            check(pct >= 25 && pct < 100, "progress kept when paused: " + pct);
            File job = new File(tmp.toFile(), "data/work/jobs/" + p);
            File part = findPart(job);
            check(part != null && part.length() > 0, "partial file kept: " + part);
            long partSize = part == null ? 0 : part.length();
            Thread.sleep(400);
            check(!processAlive(tmp.toString()), "the download program stopped");
            Thread.sleep(1200);
            check(part != null && part.length() == partSize, "nothing downloaded while paused");
            app.shutdown();

            // 3) The app is closed and opened again (even right in the middle of a download).
            File list = new File(tmp.toFile(), "data/downloads.json");
            String saved = read(list);
            check(saved.contains("\"" + p + "\""), "the list is on disk");
            Files.writeString(list.toPath(), saved.replace("\"state\":\"paused\"", "\"state\":\"running\""));
            Rec rec2 = new Rec();
            Engine app2 = new Engine(host, rec2);
            Map<String, Object> back = taskIn(app2, p);
            check(back != null && J.str(back, "state").equals("paused"), "interrupted download comes back paused: " + back);
            check(back != null && Math.abs(J.num(back, "percent") - pct) < 0.5, "with its progress " + (back == null ? null : J.num(back, "percent")));
            eq(J.str(taskIn(app2, a), "state"), "done", "finished ones stay finished");
            check(J.str(taskIn(app2, a), "thumb").startsWith("https://"), "picture kept");

            // 4) Resume continues from the last byte instead of starting over.
            served.clear();
            sentBytes.clear();
            app2.resume(p);
            Map<String, Object> done = J.obj(rec2.waitTask(p, "done", 90_000), "result");
            String first = null;
            synchronized (served) {
                for (String r : served) if (r.startsWith("v720_avc.mp4 ")) { first = r; break; }
            }
            check(first != null && first.startsWith("v720_avc.mp4 bytes=" + partSize + "-"), "resumed with a range request: " + first + " (part " + partSize + ")");
            long full = new File("/home/claude/ytest/media/v720_avc.mp4").length();
            long sent = sentBytes.getOrDefault("v720_avc.mp4", 0L);
            check(sent <= full - partSize + 65536, "only the rest was downloaded: " + sent + " of " + full + ", had " + partSize);
            File out = new File(J.str(done, "path"));
            eq(String.join(",", ffprobe(out, "stream=codec_name")), "h264,aac", "resumed file plays");
            check(!job.exists(), "work folder removed after finishing");

            // 5) Pausing a waiting download, adding one that is already there, deleting a paused one.
            app2.setDevInfoJson(infoFile);
            app2.fetch("https://youtu.be/TESTVID0001");
            rec2.waitFor("info", "done", 60_000);
            String r1 = id(app2.download(0, "v1080")), r2 = id(app2.download(0, "v360")), r3 = id(app2.download(0, "mp3-128"));
            eq(J.str(taskIn(app2, r3), "state"), "queued", "third waits");
            app2.pause(r3);
            eq(J.str(taskIn(app2, r3), "state"), "paused", "a waiting download pauses at once");
            app2.pause(r1);
            rec2.waitTask(r1, "paused", 30_000);
            Map<String, Object> dup = app2.download(0, "v1080");
            check(id(dup).equals(r1) && J.bool(dup, "existing"), "same video and quality is not added twice");
            String st1 = J.str(taskIn(app2, r1), "state");
            check(st1.equals("queued") || st1.equals("running"), "adding it again resumes it: " + st1);
            app2.delete(r1);
            rec2.waitTask(r1, "removed", 30_000);
            check(!new File(tmp.toFile(), "data/work/jobs/" + r1).exists(), "deleted download leaves nothing");
            check(taskIn(app2, r1) == null, "gone from the list");
            app2.resume(r3);
            rec2.waitTask(r3, "done", 90_000);
            Map<String, Object> t2 = taskIn(app2, r2);
            if (!J.str(t2, "state").equals("done")) rec2.waitTask(r2, "done", 90_000);
            Thread.sleep(300);
            check(!app2.busy(), "idle at the end");
            check(!processAlive(tmp.toString()), "nothing left running");
            app2.shutdown();
        } finally {
            srv.stop(0);
            Zip.deleteTree(tmp.toFile());
        }
    }

    /** Setup reports its steps, and a broken yt-dlp is reported with details. */
    static void testSetup() throws Exception {
        Path tmp = Files.createTempDirectory("setup");
        try {
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), new File(tmp.toFile(), "Downloads"));
            host.runtimeParts.add("python");
            Engine app = new Engine(host, rec);
            Map<String, Object> init = app.init();
            eq(J.str(init, "screen"), "setup", "setup screen");
            eq(Json.write(J.arr(init, "missing")), "[\"python\",\"ytdlp\"]", "missing parts");
            long t0 = System.currentTimeMillis();
            app.startSetup();
            eq(J.str(init, "setupSizeText"), "488 كيلوبايت", "download size shown before setup");
            Map<String, Object> first = rec.waitFor("setup", "download", 10_000);
            eq(J.str(first, "tool"), "python", "first step");
            eq(J.str(first, "totalText"), "488 كيلوبايت", "download total");
            rec.waitFor("setup", "extract", 10_000);
            rec.waitFor("setup", "verify", 60_000);
            rec.waitFor("setup", "done", 300_000);
            System.out.println("  setup took " + (System.currentTimeMillis() - t0) + " ms");
            check(host.runtimeParts.isEmpty(), "runtime installed");
            // A yt-dlp that cannot start leads back to setup with details.
            File dir = app.ytdlp().dir();
            Files.writeString(new File(dir, "__main__.py").toPath(), "import sys\nsys.exit(3)\n");
            app.fetch("https://youtu.be/dQw4w9WgXcQ");
            Map<String, Object> err = null;
            long end = System.currentTimeMillis() + 30_000;
            while (err == null && System.currentTimeMillis() < end) {
                String[] e = rec.q.poll(1, TimeUnit.SECONDS);
                if (e != null && e[0].equals("info") && e[1].contains("\"error\"")) err = J.asObj(Json.parse(e[1]));
            }
            check(err != null && J.str(J.obj(err, "error"), "code").equals("tools_missing")
                    && J.str(J.obj(err, "error"), "action").equals("setup"), "broken tool " + (err == null ? null : Json.write(err)));
            eq(J.str(app.init(), "screen"), "setup", "back to setup");
            app.shutdown();
        } finally {
            Zip.deleteTree(tmp.toFile());
        }
    }

    /** A copy of the bundled zipapp that says it is another version (and optionally cannot start). */
    static byte[] fakeZipapp(String version, boolean broken) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write("#!/usr/bin/env python3\n".getBytes());
        try (java.util.zip.ZipFile in = new java.util.zip.ZipFile("assets/ytdlp.zip");
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bo)) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
                java.util.zip.ZipEntry e = en.nextElement();
                byte[] b = in.getInputStream(e).readAllBytes();
                if (e.getName().equals("yt_dlp/version.py")) {
                    b = new String(b, StandardCharsets.UTF_8).replaceAll("__version__ = '[^']+'", "__version__ = '" + version + "'")
                            .getBytes(StandardCharsets.UTF_8);
                }
                if (broken && e.getName().equals("__main__.py")) b = "import sys\nsys.exit(1)\n".getBytes();
                out.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                out.write(b);
                out.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    static String sha256(byte[] b) throws Exception {
        return Http.hex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** Updating from a (local stand-in for) GitHub: checksum, swap, and keeping a working version. */

    // --------------------------------------------------------------------------- HELINK's own updates

    static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** The public half of a JDK Ed25519 key, as the 32 bytes the app keeps. */
    static byte[] rawPublic(java.security.KeyPair kp) {
        byte[] enc = kp.getPublic().getEncoded(); // X.509: a fixed 12-byte header, then the key
        return Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    }

    static byte[] sign(java.security.KeyPair kp, byte[] msg) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(msg);
        return s.sign();
    }

    /** Ed25519 as RFC 8032 and the JDK sign it; anything changed fails. */
    static void testEd25519() throws Exception {
        String[][] rfc = {
                {"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
                {"3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
                {"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
                        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
        };
        for (String[] v : rfc) {
            byte[] pub = unhex(v[0]), msg = unhex(v[1]), sig = unhex(v[2]);
            check(Ed25519.verify(pub, msg, sig), "rfc 8032 vector " + v[1]);
            byte[] bad = sig.clone();
            bad[5] ^= 1;
            check(!Ed25519.verify(pub, msg, bad), "a changed signature fails");
            byte[] msg2 = Arrays.copyOf(msg, msg.length + 1);
            check(!Ed25519.verify(pub, msg2, sig), "a changed message fails");
        }
        java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        java.util.Random r = new java.util.Random(7);
        long start = System.nanoTime();
        for (int i = 0; i < 12; i++) {
            byte[] msg = new byte[r.nextInt(3000)];
            r.nextBytes(msg);
            byte[] sig = sign(kp, msg);
            check(Ed25519.verify(rawPublic(kp), msg, sig), "jdk signature " + i);
            if (msg.length > 0) {
                msg[r.nextInt(msg.length)] ^= 4;
                check(!Ed25519.verify(rawPublic(kp), msg, sig), "jdk signature, changed message " + i);
            }
        }
        System.out.println("  verify: " + (System.nanoTime() - start) / 24 / 1000 + " µs each");
        // s ≥ L is refused (no second form of the same signature).
        byte[] sig = unhex(rfc[0][2]);
        java.math.BigInteger s0 = new java.math.BigInteger(1, reverse(Arrays.copyOfRange(sig, 32, 64)));
        java.math.BigInteger l = java.math.BigInteger.ONE.shiftLeft(252).add(new java.math.BigInteger("27742317777372353535851937790883648493"));
        byte[] s1 = reverse(toBytes32(s0.add(l)));
        System.arraycopy(s1, 0, sig, 32, 32);
        check(!Ed25519.verify(unhex(rfc[0][0]), new byte[0], sig), "s + L refused");
        check(!Ed25519.verify(new byte[31], new byte[0], unhex(rfc[0][2])), "short key refused");
        check(!Ed25519.verify(unhex(rfc[0][0]), new byte[0], new byte[63]), "short signature refused");
    }

    static byte[] reverse(byte[] b) {
        byte[] o = new byte[b.length];
        for (int i = 0; i < b.length; i++) o[i] = b[b.length - 1 - i];
        return o;
    }

    static byte[] toBytes32(java.math.BigInteger x) {
        byte[] b = x.toByteArray();
        byte[] o = new byte[32];
        int n = Math.min(32, b.length);
        System.arraycopy(b, b.length - n, o, 32 - n, n);
        return o;
    }

    /** latest.json as devtools write it: {"manifest": base64(json), "sig": base64}. */
    static byte[] signedManifest(java.security.KeyPair kp, Map<String, Object> m) throws Exception {
        byte[] body = Json.write(m).getBytes(StandardCharsets.UTF_8);
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        return Json.write(Json.obj("manifest", b64.encodeToString(body), "sig", b64.encodeToString(sign(kp, body))))
                .getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, Object> manifest(String version, String url, long size, String sha) {
        Map<String, Object> m = Json.obj("app", "helink-android", "version", version, "versionCode", 9, "date", "2026-11-01");
        m.put("notes", Json.obj("ar", Arrays.asList("تنزيل كورس كامل مرة واحدة.", "التحديث من داخل التطبيق."),
                "en", Arrays.asList("A whole course at once.", "Updates from inside the app.")));
        m.put("apk", Json.obj("urls", Arrays.asList(url), "size", size, "sha256", sha));
        return m;
    }

    static void testAppManifest() throws Exception {
        java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] pub = rawPublic(kp);
        String sha = "ab".repeat(32);
        Updates.Manifest m = Updates.parse(signedManifest(kp, manifest("1.6.1", "https://raw.githubusercontent.com/x/y/z/HELINK-1.6.1.apk", 6_500_000, sha)), pub);
        eq(m.version + " " + m.code + " " + m.apk.size + " " + m.apk.urls.get(0).endsWith(".apk") + " " + m.notes.get("ar").size(),
                "1.6.1 9 6500000 true 2", "manifest read");
        // Another key, changed bytes, another app, a plain http address, no APK: refused.
        java.security.KeyPair other = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String[] why = {"other key", "changed", "other app", "http", "no sha"};
        byte[][] bad = {
                signedManifest(other, manifest("1.6.1", "https://a/b.apk", 1, sha)),
                new String(signedManifest(kp, manifest("1.6.1", "https://a/b.apk", 1, sha)), StandardCharsets.UTF_8)
                        .replace("\"sig\":\"", "\"sig\":\"A").getBytes(StandardCharsets.UTF_8),
                signedManifest(kp, Json.obj("app", "helink-windows", "version", "2.4.0", "apk", Json.obj("urls", Arrays.asList("https://a/b"), "size", 1, "sha256", sha))),
                signedManifest(kp, manifest("1.6.1", "http://example.com/b.apk", 1, sha)),
                signedManifest(kp, manifest("1.6.1", "https://a/b.apk", 1, "xyz")),
        };
        for (int i = 0; i < bad.length; i++) {
            try {
                Updates.parse(bad[i], pub);
                check(false, "accepted: " + why[i]);
            } catch (Updates.BadManifest e) {
                // as it should
            }
        }
        // Versions.
        check(Updates.newer("1.6.1", "1.6.0") && Updates.newer("1.10.0", "1.9.9") && Updates.newer("2", "1.99.99")
                && !Updates.newer("1.6.0", "1.6.0") && !Updates.newer("1.6", "1.6.0") && !Updates.newer("x", "1.0")
                && !Updates.newer("1.5.9", "1.6.0") && Updates.newer("v1.7.0", "1.6.0"), "newer");
    }

    /** Waits for an "update" event in the given state. */
    static Map<String, Object> waitUpdate(Rec rec, String state, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new AssertionError("timeout waiting for update " + state);
            String[] e = rec.q.poll(left, TimeUnit.MILLISECONDS);
            if (e == null || !e[0].equals("update")) continue;
            Map<String, Object> m = J.asObj(Json.parse(e[1]));
            if (J.str(m, "state").equals(state)) return m;
            if (J.str(m, "state").equals("error") && !state.equals("error")) throw new AssertionError("update error: " + e[1]);
        }
    }

    /**
     * The whole update as the app does it, against a local GitHub: found when looking, "Not
     * now", "Update now" (downloaded in pieces, checked), handed to the installer, the user
     * saying no, and the next start after an update continuing the downloads that ran.
     */
    static void testAppUpdate() throws Exception {
        Path tmp = Files.createTempDirectory("appupdate");
        java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] apk = new byte[3_000_000];
        new java.util.Random(3).nextBytes(apk);
        String sha = Http.hex(java.security.MessageDigest.getInstance("SHA-256").digest(apk));
        final byte[][] latest = new byte[1][];
        final int[] apkHits = {0};
        Map<String, HttpHandler> routes = new HashMap<>();
        routes.put("/latest.json", x -> {
            x.sendResponseHeaders(200, latest[0].length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(latest[0]);
            }
            x.close();
        });
        routes.put("/apk", x -> {
            apkHits[0]++;
            String range = x.getRequestHeaders().getFirst("Range");
            int from = range == null ? 0 : Integer.parseInt(range.replaceAll("[^0-9]", ""));
            // The first answer stops half-way (a phone's connection dropping).
            int to = apkHits[0] == 1 ? apk.length / 2 : apk.length;
            if (from > 0) x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (apk.length - 1) + "/" + apk.length);
            // (sent without a length, so the half answer simply ends)
            x.sendResponseHeaders(from > 0 ? 206 : 200, apkHits[0] == 1 ? 0 : apk.length - from);
            try (OutputStream o = x.getResponseBody()) {
                o.write(apk, from, to - from);
            } catch (IOException ignored) {
                // cut short on purpose
            }
            x.close();
        });
        HttpServer srv = server(routes);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        List<String> oldUrls = Updates.manifestUrls;
        byte[] oldKey = Updates.publicKey;
        long oldDelay = Updates.firstDelay;
        Updates.manifestUrls = Arrays.asList(base + "/latest.json");
        Updates.publicKey = rawPublic(kp);
        Updates.allowLocalHttp = true;
        Updates.firstDelay = 0;
        I18n.set("ar");
        try {
            latest[0] = signedManifest(kp, manifest("1.99.1", base + "/apk", apk.length, sha));
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), new File(tmp.toFile(), "Downloads"));
            Engine app = new Engine(host, rec);
            eq(J.str(J.obj(app.init(), "update"), "state"), "", "nothing known at first");
            app.updates().checkIfDue();
            Map<String, Object> u = waitUpdate(rec, "available", 20_000);
            eq(J.str(u, "version") + "|" + J.str(u, "current") + "|" + J.arr(u, "notes").get(0) + "|" + J.bool(u, "snoozed"),
                    "1.99.1|" + Engine.VERSION + "|تنزيل كورس كامل مرة واحدة.|false", "found");
            check(!J.str(u, "sizeText").isEmpty(), "size shown");
            eq(J.str(J.obj(app.init(), "update"), "state"), "available", "a page opened now sees it");
            app.updates().checkIfDue(); // not due again so soon
            Thread.sleep(300);
            // "Not now": offered again a day later.
            app.updates().later();
            check(J.bool(waitUpdate(rec, "available", 5_000), "snoozed"), "snoozed");
            // "Update now": the APK comes in two pieces and is checked.
            check(J.bool(app.updates().updateNow(), "ok"), "update now");
            waitUpdate(rec, "downloading", 5_000);
            Map<String, Object> ready = waitUpdate(rec, "ready", 30_000);
            check(apkHits[0] >= 2, "continued after the cut: " + apkHits[0]);
            File got = new File(tmp.toFile(), "data/update/HELINK-1.99.1.apk");
            check(Arrays.equals(Files.readAllBytes(got.toPath()), apk), "the APK, whole");
            check(!J.str(ready, "installNote").equals("aborted"), "ready");
            // Handed to the installer: the downloads running are noted for the new version.
            File toInstall = app.updates().startInstall();
            check(toInstall != null && toInstall.equals(got), "file to install " + toInstall);
            waitUpdate(rec, "installing", 5_000);
            Prefs prefs = new Prefs(new File(tmp.toFile(), "data/settings.json"));
            eq(prefs.getString("updTo") + "|" + prefs.getString("updFrom"), "1.99.1|" + Engine.VERSION, "noted");
            // Android's window did not come: asked for again.
            check(app.updates().startInstall() != null, "asked again while installing");
            // The user said no on Android's screen: it stays ready to install.
            app.updates().installEnded(true, "");
            eq(J.str(waitUpdate(rec, "ready", 5_000), "installNote"), "aborted", "aborted");
            eq(new Prefs(new File(tmp.toFile(), "data/settings.json")).getString("updTo"), "", "note dropped");
            // Android's installer failed: the error shows; "Not now" forgets it, the file stays ready.
            check(app.updates().startInstall() != null, "install again");
            waitUpdate(rec, "installing", 5_000);
            app.updates().installEnded(false, "-1: test");
            Map<String, Object> failed = waitUpdate(rec, "error", 5_000);
            eq(J.str(J.obj(failed, "error"), "code") + "|" + J.str(failed, "failedIn"), "update_failed|install", "install failed");
            app.updates().later();
            Map<String, Object> back = waitUpdate(rec, "ready", 5_000);
            check(J.bool(back, "snoozed") && J.raw(back, "error") == null, "not now after a failure: " + back);
            // A newer manifest that is not signed by the key: ignored, and said when asked.
            latest[0] = signedManifest(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
                    manifest("9.9.9", base + "/apk", apk.length, sha));
            app.shutdown();
            rec.q.clear();
            Engine app2 = new Engine(host, rec);
            app2.updates().checkNow();
            Map<String, Object> err = waitUpdate(rec, "error", 20_000);
            eq(J.str(J.obj(err, "error"), "code") + "|" + J.str(err, "failedIn"), "update_verify|check", "forged manifest refused");
            app2.shutdown();

            // The start after an update: a download that ran continues, and it is said once.
            DownloadTask t = new DownloadTask();
            t.id = "dres1";
            t.url = "https://www.youtube.com/watch?v=aaaaaaaaaa1";
            t.site = Sites.YOUTUBE;
            t.kind = "video";
            t.key = "v720";
            t.base = "x";
            t.title = "x";
            t.state = DownloadTask.RUNNING;
            new DownloadStore(new File(tmp.toFile(), "data/downloads.json"), new File(tmp.toFile(), "data/work/thumbs"), host)
                    .write(DownloadStore.serialize(Arrays.asList(t)), 1);
            Prefs p2 = new Prefs(new File(tmp.toFile(), "data/settings.json"));
            p2.putAll("updTo", Engine.VERSION, "updFrom", "1.5.0", "updResume", "dres1");
            Engine app3 = new Engine(host, rec);
            Map<String, Object> init = app3.init();
            eq(J.str(init, "updated"), Engine.VERSION, "updated said");
            eq(J.str(app3.init(), "updated"), "", "said once");
            String st = J.str(J.asObj(J.arr(init, "tasks").get(0)), "state");
            check(!st.equals("paused"), "the download continues: " + st);
            check(!new File(tmp.toFile(), "data/update").exists(), "update folder removed");
            app3.shutdown();
            // An update that did not happen: still the old version.
            new Prefs(new File(tmp.toFile(), "data/settings.json")).putAll("updTo", "9.9.9", "updFrom", Engine.VERSION, "updResume", "");
            Engine app4 = new Engine(host, rec);
            eq(J.str(app4.init(), "updateFailed"), "9.9.9", "failed update said");
            app4.shutdown();
        } finally {
            Updates.manifestUrls = oldUrls;
            Updates.publicKey = oldKey;
            Updates.allowLocalHttp = false;
            Updates.firstDelay = oldDelay;
            srv.stop(0);
        }
    }

    // --------------------------------------------------------------------------- courses (playlists)

    static void testPlaylist() throws Exception {
        I18n.set("ar");
        Json.Obj data = Json.parseObject(new String(Files.readAllBytes(new File(TESTDATA, "youtube_playlist_flat.json").toPath()), StandardCharsets.UTF_8));
        Playlist.Info l = Playlist.build(data);
        eq(l.title + "|" + l.channel + "|" + l.id + "|" + l.entries.size(), "دورة بايثون للمبتدئين: من الصفر|قناة البرمجة|PLtest0001course|8", "list");
        List<Integer> avail = new ArrayList<>();
        for (Playlist.Entry e : l.entries) if (e.available) avail.add(e.n);
        eq(avail.toString(), "[1, 2, 4, 8]", "private, deleted, members-only and upcoming cannot be ticked");
        Playlist.Entry e0 = l.entries.get(0);
        eq(e0.n + "|" + e0.id + "|" + e0.durationText + "|" + e0.thumbnail, "1|aaaaaaaaaa1|12:34|https://i.ytimg.com/vi/aaaaaaaaaa1/mqdefault.jpg", "first");
        Map<String, Object> page = J.asObj(Json.parse(Json.write(l)));
        eq(J.num(page, "count") + "|" + J.str(page, "durationText") + "|" + J.arr(page, "options").size(), "4.0|1:12:46|7", "page");
        List<String> keys = new ArrayList<>();
        for (Object o : J.arr(page, "options")) {
            Map<String, Object> m = J.asObj(o);
            keys.add(J.str(m, "key"));
            if (J.str(m, "key").equals("v720")) eq(J.str(m, "label") + "|" + J.str(m, "desc") + "|" + J.bool(m, "recommended"), "720p|جودة جيدة بحجم مناسب|true", "720p");
        }
        eq(String.join(",", keys), "v1080,v720,v480,v360,mp3-320,mp3-192,mp3-128", "keys");
        try {
            Playlist.build(Json.parseObject("{\"_type\":\"playlist\",\"title\":\"x\",\"entries\":[{\"_type\":\"url\",\"id\":\"bbbbbbbbbb1\",\"title\":\"[Private video]\"}]}"));
            check(false, "an empty list should fail");
        } catch (Formats.InfoException ie) {
            eq(ie.code, "list_empty", "empty list");
        }
        String[][] lists = {
                {"https://www.youtube.com/watch?v=aaaaaaaaaa1&list=PLtest0001course&index=2", "https://www.youtube.com/playlist?list=PLtest0001course"},
                {"https://youtu.be/aaaaaaaaaa1?list=PLtest0001course", "https://www.youtube.com/playlist?list=PLtest0001course"},
                {"https://m.youtube.com/watch?v=aaaaaaaaaa1&list=OLAK5uy_abc", "https://www.youtube.com/playlist?list=OLAK5uy_abc"},
                {"https://www.youtube.com/watch?v=aaaaaaaaaa1&list=RDaaaaaaaaaa1", ""},
                {"https://www.youtube.com/watch?v=aaaaaaaaaa1&list=WL", ""},
                {"https://www.youtube.com/watch?v=aaaaaaaaaa1", ""},
                {"https://www.youtube.com/watch?v=aaaaaaaaaa1&list=PL<script>", ""},
                {"https://www.tiktok.com/@a/video/1?list=PLx", ""},
        };
        for (String[] c : lists) eq(Playlist.listOf(c[0]), c[1], "listOf " + c[0]);
        String[][] ids = {
                {"https://www.youtube.com/watch?v=aaaaaaaaaa1&list=PLx", "aaaaaaaaaa1"}, {"https://youtu.be/aaaaaaaaaa1?si=x", "aaaaaaaaaa1"},
                {"https://www.youtube.com/shorts/aaaaaaaaaa1", "aaaaaaaaaa1"}, {"https://www.youtube.com/playlist?list=PLx", ""},
                {"https://www.youtube.com/watch?v=short", ""}, {"https://example.com/watch?v=aaaaaaaaaa1", ""},
        };
        for (String[] c : ids) eq(Playlist.videoId(c[0]), c[1], "videoId " + c[0]);
        eq(String.join("|", Playlist.format("v720")), "video|720p|bv*+ba/b|res:720,vcodec:h264,acodec:aac|", "v720");
        eq(String.join("|", Playlist.format("mp3-192")), "audio|192k|ba/b|acodec:aac|192K", "mp3-192");
        for (String bad : new String[]{"v721", "mp3-100", "vbest", "", "720"}) check(Playlist.format(bad) == null, "accepted " + bad);
        // Links: a playlist is read as a list, a channel says to open one of its playlists.
        Rec rec = new Rec();
        Engine app = new Engine(new DesktopHost(Files.createTempDirectory("pl").toFile(), null), rec);
        app.fetch("https://www.youtube.com/@prog");
        Map<String, Object> ch = rec.waitForAny("info", 5_000);
        eq(J.str(J.obj(ch, "error"), "code"), "channel", "channel");
        app.shutdown();
    }

    /**
     * A whole course: the list is read, three of its videos picked and downloaded (the real
     * yt-dlp and ffmpeg, the local test video for each) into one folder named after the list,
     * numbered in its order; pasted again, what is downloaded is marked, also once it has left
     * the downloads list.
     */
    static void testEndToEndList() throws Exception {
        if (!new File("/home/claude/ytest/media").isDirectory()) {
            System.out.println("  (skipped: no local media)");
            return;
        }
        I18n.set("ar");
        Path tmp = Files.createTempDirectory("e2elist");
        HttpServer srv = mediaServer(new File("/home/claude/ytest/media"), 6_000_000);
        File save = new File(tmp.toFile(), "Downloads");
        try {
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), save);
            Engine app = new Engine(host, rec);
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            app.setDevInfoJson(makeInfo(tmp.toFile(), srv.getAddress().getPort(), "youtube"));
            app.setDevListJson(new File(TESTDATA, "youtube_playlist_flat.json"));

            int seq = app.fetch("شوف الكورس ده https://www.youtube.com/playlist?list=PLtest0001course");
            Map<String, Object> ev = rec.waitFor("info", "list", 30_000);
            eq((int) J.num(ev, "seq"), seq, "seq");
            Map<String, Object> list = J.obj(ev, "list");
            eq(J.str(list, "title") + "|" + J.num(list, "count") + "|" + J.arr(list, "entries").size(), "دورة بايثون للمبتدئين: من الصفر|4.0|8", "list");
            Map<String, Object> missed = J.obj(app.init(), "fetch");
            check(missed != null && J.str(missed, "status").equals("list"), "a page that missed the list can catch up");
            Map<String, Object> res = app.downloadList(seq, Arrays.asList(1, 3, 4, 8), "v720", false);
            eq(J.num(res, "added") + "|" + J.num(res, "skipped") + "|" + J.str(res, "folder"),
                    "3.0|0.0|التنزيلات (Downloads) ← دورة بايثون للمبتدئين - من الصفر", "added (the private one left out)");
            Map<String, Object> again = app.downloadList(seq, Arrays.asList(1, 4, 8), "v720", false);
            eq(J.num(again, "added") + "|" + J.num(again, "skipped"), "0.0|3.0", "nothing twice");
            List<Object> ids = J.arr(res, "ids");
            // Shown in the list's order (the newest first, so the first lesson has the latest time).
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Object o : J.arr(app.init(), "tasks")) byId.put(J.str(J.asObj(o), "id"), J.asObj(o));
            for (int i = 1; i < ids.size(); i++) {
                check(J.num(byId.get((String) ids.get(i - 1)), "createdAt") > J.num(byId.get((String) ids.get(i)), "createdAt"), "shown in order " + i);
            }
            Map<String, Map<String, Object>> done = new HashMap<>();
            long end = System.currentTimeMillis() + 180_000;
            while (done.size() < ids.size()) {
                String[] e = rec.q.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (e == null) throw new AssertionError("list download timeout");
                if (!e[0].equals("task")) continue;
                Map<String, Object> t = J.obj(J.asObj(Json.parse(e[1])), "task");
                if (t == null) continue;
                if (J.str(t, "state").equals("done")) done.put(J.str(t, "id"), t);
                if (J.str(t, "state").equals("error")) throw new AssertionError("download error: " + e[1]);
            }
            File dir = new File(save, "دورة بايثون للمبتدئين - من الصفر");
            String[] want = {"01 - الدرس 1 - المقدمة - لماذا بايثون؟.mp4", "04 - الدرس 4 - المتغيرات والأنواع.mp4",
                    "08 - Lesson 8 - Loops in Python (for - while).mp4"};
            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> t = done.get((String) ids.get(i));
                File f = new File(J.str(J.obj(t, "result"), "path"));
                eq(f.getParentFile() + "/" + f.getName(), dir + "/" + want[i], "saved as");
                eq(J.str(t, "list") + "|" + J.str(t, "label"), "دورة بايثون للمبتدئين: من الصفر|720p", "task " + i);
                eq(String.join(",", ffprobe(f, "stream=codec_name,height")), "h264,720,aac", "streams " + i);
            }
            // Pasted again: what is downloaded is marked; a file deleted since is not.
            check(new File(dir, want[2]).delete(), "delete one");
            app.clearFinished(); // the downloads list forgets them: the ledger still knows
            int seq2 = app.fetch("https://www.youtube.com/playlist?list=PLtest0001course");
            Map<String, Object> ev2 = rec.waitFor("info", "list", 30_000);
            eq((int) J.num(ev2, "seq"), seq2, "seq 2");
            Map<Integer, String> marks = new java.util.TreeMap<>();
            for (Object o : J.arr(J.obj(ev2, "list"), "entries")) {
                Map<String, Object> m = J.asObj(o);
                if (J.raw(m, "have") != null) marks.put((int) J.num(m, "n"), Json.write(J.raw(m, "have")));
            }
            eq(marks.toString(), "{1=[\"video\"], 4=[\"video\"]}", "have");
            // As MP3, a video not finished yet is marked too.
            Map<String, Object> mp3 = app.downloadList(seq2, Arrays.asList(2), "mp3-128", false);
            int seq3 = app.fetch("https://www.youtube.com/playlist?list=PLtest0001course");
            Map<String, Object> ev3 = rec.waitFor("info", "list", 30_000);
            eq((int) J.num(ev3, "seq"), seq3, "seq 3");
            Map<String, Object> e2 = J.asObj(J.arr(J.obj(ev3, "list"), "entries").get(1));
            eq(Json.write(J.raw(e2, "have")), "[\"audio\"]", "mp3 still downloading");
            for (Object id : J.arr(mp3, "ids")) app.delete((String) id);
            // A key that is not offered, or a list that is not on screen.
            check(J.raw(app.downloadList(seq3, Arrays.asList(1), "v999", false), "error") != null, "unknown quality");
            check(J.raw(app.downloadList(seq3 + 5, Arrays.asList(1), "v720", false), "error") != null, "another list");
            app.shutdown();
        } finally {
            srv.stop(0);
        }
    }

    static void testUpdate() throws Exception {
        Path tmp = Files.createTempDirectory("update");
        final Map<String, byte[]> files = new HashMap<>();
        final String[] tag = {"2099.01.01.000001"};
        Map<String, HttpHandler> routes = new HashMap<>();
        routes.put("/latest", x -> reply(x, 302, "", "Location", "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/" + tag[0]));
        routes.put("/download/", x -> {
            byte[] b = files.get(x.getRequestURI().getPath());
            if (b == null) {
                reply(x, 404, "");
                return;
            }
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
            x.close();
        });
        HttpServer s = server(routes);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        String oldLatest = YtdlpTool.LATEST, oldAsset = YtdlpTool.ASSET;
        YtdlpTool.LATEST = base + "/latest";
        YtdlpTool.ASSET = base + "/download/%s/%s";
        try {
            Rec rec = new Rec();
            DesktopHost host = new DesktopHost(tmp.toFile(), new File(tmp.toFile(), "Downloads"));
            Engine app = new Engine(host, rec);
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            String bundled = app.ytdlp().version();
            File oldDir = app.ytdlp().dir();

            // 1) A good new build replaces the old one.
            byte[] good = fakeZipapp(tag[0], false);
            files.put("/download/" + tag[0] + "/yt-dlp", good);
            files.put("/download/" + tag[0] + "/SHA2-256SUMS", (sha256("x".getBytes()) + "  yt-dlp.exe\n" + sha256(good) + "  yt-dlp\n").getBytes());
            check(app.ytdlp().update(new Cancel(), 0), "update applied");
            eq(app.ytdlp().version(), tag[0], "new version in use");
            check(!oldDir.exists(), "old version removed");
            // 2) Already the latest: nothing to do.
            check(!app.ytdlp().update(new Cancel(), 0), "already latest");
            // 3) A wrong checksum is refused.
            tag[0] = "2099.01.02.000001";
            byte[] other = fakeZipapp(tag[0], false);
            files.put("/download/" + tag[0] + "/yt-dlp", other);
            files.put("/download/" + tag[0] + "/SHA2-256SUMS", (sha256("tampered".getBytes()) + "  yt-dlp\n").getBytes());
            boolean refused = false;
            try {
                app.ytdlp().update(new Cancel(), 0);
            } catch (IOException e) {
                refused = e.getMessage().contains("checksum");
            }
            check(refused, "bad checksum refused");
            eq(app.ytdlp().version(), "2099.01.01.000001", "version kept after bad checksum");
            // 4) A build that does not start here is not used.
            tag[0] = "2099.01.03.000001";
            byte[] broken = fakeZipapp(tag[0], true);
            files.put("/download/" + tag[0] + "/yt-dlp", broken);
            files.put("/download/" + tag[0] + "/SHA2-256SUMS", (sha256(broken) + "  yt-dlp\n").getBytes());
            check(!app.ytdlp().update(new Cancel(), 0), "broken build not used");
            eq(app.ytdlp().version(), "2099.01.01.000001", "working version kept");
            File[] dirs = new File(host.dataDir(), "ytdlp").listFiles();
            eq(dirs.length, 1, "only one yt-dlp folder left");
            // 5) The check is skipped while the last one is recent.
            tag[0] = "2099.01.04.000001";
            check(!app.ytdlp().update(new Cancel(), 3600_000), "recent check skipped");
            check(!bundled.equals(app.ytdlp().version()), "still the updated one");
            app.shutdown();
        } finally {
            YtdlpTool.LATEST = oldLatest;
            YtdlpTool.ASSET = oldAsset;
            s.stop(0);
            Zip.deleteTree(tmp.toFile());
        }
    }

    /** The posts the UI preview shows, built from the test fixtures like the real app does. */
    static void writePreviewData(File out, File thumbs) throws Exception {
        java.util.function.Function<String, String> img = (name) -> {
            try {
                return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(new File(thumbs, name).toPath()));
            } catch (IOException e) {
                return "";
            }
        };
        Map<String, Object> posts = new LinkedHashMap<>();
        Formats.Post yt = post("youtube_like.json", Sites.YOUTUBE);
        yt.items.get(0).meta.title = "إزاي تعمل كيكة البرتقال في البيت بأسهل طريقة ومن غير فرن";
        yt.items.get(0).meta.channel = "مطبخ ست الكل";
        yt.items.get(0).meta.durationText = "12:48";
        yt.items.get(0).meta.thumbnail = img.apply("thumb.jpg");
        Formats.Post tt = post("tiktok.json", Sites.TIKTOK);
        tt.items.get(0).meta.thumbnail = img.apply("thumb_tall.jpg");
        Formats.Post x = post("x_multi.json", Sites.X);
        x.items.get(0).meta.thumbnail = img.apply("thumb2.jpg");
        x.items.get(1).meta.thumbnail = img.apply("thumb.jpg");
        Formats.Post ig = post("instagram_carousel.json", Sites.INSTAGRAM);
        ig.items.get(0).meta.thumbnail = img.apply("thumb_tall.jpg");
        ig.items.get(1).meta.thumbnail = img.apply("thumb2.jpg");
        String[][] all = {{"youtube"}, {"tiktok"}, {"x"}, {"instagram"}};
        Formats.Post[] ps = {yt, tt, x, ig};
        for (int i = 0; i < ps.length; i++) {
            posts.put(all[i][0], Json.obj("site", ps[i].site, "siteName", Sites.name(ps[i].site), "items", ps[i].items));
        }
        String ar = Json.write(posts);
        // The same posts for the English preview: written in English, with English titles.
        I18n.set("en");
        try {
            yt.items.get(0).meta.title = "How to make orange cake at home, no oven needed";
            yt.items.get(0).meta.channel = "Home Kitchen";
            tt.items.get(0).meta.title = "The best video you'll see today 😂";
            for (Formats.Item it : x.items) it.meta.title = "Amazing goal in the 90th minute";
            for (Formats.Item it : ig.items) it.meta.title = "Instagram - sara.cooks";
            Map<String, Object> en = new LinkedHashMap<>();
            for (int i = 0; i < ps.length; i++) {
                en.put(all[i][0], Json.obj("site", ps[i].site, "siteName", Sites.name(ps[i].site), "items", ps[i].items));
            }
            Files.writeString(out.toPath(), "{\"posts\":" + ar + ",\"postsEn\":" + Json.write(en) + "}");
        } finally {
            I18n.set("ar");
        }
    }

    // --------------------------------------------------------------------------- desktop host

    /** What the cut screen plays for each site's sample. */
    static void testClipPreview() throws Exception {
        for (String name : new String[]{"youtube_like", "only18", "tiktok", "instagram_reel", "x_single", "facebook_dash", "facebook_sdhd", "short"}) {
            File f = new File("test/testdata/" + name + ".json");
            if (!f.exists()) continue;
            Map<String, Object> info = J.asObj(Json.parse(Files.readString(f.toPath())));
            Formats.Preview p = Formats.preview(info);
            System.out.println("    " + name + ": " + p.mode + " | v=" + (p.videoUrl.length() > 60 ? p.videoUrl.substring(0, 60) : p.videoUrl)
                    + " " + p.videoType + " | a=" + (p.audioUrl.length() > 50 ? p.audioUrl.substring(0, 50) : p.audioUrl) + " " + p.audioType
                    + " | headers " + p.videoHeaders.keySet());
        }
        eq(Formats.cookieHeader("tt_chain_token=abc; Domain=.tiktok.com; Path=/; Secure; Expires=123; b=2"), "tt_chain_token=abc; b=2", "cookie header");
    }

    /** One request to the preview server: {status, Content-Range, body bytes}. */
    static Object[] getRange(String url, String range) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        if (range != null) c.setRequestProperty("Range", range);
        int code = c.getResponseCode();
        byte[] body = code >= 400 ? new byte[0] : c.getInputStream().readAllBytes();
        String cr = c.getHeaderField("Content-Range");
        String type = c.getContentType();
        c.disconnect();
        return new Object[]{code, cr == null ? "" : cr, body, type == null ? "" : type};
    }

    static void testPreviewServer() throws Exception {
        if (!new File("/home/claude/ytest/media").isDirectory()) {
            System.out.println("  (skipped: no local media)");
            return;
        }
        HttpServer srv = mediaServer(new File("/home/claude/ytest/media"), 400_000_000);
        Path tmp = Files.createTempDirectory("prev");
        PreviewServer ps = new PreviewServer(new DesktopHost(tmp.toFile(), new File(tmp.toFile(), "save")));
        try {
            byte[] file = Files.readAllBytes(new File("/home/claude/ytest/media/c360.mp4").toPath());
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "TestAgent/1");
            String u = ps.add("clip", PreviewServer.web("http://127.0.0.1:" + srv.getAddress().getPort() + "/c360.mp4", h, "video/mp4"));
            check(u.startsWith("http://127.0.0.1:") && u.contains("/m/") && u.length() > 50, "address with a long name: " + u);
            Object[] r = getRange(u, "bytes=100-299");
            eq(r[0] + "|" + r[1] + "|" + r[3], "206|bytes 100-299/" + file.length + "|video/mp4", "a part");
            check(Arrays.equals((byte[]) r[2], Arrays.copyOfRange(file, 100, 300)), "the right bytes");
            r = getRange(u, "bytes=" + (file.length - 10) + "-");
            check(Arrays.equals((byte[]) r[2], Arrays.copyOfRange(file, file.length - 10, file.length)), "the end");
            r = getRange(u, null);
            check((int) r[0] == 200 && ((byte[]) r[2]).length == file.length, "whole");
            check(served.stream().anyMatch(x -> x.startsWith("c360.mp4 bytes=100-299")), "range passed on");
            // Unknown, or dropped: not found.
            eq(String.valueOf(getRange(u.substring(0, u.length() - 4) + "abcd", null)[0]), "404", "unknown");
            ps.drop("clip");
            eq(String.valueOf(getRange(u, null)[0]), "404", "dropped");
            // Bytes the app made (a status picture).
            byte[] pic = "0123456789".getBytes();
            String b = ps.add("wa", PreviewServer.bytes(pic, "image/jpeg"));
            r = getRange(b, "bytes=2-5");
            eq(r[0] + "|" + r[1] + "|" + new String((byte[]) r[2]), "206|bytes 2-5/10|2345", "bytes part");
            eq(PreviewServer.parseRange("bytes=5-") [0] + "," + PreviewServer.parseRange("bytes=5-")[1], "5,-1", "open range");
            check(PreviewServer.parseRange("bytes=-5") == null && PreviewServer.parseRange("items=1-2") == null, "odd ranges ignored");
        } finally {
            ps.stop();
            srv.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    static void testEndToEndClip() throws Exception {
        if (!new File("/home/claude/ytest/media").isDirectory()) {
            System.out.println("  (skipped: no local media)");
            return;
        }
        Path tmp = Files.createTempDirectory("clip");
        HttpServer srv = mediaServer(new File("/home/claude/ytest/media"), 3_000_000);
        File save = new File(tmp.toFile(), "Downloads");
        Rec rec = new Rec();
        DesktopHost host = new DesktopHost(tmp.toFile(), save);
        Engine app = new Engine(host, rec);
        try {
            app.startSetup();
            rec.waitFor("setup", "done", 300_000);
            app.setDevInfoJson(makeInfo(tmp.toFile(), srv.getAddress().getPort(), "youtube"));

            // A list cannot be cut.
            app.clipFetch("https://www.youtube.com/playlist?list=PL1234567890abcdef");
            eq(J.str(J.obj(rec.waitForAny("clip", 5_000), "error"), "code"), "clip_list", "a list");

            int seq = app.clipFetch("https://youtu.be/TESTVID0001");
            Map<String, Object> ev = rec.waitFor("clip", "done", 60_000);
            eq((int) J.num(ev, "seq"), seq, "seq");
            Map<String, Object> it = J.asObj(J.arr(ev, "items").get(0));
            Map<String, Object> pv = J.obj(it, "preview");
            eq(J.str(pv, "mode"), "av", "plays the file with both");
            Object[] r = getRange(J.str(pv, "video"), "bytes=0-1023");
            eq(String.valueOf(r[0]), "206", "preview served");
            check(J.num(J.obj(it, "meta"), "duration") == 8, "duration");
            Map<String, Object> restored = J.obj(app.init(), "clip");
            check(restored != null && J.str(restored, "status").equals("done"), "the cut screen comes back after a reload");
            // The main tab is untouched.
            check(J.obj(J.obj(app.init(), "restore"), "info") == null, "own reading");

            // Too short, or outside.
            eq(J.str(J.obj(J.asObj(Json.parse(Json.write(app.clipDownload(seq, 0, "v720", 5, 5.2, false)))), "error"), "code"), "clip_range", "too short");
            eq(J.str(J.obj(J.asObj(Json.parse(Json.write(app.clipDownload(seq, 0, "v720", 9, 12, false)))), "error"), "code"), "clip_range", "after the end");

            // 720p from 2.5 to 5.25 seconds.
            Map<String, Object> res = app.clipDownload(seq, 0, "v720", 2.5, 5.25, false);
            String id = J.str(res, "id");
            check(!id.isEmpty(), "added: " + res);
            Map<String, Object> done = rec.waitTask(id, "done", 120_000);
            eq(J.str(done, "clip"), "0:03 – 0:05", "shown times");
            Map<String, Object> result = J.obj(done, "result");
            String name = J.str(result, "name");
            check(name.contains("مقطع 0.03-0.05") && name.endsWith("720p.mp4"), "name: " + name);
            File out = new File(save, name);
            List<String> d = ffprobe(out, "format=duration");
            double dur = Double.parseDouble(d.get(d.size() - 1));
            check(Math.abs(dur - 2.75) < 0.15, "the part's length: " + dur);
            String streams = String.join(",", ffprobe(out, "stream=codec_type"));
            check(streams.contains("video") && streams.contains("audio"), "picture and sound: " + streams);
            check(videoStream(out).startsWith("h264,1280,720"), "720p H.264: " + videoStream(out));

            // MP3 of 1 to 4 seconds.
            String id2 = J.str(app.clipDownload(seq, 0, "mp3-128", 1, 4, false), "id");
            Map<String, Object> done2 = rec.waitTask(id2, "done", 120_000);
            File mp3 = new File(save, J.str(J.obj(done2, "result"), "name"));
            check(mp3.getName().endsWith(".mp3"), "mp3: " + mp3.getName());
            List<String> d2 = ffprobe(mp3, "format=duration");
            double dur2 = Double.parseDouble(d2.get(d2.size() - 1));
            check(Math.abs(dur2 - 3) < 0.2, "the MP3 part's length: " + dur2);

            // Survives a restart: the times are kept.
            app.shutdown();
            Engine again = new Engine(host, new Rec());
            Map<String, Object> t1 = null;
            for (Object o : J.arr(again.init(), "tasks")) if (J.str(J.asObj(o), "id").equals(id)) t1 = J.asObj(o);
            check(t1 != null && J.str(t1, "clip").equals("0:03 – 0:05"), "kept after a restart");
            again.shutdown();
        } finally {
            app.shutdown();
            srv.stop(0);
        }
    }

    static final class DesktopHost implements Host {
        final File data, cache, save;
        final List<String> runtimeParts = new ArrayList<>();

        DesktopHost(File root, File save) {
            this.data = new File(root, "data");
            this.cache = new File(root, "cache");
            this.save = save;
            data.mkdirs();
            cache.mkdirs();
            if (save != null) save.mkdirs();
        }

        @Override
        public File dataDir() {
            return data;
        }

        @Override
        public File cacheDir() {
            return cache;
        }

        @Override
        public Proc start(List<String> cmd, Map<String, String> env) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(env);
            final Process p = pb.start();
            return new Proc() {
                @Override
                public OutputStream stdin() {
                    return p.getOutputStream();
                }

                @Override
                public InputStream stdout() {
                    return p.getInputStream();
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
                    p.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            };
        }

        @Override
        public List<String> ytdlpCommand(File dir) {
            return Arrays.asList("python3", dir.getAbsolutePath());
        }

        @Override
        public List<String> pythonCommand(List<String> args) {
            List<String> c = new ArrayList<>();
            c.add("python3");
            c.addAll(args);
            return c;
        }

        @Override
        public List<String> ffmpegCommand(List<String> args) {
            List<String> c = new ArrayList<>();
            c.add("ffmpeg");
            c.addAll(args);
            return c;
        }

        @Override
        public List<String> ffprobeCommand(List<String> args) {
            List<String> c = new ArrayList<>();
            c.add("ffprobe");
            c.addAll(args);
            return c;
        }

        @Override
        public List<String> sepCommand(List<String> args) {
            List<String> c = new ArrayList<>();
            c.add(SEP_BIN.getAbsolutePath());
            c.addAll(args);
            return c;
        }

        /** The phone's memory as the test wants it ({-1, -1}: unknown). */
        volatile long[] mem = {-1, -1};

        @Override
        public long[] memory() {
            return mem.clone();
        }

        @Override
        public InputStream openAsset(String name) throws IOException {
            return new FileInputStream("assets/" + name);
        }

        @Override
        public InputStream openContent(String uri) throws IOException {
            return new FileInputStream(uri);
        }

        final List<String> forgotten = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void forgetContent(String uri) {
            forgotten.add(uri);
        }

        @Override
        public Map<String, String> toolEnv() {
            Map<String, String> e = new HashMap<>();
            e.put("PYTHONUTF8", "1");
            e.put("PYTHONIOENCODING", "utf-8");
            e.put("YTDLP_TEST_MARKER", "yt-dlp-test-marker");
            return e;
        }

        @Override
        public List<String> ytdlpPlatformArgs() {
            return new ArrayList<>();
        }

        @Override
        public List<String> runtimeMissing() {
            return new ArrayList<>(runtimeParts);
        }

        @Override
        public long runtimeSize(String part) {
            return 1_000_000;
        }

        @Override
        public long runtimeDownloadSize(String part) {
            return 500_000;
        }

        final List<String> phases = new ArrayList<>();

        @Override
        public void installRuntime(String part, Cancel cancel, StepProgress progress) {
            progress.phase("download");
            phases.add(part + ":download");
            for (int i = 1; i <= 5; i++) progress.onBytes(i * 100_000L, 500_000);
            progress.phase("extract");
            phases.add(part + ":extract");
            for (int i = 1; i <= 10; i++) progress.onBytes(i * 100_000L, 1_000_000);
            runtimeParts.remove(part);
        }

        @Override
        public void runtimeBroken() {
            // nothing to unpack on a desktop
        }

        @Override
        public InputStream openBundledYtdlp() throws IOException {
            return new FileInputStream("assets/ytdlp.zip");
        }

        @Override
        public String bundledYtdlpVersion() {
            String s = Zip.readText(new File("assets/ytdlp.zip"), "yt_dlp/version.py", 4096);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("__version__ = '([^']+)'").matcher(s == null ? "" : s);
            return m.find() ? m.group(1) : "";
        }

        @Override
        public long freeBytes(File dir) {
            return dir.getUsableSpace();
        }

        @Override
        public void symlink(String target, File link) throws IOException {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        }

        @Override
        public Saved save(File file, String displayName, String kind, String subdir, Cancel cancel, Http.ByteProgress progress)
                throws IOException {
            String ext = displayName.substring(displayName.lastIndexOf('.') + 1);
            String base = displayName.substring(0, displayName.lastIndexOf('.'));
            File dir = subdir.isEmpty() ? save : new File(save, subdir);
            dir.mkdirs();
            File dest = Text.uniquePath(dir, base, ext);
            Files.copy(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            progress.onBytes(dest.length(), dest.length());
            Saved s = new Saved();
            s.uri = dest.getAbsolutePath();
            s.name = dest.getName();
            s.folderLabel = saveLabel(subdir);
            s.size = dest.length();
            s.mime = kind.equals("audio") ? "audio/mpeg" : "video/mp4";
            return s;
        }

        @Override
        public String saveLabel() {
            return "التنزيلات (Downloads)";
        }

        @Override
        public String saveLabel(String subdir) {
            return subdir.isEmpty() ? saveLabel() : saveLabel() + " ← " + subdir;
        }

        @Override
        public boolean exists(String uri) {
            return new File(uri).isFile();
        }

        @Override
        public String smallPicture(String dataUrl) {
            return dataUrl;
        }

        final List<String> logList = java.util.Collections.synchronizedList(new ArrayList<>());

        String logs() {
            synchronized (logList) {
                return String.join("\n", logList);
            }
        }

        @Override
        public void log(String message) {
            logList.add(message);
            System.out.println("  [log] " + message.replace("\n", "\n        "));
        }
    }
}
