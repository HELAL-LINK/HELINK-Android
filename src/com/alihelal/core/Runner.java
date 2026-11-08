package com.alihelal.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs yt-dlp and friends, streaming their output lines (port of the Go runTool). */
public final class Runner {
    private Runner() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Result {
        public String stdout = "";  // only filled when captureStdout is set
        public String log = "";     // last lines of stdout+stderr, for error messages
        public int exitCode = -1;
        public boolean cancelled;
    }

    public interface LineHandler {
        void onLine(String line, boolean fromStderr);
    }

    /** Keeps the last lines of output. */
    static final class Ring {
        private final List<String> lines = new ArrayList<String>();
        private final int max;

        Ring(int max) {
            this.max = max;
        }

        synchronized void add(String s) {
            if (s.length() > 2000) s = s.substring(0, 2000) + "…";
            lines.add(s);
            if (lines.size() > max) lines.subList(0, lines.size() - max).clear();
        }

        @Override
        public synchronized String toString() {
            return Str.join("\n", lines);
        }
    }

    /**
     * Starts the command, streams every output line to onLine and waits. Cancelling
     * stops the whole process tree. Throws only when the program could not start.
     */
    public static Result run(Host host, List<String> cmd, Map<String, String> env, final boolean captureStdout,
                             final LineHandler onLine, Cancel cancel) throws IOException {
        final Host.Proc p = host.start(cmd, env);
        final Result res = new Result();
        final Ring ring = new Ring(60);
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Runnable killer = new Runnable() {
            @Override
            public void run() {
                p.kill();
            }
        };
        if (cancel != null) cancel.onCancel(killer);
        Thread out = new Thread(new Runnable() {
            @Override
            public void run() {
                if (captureStdout) {
                    copyAll(p.stdout(), captured);
                } else {
                    readLines(p.stdout(), false, ring, onLine);
                }
            }
        }, "yt-dlp stdout");
        Thread err = new Thread(new Runnable() {
            @Override
            public void run() {
                readLines(p.stderr(), true, ring, onLine);
            }
        }, "yt-dlp stderr");
        out.start();
        err.start();
        try {
            out.join();
            err.join();
            res.exitCode = p.waitFor();
        } catch (InterruptedException e) {
            p.kill();
            Thread.currentThread().interrupt();
            res.cancelled = true;
        } finally {
            if (cancel != null) cancel.removeHook(killer);
        }
        res.log = ring.toString();
        if (captureStdout) res.stdout = new String(captured.toByteArray(), UTF8);
        if (cancel != null && cancel.isCancelled()) res.cancelled = true;
        return res;
    }

    private static void copyAll(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (out) {
                    out.write(buf, 0, n);
                }
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

    private static void readLines(InputStream in, boolean isErr, Ring ring, LineHandler onLine) {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8), 65536);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String l = Str.trimRight(line, "\r\n");
                if (l.length() == 0) continue;
                if (!l.startsWith(Progress.TAG_DOWNLOAD)) ring.add(l);
                if (onLine != null) {
                    try {
                        onLine.onLine(l, isErr);
                    } catch (RuntimeException e) {
                        // a bad line must not stop the reading
                    }
                }
            }
        } catch (IOException ignored) {
            // the program was stopped
        } finally {
            try {
                r.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /** Runs a short command and returns its output (stdout and stderr together), or null on failure. */
    public static String quick(Host host, List<String> cmd, Map<String, String> env, long timeoutMs) {
        StringBuilder log = new StringBuilder();
        String out = quickWithLog(host, cmd, env, timeoutMs, log);
        if (out == null) host.log("quick run failed: " + Str.tailLines(log.toString(), 10));
        return out;
    }

    /**
     * Like quick(), and copies everything the program printed (plus why it failed) into logOut.
     */
    public static String quickWithLog(Host host, List<String> cmd, Map<String, String> env, long timeoutMs, StringBuilder logOut) {
        final StringBuilder sb = new StringBuilder();
        final Cancel c = new Cancel();
        final long deadline = System.currentTimeMillis() + timeoutMs;
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                c.cancel();
            }
        }, "quick-run watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            Result r = run(host, cmd, env, false, new LineHandler() {
                @Override
                public void onLine(String line, boolean fromStderr) {
                    synchronized (sb) {
                        if (sb.length() < 16000) sb.append(line).append('\n');
                    }
                }
            }, c);
            synchronized (sb) {
                logOut.append(sb);
                if (r.cancelled) logOut.append("(timed out after ").append(timeoutMs / 1000).append(" s)\n");
                if (r.exitCode != 0) logOut.append("(exit code ").append(r.exitCode).append(")\n");
                return r.cancelled || r.exitCode != 0 ? null : Str.trimSpace(sb.toString());
            }
        } catch (IOException e) {
            logOut.append("could not start: ").append(e).append('\n');
            return null;
        } finally {
            watchdog.interrupt();
        }
    }
}
