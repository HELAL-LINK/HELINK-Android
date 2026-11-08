package com.alihelal.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/** What the engine needs from the platform (the phone, or a desktop test). */
public interface Host {
    /** Private app folder: settings, yt-dlp, unfinished downloads. */
    File dataDir();

    /** Folder the system may clean: yt-dlp's cache and temporary files. */
    File cacheDir();

    /** Starts a program. kill() on the result must stop it and everything it started. */
    Proc start(List<String> cmd, Map<String, String> env) throws IOException;

    /** The command that runs yt-dlp unpacked in dir (python + dir). */
    List<String> ytdlpCommand(File dir);

    /** The command that runs Python with the given arguments. */
    List<String> pythonCommand(List<String> args);

    /** The command that runs ffmpeg with the given arguments (for the music removal). */
    List<String> ffmpegCommand(List<String> args);

    /** The command that runs ffprobe with the given arguments. */
    List<String> ffprobeCommand(List<String> args);

    /**
     * The command that runs the music remover (libhelinksep.so on the phone) with the given
     * arguments, below normal priority (it keeps every core busy for minutes).
     */
    List<String> sepCommand(List<String> args);

    /** The phone's memory: {available, total} bytes ({-1, -1} when unknown). */
    long[] memory();

    /** A file shipped with the app. */
    InputStream openAsset(String name) throws IOException;

    /**
     * Reads a file of the phone given by the address the system gave for it (content://…,
     * from the file picker, a share or the Download folder; a plain path in the tests).
     */
    InputStream openContent(String uri) throws IOException;

    /** The file is not needed any more: the lasting permission to read it can go. */
    void forgetContent(String uri);

    /** Environment for yt-dlp and the tools it starts. */
    Map<String, String> toolEnv();

    /** Options every yt-dlp run needs on this platform (ffmpeg and JavaScript helper locations). */
    List<String> ytdlpPlatformArgs();

    /** Parts of the runtime (Python, ffmpeg) still to unpack; empty when ready. */
    List<String> runtimeMissing();

    /** Size in bytes the part takes once unpacked, for the setup progress bar. */
    long runtimeSize(String part);

    /** Bytes to download for the part (0 when it ships inside the app). */
    long runtimeDownloadSize(String part);

    /** Progress of one setup step, which can download and then unpack. */
    interface StepProgress extends Http.ByteProgress {
        /** "download" or "extract" */
        void phase(String phase);
    }

    void installRuntime(String part, Cancel cancel, StepProgress progress) throws IOException;

    /** The runtime failed to start: unpack it again at the next setup. */
    void runtimeBroken();

    /** The yt-dlp zipapp shipped with the app. */
    InputStream openBundledYtdlp() throws IOException;

    /** The version of the shipped yt-dlp ("2026.10.10.233141"). */
    String bundledYtdlpVersion();

    long freeBytes(File dir);

    /** Makes a symbolic link (used when unpacking). */
    void symlink(String target, File link) throws IOException;

    /**
     * Moves a finished file to where the user finds it (Download/HELINK on the phone), in the
     * folder subdir inside it when not empty (a course's videos: Download/HELINK/<course>).
     */
    Saved save(File file, String displayName, String kind, String subdir, Cancel cancel, Http.ByteProgress progress)
            throws IOException;

    /** Where finished files go, in words: "التنزيلات ← HELINK". */
    String saveLabel();

    /** The same for the folder subdir inside it: "التنزيلات ← HELINK ← <subdir>". */
    String saveLabel(String subdir);

    /** Whether a file saved before (its address from save()) is still there. */
    boolean exists(String uri);

    /**
     * A small copy of a picture given as a data: URL, for the downloads list (the phone
     * shrinks it; "" when it cannot be read). May return the picture unchanged.
     */
    String smallPicture(String dataUrl);

    void log(String message);

    /** A running program. */
    interface Proc {
        /** The program's input (programs that do not read it ignore it). */
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        int waitFor() throws InterruptedException;

        void kill();
    }

    /** A file of the phone to remove the music from: where it is, what it is called. */
    final class Content {
        public String uri = "", name = "", mime = "";
        public long size = -1;
    }

    /** A file in its final place. */
    final class Saved {
        public String uri = "";        // what the system uses to open or share it
        public String name = "";       // the file name the user sees
        public String folderLabel = "";
        public long size;
        public String mime = "";
    }
}
