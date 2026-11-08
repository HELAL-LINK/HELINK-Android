package com.alihelal.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

/**
 * The music remover's model, set up the first time music is to be removed: the voice
 * network Kim_Vocal_2 (MDX-Net, as Ultimate Vocal Remover publishes it on GitHub, 67 MB),
 * the same one HELINK for Windows uses. It is downloaded, checked, turned by the app's
 * music remover (libhelinksep.so: "convert") into its weight file (33 MB) and checked
 * again. The old remover of HELINK 1.4 (Spleeter) is deleted: it left far more music
 * behind.
 */
public final class SepTool {
    static final String FILE = "kim-vocal-2.hlw";
    static final long WEIGHTS_SIZE = 33354944L;

    static final class Source {
        final String file, sha256, weightsSha256;
        final long size;
        final List<String> urls;

        Source(String file, long size, String sha256, String weightsSha256, String... urls) {
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
            this.weightsSha256 = weightsSha256;
            this.urls = Arrays.asList(urls);
        }
    }

    /**
     * Where the model comes from, in the order tried (tests replace it). The second address
     * is python-audio-separator's copy of the same file.
     */
    static volatile Source[] sources = {
            new Source("Kim_Vocal_2.onnx", 66759214L,
                    "ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b",
                    "d0b06e512a683a749185fc178c70a55d6c0ceb567761345e2f8042c82fd91dc2",
                    "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx",
                    "https://github.com/nomadkaraoke/python-audio-separator/releases/download/model-configs/Kim_Vocal_2.onnx"),
    };

    /** HELINK 1.4's music remover (Spleeter), deleted when found. */
    static final String[] OLD_FILES = {"spleeter-2stems.hlw", "spleeter-2stems.hlw.ok", "spleeter-2stems.hlw.new",
            "spleeter-2stems.hlw.new.tmp", "sherpa-onnx-spleeter-2stems-fp16.tar.bz2",
            "sherpa-onnx-spleeter-2stems-fp16.tar.bz2.part", "2stems.tar.gz", "2stems.tar.gz.part", "sep_install.py"};

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final Host host;
    private final File dir;
    private final java.util.concurrent.locks.ReentrantLock installing = new java.util.concurrent.locks.ReentrantLock();

    public SepTool(Host host) {
        this.host = host;
        this.dir = new File(host.dataDir(), "sep");
    }

    public File weights() {
        return new File(dir, FILE);
    }

    private File marker() {
        return new File(dir, FILE + ".ok");
    }

    /** Written when the fast loops failed the self-test on this phone: the plain ones are used. */
    private File plainMark() {
        return new File(dir, "plain-loops");
    }

    /** Whether the model is in place (checked when it was set up; here only its size and mark). */
    public boolean ready() {
        File w = weights();
        if (!w.isFile() || w.length() != WEIGHTS_SIZE) return false;
        String mark = readSmall(marker());
        for (Source s : sources) if (s.weightsSha256.equals(mark)) return true;
        return false;
    }

    /** The mark of the weight file in use (for checkpoints: they belong to one model). */
    String weightsMark() {
        return readSmall(marker());
    }

    /** Options the remover needs on this phone ("--kernel go" when its fast loops failed). */
    List<String> kernelArgs() {
        return plainMark().isFile() ? Arrays.asList("--kernel", "go") : java.util.Collections.<String>emptyList();
    }

    /** Bytes the first download takes (for the screen). */
    public static long downloadSize() {
        return sources[0].size;
    }

    /** Free space the setup needs: the download and the weight file (and a little room). */
    static long spaceNeeded() {
        return sources[0].size + WEIGHTS_SIZE + (8L << 20);
    }

    /** Deletes what HELINK 1.4's music remover left (39 MB). */
    void removeOld() {
        for (String n : OLD_FILES) {
            File f = new File(dir, n);
            if (f.exists() && f.delete()) host.log("removed the old music remover's " + n);
        }
    }

    /**
     * Downloads and converts the model if it is not there yet. Only one runs at a time;
     * others wait for it. progress gets phase "download" (bytes) and then "extract".
     */
    public void install(Cancel cancel, Host.StepProgress progress) throws IOException {
        // Another download may be setting it up: wait for it, but stay pausable meanwhile.
        try {
            while (!installing.tryLock(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancel.isCancelled()) throw new InterruptedIOException("cancelled");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
        try {
            if (ready()) return;
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            removeOld();
            //noinspection ResultOfMethodCallIgnored
            marker().delete();
            long free = host.freeBytes(dir);
            if (free >= 0 && free < spaceNeeded() - partSize()) throw new IOException("no space left for the music remover");
            IOException last = null;
            for (int i = 0; i < sources.length; i++) {
                Source s = sources[i];
                File model = new File(dir, s.file);
                try {
                    progress.phase("download");
                    if (!(model.isFile() && model.length() == s.size)) {
                        Http.fetchVerified(s.urls, model, s.size, s.sha256, progress, cancel);
                    }
                } catch (IOException e) {
                    if (cancel.isCancelled()) throw new InterruptedIOException("cancelled");
                    last = e;
                    // Gone or changed at that address: the next source. A cut connection is
                    // reported instead (trying again continues the same file).
                    if (e instanceof Http.StatusException || String.valueOf(e.getMessage()).contains("checksum")) {
                        host.log("music remover source " + s.file + ": " + e);
                        continue;
                    }
                    throw e;
                }
                progress.phase("extract");
                convert(model, s, cancel);
                selfTest(cancel);
                cleanup();
                return;
            }
            throw last != null ? last : new IOException("no source for the music remover");
        } finally {
            installing.unlock();
        }
    }

    private long partSize() {
        long n = 0;
        for (Source s : sources) {
            n += new File(dir, s.file).length() + new File(dir, s.file + ".part").length();
        }
        return n;
    }

    private void convert(File model, Source s, Cancel cancel) throws IOException {
        File tmp = new File(dir, FILE + ".new");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        List<String> cmd = host.sepCommand(Arrays.asList("convert", model.getAbsolutePath(), tmp.getAbsolutePath()));
        StringBuilder log = new StringBuilder();
        String out = Runner.quickWithLog(host, cmd, host.toolEnv(), 10 * 60 * 1000, log);
        if (cancel.isCancelled()) throw new InterruptedIOException("cancelled");
        String sha = "";
        if (out != null) {
            for (String line : out.split("\n")) {
                line = Str.trimSpace(line);
                if (line.startsWith("ok ")) sha = Str.trimSpace(line.substring(3));
            }
        }
        if (!sha.equals(s.weightsSha256) || tmp.length() != WEIGHTS_SIZE) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            String l = Str.lower(log.toString());
            if (l.contains("no space") || l.contains("enospc")) throw new IOException("no space left: " + Str.tailLines(log.toString(), 5));
            if (out == null && (l.contains("could not start") || l.contains("exit code 126") || l.contains("exit code 127"))) {
                throw new SetupFailed("the music remover does not run on this phone:\n" + Str.tailLines(log.toString(), 20));
            }
            // The model checked out, so a bad result means something else: start over next time.
            //noinspection ResultOfMethodCallIgnored
            model.delete();
            throw new SetupFailed("converting the music remover failed (got " + sha + "):\n" + Str.tailLines(log.toString(), 20));
        }
        // What is on disk must be exactly what was checked.
        String real = Http.sha256(tmp);
        if (!real.equals(s.weightsSha256)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new SetupFailed("music remover file damaged on disk");
        }
        File w = weights();
        if (w.exists() && !w.delete()) throw new IOException("cannot replace " + w);
        if (!tmp.renameTo(w)) throw new IOException("cannot rename " + tmp);
        Engine.writeText(marker(), s.weightsSha256);
    }

    /**
     * Checks the remover's fast loops against its plain ones on this phone's processor
     * (a fraction of a second); if they disagree, the plain ones are used from now on.
     */
    private void selfTest(Cancel cancel) throws IOException {
        StringBuilder log = new StringBuilder();
        String out = Runner.quickWithLog(host, host.sepCommand(Arrays.asList("selftest")), host.toolEnv(), 120000, log);
        if (cancel.isCancelled()) throw new InterruptedIOException("cancelled");
        if (out != null && out.contains("ok")) {
            //noinspection ResultOfMethodCallIgnored
            plainMark().delete();
            host.log("music remover: " + Str.trimSpace(out));
            return;
        }
        host.log("music remover self-test failed, using the plain loops: " + Str.tailLines(log.toString(), 10));
        Engine.writeText(plainMark(), Str.tailLines(log.toString(), 10));
    }

    /** Removes the download (the weight file is all that is needed now). */
    private void cleanup() {
        for (Source s : sources) {
            for (File f : new File[]{new File(dir, s.file), new File(dir, s.file + ".part")}) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        for (String n : new String[]{FILE + ".new", FILE + ".new.tmp"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, n).delete();
        }
    }

    /** The weight file did not make the network (damaged): set it up again next time. */
    void forget() {
        //noinspection ResultOfMethodCallIgnored
        marker().delete();
    }

    /** Setting up failed for a reason other than the connection or the space. */
    static final class SetupFailed extends IOException {
        private static final long serialVersionUID = 1L;

        SetupFailed(String m) {
            super(m);
        }
    }

    private static String readSmall(File f) {
        try {
            if (!f.isFile() || f.length() > 4096) return "";
            InputStream in = new java.io.FileInputStream(f);
            try {
                return Str.trimSpace(new String(Http.readAll(in), UTF8));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }
}
