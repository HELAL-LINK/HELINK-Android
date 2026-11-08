package com.alihelal.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/** Small HTTP helpers on top of HttpURLConnection, with cancellation and redirect control. */
public final class Http {
    private Http() {}

    public static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
    public static final String APP_UA = "HELINK/1.2 (Android app)";

    public static final class Response {
        public int code;
        public String url;          // the address that answered
        public String stoppedAt;    // set when a redirect check stopped the chain
        public String contentType = "";
        public String location = "";
        public byte[] body = new byte[0];
    }

    /** Decides whether to stop at a redirect target instead of following it. */
    public interface RedirectCheck {
        boolean stopAt(String url);
    }

    public static final class StatusException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int code;

        public StatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    public static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        TooLargeException() {
            super("response too large");
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(Math.min(timeoutMs, 20000));
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        boolean ua = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
                if (e.getKey().equalsIgnoreCase("User-Agent")) ua = true;
            }
        }
        if (!ua) c.setRequestProperty("User-Agent", APP_UA);
        return c;
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /**
     * GET with up to 10 redirects. With follow=false the first redirect is returned as is.
     * maxBody limits how much of the answer is read (the rest is ignored).
     */
    public static Response get(String url, Map<String, String> headers, int timeoutMs, int maxBody,
                               boolean follow, RedirectCheck check, Cancel cancel) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= 10; hop++) {
            if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
            final HttpURLConnection c = open(cur, headers, timeoutMs);
            Runnable hook = new Runnable() {
                @Override
                public void run() {
                    c.disconnect();
                }
            };
            if (cancel != null) cancel.onCancel(hook);
            try {
                int code = c.getResponseCode();
                Response r = new Response();
                r.code = code;
                r.url = cur;
                String ct = c.getContentType();
                r.contentType = ct == null ? "" : ct;
                String loc = c.getHeaderField("Location");
                r.location = loc == null ? "" : loc;
                if (isRedirect(code) && loc != null && loc.length() > 0) {
                    String next = new URL(new URL(cur), loc).toString();
                    if (!follow) {
                        drain(c);
                        return r;
                    }
                    if (check != null && check.stopAt(next)) {
                        drain(c);
                        r.stoppedAt = next;
                        return r;
                    }
                    drain(c);
                    cur = next;
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) r.body = readUpTo(in, maxBody);
                return r;
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
                throw e;
            } finally {
                if (cancel != null) cancel.removeHook(hook);
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream in = c.getInputStream();
            if (in != null) {
                readUpTo(in, 64 * 1024);
                in.close();
            }
        } catch (IOException ignored) {
            // the redirect body does not matter
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        return readUpTo(in, 64 << 20);
    }

    static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        try {
            while (out.size() < max) {
                int n = in.read(buf, 0, Math.min(buf.length, max - out.size()));
                if (n < 0) break;
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /** Progress of a file download: done bytes and total (or -1). */
    public interface ByteProgress {
        void onBytes(long done, long total);
    }

    /** Downloads url into dest (following redirects) and returns its SHA-256 in hex. */
    public static String download(String url, File dest, int timeoutMs, long maxBytes, ByteProgress progress, Cancel cancel)
            throws IOException {
        String cur = url;
        for (int hop = 0; hop <= 10; hop++) {
            if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
            final HttpURLConnection c = open(cur, null, timeoutMs);
            Runnable hook = new Runnable() {
                @Override
                public void run() {
                    c.disconnect();
                }
            };
            if (cancel != null) cancel.onCancel(hook);
            try {
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (isRedirect(code) && loc != null) {
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) throw new StatusException(code);
                long total = c.getContentLengthLong();
                if (total > maxBytes) throw new TooLargeException();
                MessageDigest sha;
                try {
                    sha = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(dest);
                long done = 0;
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        done += n;
                        if (done > maxBytes) throw new TooLargeException();
                        if (progress != null) progress.onBytes(done, total);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                if (total > 0 && done != total) throw new IOException("download cut short");
                return hex(sha.digest());
            } catch (IOException e) {
                if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
                throw e;
            } finally {
                if (cancel != null) cancel.removeHook(hook);
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /**
     * Downloads a big file that must have exactly this size and SHA-256, trying the
     * addresses in turn. A cut connection continues where it stopped (mobile data drops
     * often), and the file is only put in place once it checks out.
     */
    public static void fetchVerified(List<String> urls, File dest, long size, String sha256, ByteProgress progress,
                                     Cancel cancel) throws IOException {
        File part = new File(dest.getPath() + ".part");
        IOException last = null;
        int attempts = Math.max(3, urls.size() * 3);
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
            if (part.length() > size) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            if (part.length() < size) {
                String url = urls.get(attempt % urls.size());
                try {
                    fetchRange(url, part, size, progress, cancel);
                } catch (StatusException e) {
                    last = e;
                    continue; // try the next address right away
                } catch (IOException e) {
                    if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
                    last = e;
                    sleep(Math.min(8000, 1000L << Math.min(3, attempt)), cancel);
                    continue;
                }
            }
            if (part.length() != size) continue;
            String got = sha256(part);
            if (!got.equalsIgnoreCase(sha256)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                last = new IOException("checksum mismatch for " + dest.getName());
                continue;
            }
            if (dest.exists() && !dest.delete()) throw new IOException("cannot replace " + dest);
            if (!part.renameTo(dest)) throw new IOException("cannot rename " + part);
            return;
        }
        throw last != null ? last : new IOException("download failed: " + dest.getName());
    }

    private static void sleep(long ms, Cancel cancel) throws InterruptedIOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
        }
    }

    /** Appends the rest of url to part (or starts over if the server ignores ranges). */
    private static void fetchRange(String url, File part, long size, ByteProgress progress, Cancel cancel) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= 10; hop++) {
            long have = part.exists() ? part.length() : 0;
            final HttpURLConnection c = open(cur, null, 30000);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            Runnable hook = new Runnable() {
                @Override
                public void run() {
                    c.disconnect();
                }
            };
            if (cancel != null) cancel.onCancel(hook);
            try {
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (isRedirect(code) && loc != null) {
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code == 416) return; // nothing left to send: the size check decides
                if (code != 200 && code != 206) throw new StatusException(code);
                boolean append = code == 206 && have > 0;
                if (!append) have = 0;
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(part, append);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    long done = have;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done > size) throw new TooLargeException();
                        if (progress != null) progress.onBytes(done, size);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                return;
            } finally {
                if (cancel != null) cancel.removeHook(hook);
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    public static String sha256(File f) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) sha.update(buf, 0, n);
        } finally {
            in.close();
        }
        return hex(sha.digest());
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append("0123456789abcdef".charAt((x >> 4) & 15)).append("0123456789abcdef".charAt(x & 15));
        return s.toString();
    }

    /** A rough version of Go's http.DetectContentType for pictures. */
    public static String sniffImage(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
                && b[8] == 'a' && b[9] == 'v' && b[10] == 'i' && b[11] == 'f') return "image/avif";
        return "application/octet-stream";
    }
}
