package com.alihelal.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Removing the music from a recording (the same way as HELINK for Windows, nomusic.go):
 * ffmpeg decodes the sound to 44.1 kHz stereo, the music remover (libhelinksep.so, the
 * voice network Kim_Vocal_2) keeps the voices, and ffmpeg writes them in the file's own
 * format: a video keeps its picture as it is (copied, not encoded again), its subtitles and
 * chapters; an MP3 its cover and its tags.
 *
 * The voices are written to a file next to the work (when there is room), with a
 * checkpoint every chunk of about 6 seconds: a removal that was paused, or stopped by the
 * system, continues where it was. Without room, they stream straight into the encoder.
 */
final class MediaSep {
    private MediaSep() {}

    static final int RATE = 44100;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** A failure with the code of its message (sep_unreadable, sep_failed, sep_memory, disk…). */
    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;

        Failure(String code, String details) {
            super(code + ": " + details);
            this.code = code;
        }
    }

    // ---------------------------------------------------------------------------------
    // What a file holds
    // ---------------------------------------------------------------------------------

    static final class Probe {
        String format = "";     // ffmpeg's names of the container ("mov,mp4,m4a,3gp,3g2,mj2", "mp3"…)
        double duration;        // seconds, 0 if unknown
        double start;           // when the file's first stream starts
        boolean video;          // a moving picture (not only a cover)
        int cover = -1;         // the stream of a cover picture (JPEG or PNG), -1 if none
        boolean audio;
        String acodec = "";
        long abitrate;          // bits per second of the sound, 0 if unknown
        int channels;
        double astart;
        String title = "";      // the title tag, "" if none
        boolean titleOfStream;  // it is a tag of the sound (Ogg) rather than of the file
        boolean subs;
    }

    private static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                double d = Double.parseDouble(Str.trimSpace((String) v));
                return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String tagOf(Map<String, Object> tags, String name) {
        if (tags == null) return "";
        for (Map.Entry<String, Object> e : tags.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && e.getValue() instanceof String) return Str.trimSpace((String) e.getValue());
        }
        return "";
    }

    static Probe parseProbe(String json) throws IOException {
        Json.Obj j = Json.parseObject(Str.trimSpace(json));
        if (j == null) throw new IOException("ffprobe gave no JSON");
        Probe p = new Probe();
        Map<String, Object> format = J.obj(j, "format");
        p.format = J.str(format, "format_name");
        p.start = num(J.raw(format, "start_time"));
        List<Object> streams = J.arr(j, "streams");
        if (streams != null) {
            for (Object o : streams) {
                Map<String, Object> s = J.asObj(o);
                if (s == null) continue;
                String type = J.str(s, "codec_type");
                Map<String, Object> disp = J.obj(s, "disposition");
                if (type.equals("video")) {
                    if (num(J.raw(disp, "attached_pic")) == 1 || num(J.raw(disp, "timed_thumbnails")) == 1) {
                        String cn = J.str(s, "codec_name");
                        if (p.cover < 0 && (cn.equals("mjpeg") || cn.equals("png"))) p.cover = (int) num(J.raw(s, "index"));
                        continue;
                    }
                    p.video = true;
                } else if (type.equals("audio")) {
                    if (p.audio) continue; // the first sound track is the one used
                    p.audio = true;
                    p.acodec = J.str(s, "codec_name");
                    p.channels = (int) num(J.raw(s, "channels"));
                    p.abitrate = (long) num(J.raw(s, "bit_rate"));
                    p.astart = num(J.raw(s, "start_time"));
                    p.duration = num(J.raw(s, "duration"));
                    String t = tagOf(J.obj(s, "tags"), "title");
                    if (t.length() > 0) {
                        p.title = t;
                        p.titleOfStream = true;
                    }
                } else if (type.equals("subtitle")) {
                    p.subs = true;
                }
            }
        }
        if (p.duration <= 0) p.duration = num(J.raw(format, "duration"));
        String t = tagOf(J.obj(format, "tags"), "title");
        if (t.length() > 0) {
            p.title = t;
            p.titleOfStream = false;
        }
        if (p.audio && p.abitrate <= 0 && !p.video) p.abitrate = (long) num(J.raw(format, "bit_rate")); // a sound file: its own rate
        return p;
    }

    /** ffmpeg's name for a file (without "file:", a name like "a:b.mp3" would be taken for a protocol). */
    static String ff(File f) {
        return "file:" + f.getAbsolutePath();
    }

    static Probe probe(Host host, File f, Cancel cancel) throws IOException {
        List<String> cmd = host.ffprobeCommand(Arrays.asList("-v", "error", "-hide_banner", "-print_format", "json",
                "-show_format", "-show_streams", "-i", ff(f)));
        Runner.Result r = Runner.run(host, cmd, host.toolEnv(), true, null, cancel);
        if (r.cancelled || cancel.isCancelled()) throw new InterruptedIOException("cancelled");
        if (r.exitCode != 0) throw new Failure("sep_unreadable", "ffprobe exit " + r.exitCode + ": " + Str.tailLines(r.log, 8));
        try {
            return parseProbe(r.stdout);
        } catch (IOException e) {
            throw new Failure("sep_unreadable", e.getMessage() + "\n" + Str.tailLines(r.log, 8));
        }
    }

    // ---------------------------------------------------------------------------------
    // The result's format
    // ---------------------------------------------------------------------------------

    static final class Plan {
        boolean video;
        String ext = "";         // of the result
        String muxer = "";       // ffmpeg's name of its container
        List<String> codec = new ArrayList<String>(); // how its sound is encoded
        long rate;               // about how many bits per second the sound takes (for the space it needs)
        boolean mono;
        boolean cover;           // keep the cover picture (sound files)
        boolean subs;            // copy the subtitles (a video in the same kind of file)
        boolean attach;          // copy the attached fonts (Matroska)
        String title = "";       // the new title tag ("" for none)
        boolean titleOfStream;
        double offset;           // a video's sound starts this many seconds after its picture

        Plan copy() {
            Plan p = new Plan();
            p.video = video;
            p.ext = ext;
            p.muxer = muxer;
            p.codec = new ArrayList<String>(codec);
            p.rate = rate;
            p.mono = mono;
            p.cover = cover;
            p.subs = subs;
            p.attach = attach;
            p.title = title;
            p.titleOfStream = titleOfStream;
            p.offset = offset;
            return p;
        }

        private void use(String codec, long rate) {
            this.rate = rate;
            this.codec = new ArrayList<String>(Arrays.asList("-c:a", codec, "-b:a", kbps(rate)));
        }

        void useMP3(long br) {
            use("libmp3lame", mp3Rate(br));
        }

        void useAAC(long br) {
            use("aac", clampRate(br, 128000, 256000, 160000));
        }

        void useOpus(long br) {
            use("libopus", clampRate(br, 64000, 256000, 128000));
        }

        void useVorbis(long br) {
            use("libvorbis", clampRate(br, 64000, 320000, 160000));
        }

        void useLossless(String codec, long rate) {
            this.codec = new ArrayList<String>(Arrays.asList("-c:a", codec));
            this.rate = rate;
        }

        /** Keeps the kind of sound of a Matroska file when it can. */
        void sameFamily(Probe p) {
            if (p.acodec.equals("opus")) useOpus(p.abitrate);
            else if (p.acodec.equals("vorbis")) useVorbis(p.abitrate);
            else if (p.acodec.equals("flac")) useLossless("flac", 900000);
            else useAAC(p.abitrate);
        }
    }

    static long clampRate(long br, long lo, long hi, long def) {
        if (br <= 0) return def;
        return Math.max(lo, Math.min(hi, br));
    }

    static String kbps(long br) {
        return ((br + 500) / 1000) + "k";
    }

    /** The MP3 rate for a sound of br bits per second: the standard one at or just above it. */
    static long mp3Rate(long br) {
        long r = clampRate(br, 64000, 320000, 192000);
        for (long s : new long[]{64, 80, 96, 112, 128, 160, 192, 224, 256, 320}) {
            if (s * 1000 * 103 / 100 >= r) return s * 1000;
        }
        return 320000;
    }

    /** " (بدون موسيقى)", the end of the name of a file without its music. */
    static String suffix() {
        return " (" + I18n.t("بدون موسيقى", "no music") + ")";
    }

    private static boolean endsWithSuffix(String title) {
        String t = Str.trimSpace(title);
        return t.endsWith("(بدون موسيقى)") || t.endsWith("(no music)");
    }

    /**
     * The result's format: the file's own (by its extension), MP3 for kinds ffmpeg cannot
     * write well, and MP3 at mp3Rate ("192K") for a download in MP3.
     */
    static Plan planFor(Probe p, String ext, String mp3) {
        ext = Str.lower(ext);
        Plan pl = new Plan();
        pl.ext = ext;
        pl.mono = p.channels == 1;
        if (p.title.length() > 0 && !endsWithSuffix(p.title)) {
            pl.title = p.title + suffix();
            pl.titleOfStream = p.titleOfStream;
        }
        if (mp3 != null && mp3.length() > 0) {
            long rate = 192;
            try {
                rate = Long.parseLong(Str.trimSuffix(Str.upper(Str.trimSpace(mp3)), "K"));
            } catch (NumberFormatException ignored) {
                // the default
            }
            pl.ext = "mp3";
            pl.muxer = "mp3";
            pl.useMP3(rate * 1000);
            pl.cover = true;
            pl.titleOfStream = false;
            return pl;
        }
        if (p.video) {
            pl.video = true;
            double off = p.astart - p.start;
            if (off > 0.005 && off < 60) pl.offset = off;
            if (ext.equals("mp4") || ext.equals("m4v")) {
                pl.muxer = "mp4";
                pl.subs = true;
                pl.useAAC(p.abitrate);
            } else if (ext.equals("mov")) {
                pl.muxer = "mov";
                pl.subs = true;
                pl.useAAC(p.abitrate);
            } else if (ext.equals("mkv")) {
                pl.muxer = "matroska";
                pl.subs = true;
                pl.attach = true;
                pl.sameFamily(p);
            } else if (ext.equals("webm")) {
                pl.muxer = "webm";
                pl.subs = true;
                pl.useOpus(p.abitrate);
            } else if (ext.equals("avi")) {
                pl.muxer = "avi";
                pl.useMP3(p.abitrate);
            } else if (ext.equals("3gp")) {
                pl.ext = "mp4";
                pl.muxer = "mp4";
                pl.useAAC(p.abitrate);
            } else {
                pl.ext = "mkv";
                pl.muxer = "matroska";
                pl.subs = true;
                pl.sameFamily(p);
            }
            pl.titleOfStream = false;
            return pl;
        }
        if (ext.equals("mp3")) {
            pl.muxer = "mp3";
            pl.cover = true;
            pl.useMP3(p.abitrate);
        } else if (ext.equals("m4a") || ext.equals("m4b")) {
            pl.muxer = "ipod";
            pl.cover = true;
            pl.useAAC(p.abitrate);
        } else if (ext.equals("mp4") || ext.equals("m4v") || ext.equals("mov") || ext.equals("3gp")) {
            pl.ext = "m4a";
            pl.muxer = "ipod";
            pl.cover = true;
            pl.useAAC(p.abitrate);
        } else if (ext.equals("aac")) {
            pl.muxer = "adts";
            pl.useAAC(p.abitrate);
        } else if (ext.equals("ogg") || ext.equals("oga") || ext.equals("opus")) {
            pl.muxer = "ogg";
            if (p.acodec.equals("opus") || ext.equals("opus")) pl.useOpus(p.abitrate);
            else pl.useVorbis(p.abitrate);
        } else if (ext.equals("webm")) {
            pl.muxer = "webm";
            pl.useOpus(p.abitrate);
        } else if (ext.equals("mka")) {
            pl.muxer = "matroska";
            pl.sameFamily(p);
        } else if (ext.equals("flac")) {
            pl.muxer = "flac";
            pl.cover = true;
            pl.useLossless("flac", 900000);
        } else if (ext.equals("wav")) {
            pl.muxer = "wav";
            pl.useLossless("pcm_s16le", 1411200);
        } else if (ext.equals("aif") || ext.equals("aiff") || ext.equals("aifc")) {
            pl.ext = "aiff";
            pl.muxer = "aiff";
            pl.useLossless("pcm_s16be", 1411200);
        } else { // wma, ape, amr, ac3…
            pl.ext = "mp3";
            pl.muxer = "mp3";
            pl.cover = true;
            pl.useMP3(p.abitrate);
        }
        if (!pl.muxer.equals("ogg") && !pl.muxer.equals("matroska") && !pl.muxer.equals("webm")) pl.titleOfStream = false;
        return pl;
    }

    static List<String> decodeArgs(File src) {
        return Arrays.asList("-nostdin", "-hide_banner", "-loglevel", "error", "-i", ff(src),
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-f", "f32le", "-acodec", "pcm_f32le",
                "-ar", String.valueOf(RATE), "-ac", "2", "pipe:1");
    }

    /**
     * ffmpeg's options to write the voices (from its input, or from the file voices) in the
     * plan's format. A video's sound is written on its own (into dst, a Matroska file) and
     * joined with the picture afterwards.
     */
    static List<String> encodeArgs(Probe p, Plan pl, File src, File dst, File voices) {
        List<String> a = new ArrayList<String>(Arrays.asList("-nostdin", "-hide_banner", "-loglevel", "error"));
        if (voices != null) a.addAll(Arrays.asList("-progress", "pipe:1", "-nostats"));
        a.addAll(Arrays.asList("-f", "f32le", "-ar", String.valueOf(RATE), "-ac", "2", "-i",
                voices != null ? ff(voices) : "pipe:0"));
        if (pl.video) {
            a.addAll(Arrays.asList("-map", "0:a:0"));
            a.addAll(pl.codec);
            if (pl.mono) a.addAll(Arrays.asList("-ac", "1"));
            a.addAll(Arrays.asList("-f", "matroska", "-y", ff(dst)));
            return a;
        }
        a.addAll(Arrays.asList("-i", ff(src), "-map", "0:a:0"));
        if (pl.cover && p.cover >= 0) {
            a.addAll(Arrays.asList("-map", "1:" + p.cover, "-c:v", "copy", "-disposition:v:0", "attached_pic"));
        }
        a.addAll(pl.codec);
        if (pl.mono) a.addAll(Arrays.asList("-ac", "1"));
        a.addAll(Arrays.asList("-map_metadata", "1", "-map_metadata:s:a:0", "1:s:a:0", "-map_chapters", "1"));
        if (pl.title.length() > 0) {
            a.add(pl.titleOfStream ? "-metadata:s:a:0" : "-metadata");
            a.add("title=" + pl.title);
        }
        if (pl.muxer.equals("mp3")) a.addAll(Arrays.asList("-id3v2_version", "3"));
        a.addAll(Arrays.asList("-f", pl.muxer, "-y", ff(dst)));
        return a;
    }

    /** ffmpeg's options to join a video's picture (and subtitles, chapters, tags) with its new sound, both copied. */
    static List<String> muxArgs(Plan pl, File src, File voice, File dst) {
        List<String> a = new ArrayList<String>(Arrays.asList("-nostdin", "-hide_banner", "-loglevel", "error",
                "-progress", "pipe:1", "-nostats", "-i", ff(src)));
        if (pl.offset > 0) a.addAll(Arrays.asList("-itsoffset", String.format(java.util.Locale.ROOT, "%.3f", pl.offset)));
        a.addAll(Arrays.asList("-i", ff(voice), "-map", "0:V", "-map", "1:a:0"));
        if (pl.subs) a.addAll(Arrays.asList("-map", "0:s?"));
        if (pl.attach) a.addAll(Arrays.asList("-map", "0:t?"));
        a.addAll(Arrays.asList("-c", "copy", "-map_metadata", "0", "-map_metadata:s:a:0", "0:s:a:0", "-map_chapters", "0",
                "-disposition:a:0", "default"));
        if (pl.title.length() > 0) a.addAll(Arrays.asList("-metadata", "title=" + pl.title));
        a.addAll(Arrays.asList("-f", pl.muxer, "-y", ff(dst)));
        return a;
    }

    /** About how much the result takes while it is made (a video: its copy and its sound on its own). */
    static long spaceNeeded(Probe p, Plan pl, long srcSize) {
        if (p.duration <= 0) return 20L << 20;
        long rate = pl.mono ? pl.rate * 6 / 10 : pl.rate;
        long sound = (long) (p.duration * rate / 8) + (1L << 20);
        long need = sound + (20L << 20);
        if (pl.video) need += srcSize + sound;
        return need;
    }

    /** The voices as they come out of the remover (float stereo), kept for the checkpoints. */
    static long rawBytes(Probe p) {
        return p.duration <= 0 ? 0 : (long) Math.ceil(p.duration * RATE) * 8;
    }

    // ---------------------------------------------------------------------------------
    // One file
    // ---------------------------------------------------------------------------------

    interface Listener {
        /** The removal itself: percent of the file, seconds left (-1 while unknown). */
        void separating(double percent, double eta);

        /** A last step: "finish" (writing the result) or "merge" (joining the picture); percent -1 if unknown. */
        void finishing(String stage, double percent);
    }

    static final class Job {
        File src;         // the file
        File work;        // the folder the result is made in
        String base = ""; // the result's name, without extension
        String mp3 = "";  // "192K": the result is an MP3 at that rate (a download in MP3)
        String tag = "";  // what the checkpoint belongs to
        boolean resumable;// keep the voices in a file, with checkpoints (needs the room for them)
        Probe probe;
        Plan plan;
    }

    static File voicesFile(File work) {
        return new File(work, "voice.f32");
    }

    static File stateFile(File work) {
        return new File(work, "voice.state");
    }

    /** Removes what the checkpoints left (once the result is made). */
    static void dropVoices(File work) {
        for (File f : new File[]{voicesFile(work), stateFile(work), new File(work, "voice.state.tmp"), new File(work, "voice.mka")}) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Makes, in j.work, the copy of j.src without its music, named j.base and the plan's
     * extension, and returns it. Throws InterruptedIOException when cancelled.
     */
    static File run(Host host, SepTool sep, Job j, Cancel c, Listener l) throws IOException {
        Plan pl = j.plan;
        File out = new File(j.work, j.base + "." + pl.ext);
        File sound = pl.video ? new File(j.work, "voice.mka") : out;
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        //noinspection ResultOfMethodCallIgnored
        sound.delete();
        File voices = j.resumable ? voicesFile(j.work) : null;
        if (voices == null) {
            //noinspection ResultOfMethodCallIgnored
            voicesFile(j.work).delete();
            //noinspection ResultOfMethodCallIgnored
            stateFile(j.work).delete();
        }
        separate(host, sep, j, voices, sound, c, l);
        if (voices != null) encodeVoices(host, j, voices, sound, c, l);
        if (!fileOK(sound)) throw new Failure("sep_failed", "no result was written");
        if (!pl.video) return out;
        try {
            return joinPicture(host, j, sound, c, l);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            sound.delete();
        }
    }

    static boolean fileOK(File f) {
        return f.isFile() && f.length() > 0;
    }

    /** How many frames a chunk has: UVR's 256 when the phone has the memory, else less. */
    static int dimT(Host host) {
        long[] m = host.memory();
        long avail = m[0], total = m[1];
        if (avail < 0) return 256;
        if (total >= (3500L << 20) && avail >= (900L << 20)) return 256;
        if (avail >= (450L << 20) || total >= (2500L << 20)) return 128;
        return 64;
    }

    /** What one run of the remover ended with. */
    private static final class SepRun {
        int exit = -1;
        boolean done, killed;
        boolean decodeFailed; // ffmpeg could not read the file (to its end)
        String log = "";
        long samples;
    }

    private static void separate(Host host, SepTool sep, Job j, File voices, File sound, Cancel c, Listener l) throws IOException {
        int dimT = dimT(host);
        boolean freshAgain = false;
        for (int attempt = 0; ; attempt++) {
            SepRun r = separateOnce(host, sep, j, voices, sound, dimT, c, l);
            if (c.isCancelled()) throw new InterruptedIOException("cancelled");
            if (r.done) return;
            String l1 = Str.lower(r.log);
            if ((r.killed || l1.contains("out of memory")) && dimT > 64 && attempt < 3) {
                // Stopped by the system, most likely short of memory: again with smaller chunks.
                host.log("music remover stopped (exit " + r.exit + ") with " + dimT + " frames a chunk: trying with less memory");
                dimT /= 2;
                if (voices != null) {
                    //noinspection ResultOfMethodCallIgnored
                    stateFile(j.work).delete();
                }
                continue;
            }
            if (r.killed || l1.contains("out of memory")) throw new Failure("sep_memory", r.log);
            if (r.exit == 4 && !freshAgain && voices != null) {
                // The checkpoint does not fit this file: from the start.
                freshAgain = true;
                //noinspection ResultOfMethodCallIgnored
                stateFile(j.work).delete();
                continue;
            }
            if (r.exit == 6) {
                sep.forget();
                throw new Failure("sep_setup", r.log);
            }
            if (r.exit == 5 || l1.contains("no space left") || l1.contains("enospc") || l1.contains("errno 28")) {
                throw new Failure("disk", r.log);
            }
            if (r.exit == 3 || r.decodeFailed) throw new Failure("sep_unreadable", r.log);
            throw new Failure("sep_failed", r.log);
        }
    }

    private static SepRun separateOnce(Host host, SepTool sep, final Job j, File voices, File sound, int dimT, final Cancel c,
                                       final Listener l) throws IOException {
        final SepRun run = new SepRun();
        List<String> args = new ArrayList<String>(Arrays.asList("separate", "--model", sep.weights().getAbsolutePath(),
                "--dimt", String.valueOf(dimT)));
        args.addAll(sep.kernelArgs());
        if (voices != null) {
            args.addAll(Arrays.asList("--out", voices.getAbsolutePath(), "--state", stateFile(j.work).getAbsolutePath(),
                    "--tag", j.tag));
        } else {
            args.addAll(Arrays.asList("--out", "-"));
        }
        Map<String, String> env = host.toolEnv();
        final Runner.Ring log = new Runner.Ring(40);
        final List<Host.Proc> procs = new ArrayList<Host.Proc>();
        Runnable killer = new Runnable() {
            @Override
            public void run() {
                synchronized (procs) {
                    for (Host.Proc p : procs) p.kill();
                }
            }
        };
        final Host.Proc dec, sp, enc;
        try {
            dec = host.start(host.ffmpegCommand(decodeArgs(j.src)), env);
            synchronized (procs) {
                procs.add(dec);
            }
            sp = host.start(host.sepCommand(args), env);
            synchronized (procs) {
                procs.add(sp);
            }
            if (voices == null) {
                enc = host.start(host.ffmpegCommand(encodeArgs(j.probe, j.plan, j.src, sound, null)), env);
                synchronized (procs) {
                    procs.add(enc);
                }
            } else {
                enc = null;
            }
        } catch (IOException e) {
            killer.run();
            throw new Failure("sep_failed", "could not start: " + e);
        }
        c.onCancel(killer);
        List<Thread> threads = new ArrayList<Thread>();
        try {
            threads.add(pump(dec.stdout(), sp.stdin(), "decode→remove"));
            if (enc != null) threads.add(pump(sp.stdout(), enc.stdin(), "remove→encode"));
            else threads.add(drain(sp.stdout(), null, ""));
            threads.add(drain(dec.stderr(), log, "decode: "));
            if (enc != null) {
                threads.add(drain(enc.stderr(), log, "encode: "));
                threads.add(drain(enc.stdout(), null, ""));
            }
            final long total = (long) Math.round(j.probe.duration * RATE);
            final long began = System.currentTimeMillis();
            final long[] startAt = {-1};
            final long[] lastSent = {0};
            BufferedReader r = new BufferedReader(new InputStreamReader(sp.stderr(), UTF8), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                line = Str.trimSpace(line);
                if (line.length() == 0) continue;
                String[] f = line.split(" ");
                if (f[0].equals("at") || f[0].equals("saved") || f[0].equals("start") || f[0].equals("done")) {
                    long n = f.length > 1 ? parseLong(f[1]) : 0;
                    if (f[0].equals("start")) {
                        startAt[0] = n;
                        if (n > 0) host.log("music remover: continuing from " + Math.round(n / (double) RATE) + " s");
                    }
                    if (f[0].equals("done")) {
                        run.done = true;
                        run.samples = n;
                    }
                    if (total > 0 && l != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastSent[0] < 400 && !f[0].equals("done")) continue;
                        lastSent[0] = now;
                        double share = Math.min(0.995, (double) n / total);
                        long from = Math.max(0, startAt[0]);
                        double secs = (now - began) / 1000.0;
                        double eta = -1;
                        if (n > from && secs > 2 && (double) (n - from) / total > 0.01) eta = secs * (total - n) / (n - from);
                        l.separating(share * 100, eta);
                    }
                    continue;
                }
                if (f[0].equals("kernel")) host.log("music remover: " + line);
                log.add(line);
            }
            int code = sp.waitFor();
            run.exit = code;
            if (!(run.done && code == 0)) {
                run.done = false;
                dec.kill();
                if (enc != null) enc.kill();
                // SIGKILL from outside: the system stopped it (short of memory).
                if (code == 137 && !c.isCancelled()) run.killed = true;
            } else if (startAt[0] > 0 && run.samples == startAt[0]) {
                // It found its work already done and read nothing: the decoder is not wanted.
                dec.kill();
            } else {
                int dexit = dec.waitFor();
                if (dexit != 0) {
                    // The remover saw the end early: the file is damaged or cut.
                    run.done = false;
                    run.decodeFailed = true;
                    log.add("decode: exit " + dexit);
                }
            }
            dec.waitFor();
            int eexit = enc != null ? enc.waitFor() : 0;
            for (Thread t : threads) t.join(5000);
            if (run.done && enc != null && eexit != 0) {
                run.done = false;
                log.add("encode: exit " + eexit);
            }
            if (run.done && run.samples == 0 && total > 0) {
                run.done = false;
                run.decodeFailed = true;
                log.add("decode: no sound decoded");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killer.run();
            throw new InterruptedIOException("interrupted");
        } finally {
            c.removeHook(killer);
            if (!run.done) killer.run();
        }
        run.log = log.toString();
        return run;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Writes the voices kept in a file in the plan's format (the result, or a video's sound). */
    private static void encodeVoices(Host host, final Job j, File voices, File sound, Cancel c, final Listener l) throws IOException {
        l.finishing("finish", -1);
        Runner.Result r = Runner.run(host, host.ffmpegCommand(encodeArgs(j.probe, j.plan, j.src, sound, voices)), host.toolEnv(),
                false, progressLines("finish", j.probe.duration, l), c);
        if (r.cancelled || c.isCancelled()) throw new InterruptedIOException("cancelled");
        if (r.exitCode != 0 || !fileOK(sound)) {
            //noinspection ResultOfMethodCallIgnored
            sound.delete();
            throw ffError("sep_failed", r.log);
        }
    }

    /** Reads ffmpeg's -progress lines into percents of the stage. */
    private static Runner.LineHandler progressLines(final String stage, final double duration, final Listener l) {
        return new Runner.LineHandler() {
            long last;

            @Override
            public void onLine(String line, boolean fromStderr) {
                if (fromStderr || duration <= 0 || !line.startsWith("out_time_us=")) return;
                long now = System.currentTimeMillis();
                if (now - last < 300) return;
                last = now;
                double us = parseLong(line.substring("out_time_us=".length()));
                l.finishing(stage, Math.max(0, Math.min(100, us / 1e4 / duration)));
            }
        };
    }

    /** What ffmpeg said, in the user's words: no space, no permission, or code. */
    static Failure ffError(String code, String logText) {
        String l = Str.lower(logText);
        if (l.contains("no space") || l.contains("errno 28") || l.contains("disk full") || l.contains("enospc")) code = "disk";
        else if (l.contains("permission denied")) code = "permission";
        return new Failure(code, Str.tailLines(logText, 15));
    }

    /**
     * Puts the new sound with the video's picture (both copied). When the file's own kind
     * refuses something (a subtitle, a kind of picture), it is tried without the subtitles,
     * then as an MKV, which takes anything.
     */
    private static File joinPicture(Host host, Job j, File sound, Cancel c, Listener l) throws IOException {
        Plan pl = j.plan;
        List<Plan> tries = new ArrayList<Plan>();
        tries.add(pl);
        if (pl.subs || pl.attach) {
            Plan bare = pl.copy();
            bare.subs = false;
            bare.attach = false;
            tries.add(bare);
        }
        if (!pl.muxer.equals("matroska")) {
            Plan mkv = pl.copy();
            mkv.ext = "mkv";
            mkv.muxer = "matroska";
            mkv.subs = false;
            mkv.attach = false;
            tries.add(mkv);
        }
        l.finishing("merge", 0);
        String lastLog = "";
        for (Plan t : tries) {
            File dst = new File(j.work, j.base + "." + t.ext);
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            Runner.Result r = Runner.run(host, host.ffmpegCommand(muxArgs(t, j.src, sound, dst)), host.toolEnv(), false,
                    progressLines("merge", j.probe.duration, l), c);
            if (r.cancelled || c.isCancelled()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                throw new InterruptedIOException("cancelled");
            }
            if (r.exitCode == 0 && fileOK(dst)) {
                j.plan = t;
                return dst;
            }
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            host.log("joining " + j.src.getName() + " as " + t.muxer + ": exit " + r.exitCode + ": " + Str.tailLines(r.log, 10));
            lastLog = r.log;
            Failure e = ffError("sep_failed", r.log);
            if (!e.code.equals("sep_failed")) throw e; // no space, no permission: another kind of file would not help
        }
        throw ffError("sep_failed", lastLog);
    }

    // ---------------------------------------------------------------------------------
    // Pipes
    // ---------------------------------------------------------------------------------

    /** Copies in to out until in ends, then closes out (the next program sees the end). */
    private static Thread pump(final InputStream in, final OutputStream out, String name) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[1 << 16];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (IOException ignored) {
                    // one side stopped: the programs' exit codes tell why
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Reads a program's output to its end (into log, when given). */
    private static Thread drain(final InputStream in, final Runner.Ring log, final String prefix) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (log == null) {
                        byte[] buf = new byte[8192];
                        //noinspection StatementWithEmptyBody
                        while (in.read(buf) > 0) {
                            // not wanted
                        }
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (Str.trimSpace(line).length() > 0) log.add(prefix + line);
                    }
                } catch (IOException ignored) {
                    // the program was stopped
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }
        }, "ffmpeg output");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
