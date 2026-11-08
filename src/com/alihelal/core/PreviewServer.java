package com.alihelal.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Plays what HELINK shows before saving it: the part of a video being cut, and WhatsApp
 * statuses. The page's player cannot send the headers a site wants, nor read the phone's
 * files, so it asks this small server on the phone itself (127.0.0.1, a port of its own),
 * which fetches the bytes with the right headers (or reads the file) and answers the
 * player's range requests, so it can jump to any moment.
 *
 * Every source has an address of its own with a long random name: other apps on the phone
 * cannot guess it.
 */
public final class PreviewServer {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int MAX_SOURCES = 400;

    /** Something that can be played: opened from a byte on (range: the player's Range header, or null). */
    public interface Source {
        Opened open(String range) throws IOException;
    }

    /** An opened source: what to answer the player. */
    public static final class Opened {
        public int status = 200;            // 200, or 206 for a part
        public long length = -1;            // bytes sent, -1 if unknown
        public String contentRange = "";    // for 206
        public String type = "application/octet-stream";
        public InputStream in;
        public Runnable closer;             // extra cleanup, if any
    }

    private static final class Entry {
        final String group;
        final Source source;

        Entry(String group, Source source) {
            this.group = group;
            this.source = source;
        }
    }

    private final Map<String, Entry> sources = new LinkedHashMap<String, Entry>();
    private final SecureRandom random = new SecureRandom();
    private final Host host;
    private ServerSocket socket;
    private ExecutorService pool;

    public PreviewServer(Host host) {
        this.host = host;
    }

    /** The address of a source (the server starts the first time). group: dropped together. */
    public synchronized String add(String group, Source s) throws IOException {
        start();
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = Http.hex(b);
        sources.put(token, new Entry(group, s));
        while (sources.size() > MAX_SOURCES) {
            Iterator<String> it = sources.keySet().iterator();
            it.next();
            it.remove();
        }
        return "http://127.0.0.1:" + socket.getLocalPort() + "/m/" + token;
    }

    /** Forgets a group's sources (a new video to cut, the statuses listed again). */
    public synchronized void drop(String group) {
        Iterator<Map.Entry<String, Entry>> it = sources.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().group.equals(group)) it.remove();
    }

    private synchronized Source find(String token) {
        Entry e = sources.get(token);
        return e == null ? null : e.source;
    }

    private void start() throws IOException {
        if (socket != null) return;
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "preview");
                t.setDaemon(true);
                return t;
            }
        });
        final ServerSocket s = socket;
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        final Socket c = s.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                serve(c);
                            }
                        });
                    } catch (IOException e) {
                        if (!s.isClosed()) host.log("preview accept: " + e);
                    }
                }
            }
        }, "preview accept");
        accept.setDaemon(true);
        accept.start();
    }

    public synchronized void stop() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
        socket = null;
        pool.shutdownNow();
        sources.clear();
    }

    // ------------------------------------------------------------------ one request

    private void serve(Socket c) {
        try {
            c.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String range = null;
            while (true) {
                String h = readLine(in);
                if (h == null || h.length() == 0) break;
                int colon = h.indexOf(':');
                if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("range")) range = h.substring(colon + 1).trim();
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            boolean head = method.equals("HEAD");
            if (!head && !method.equals("GET")) {
                simple(out, 405, "Method Not Allowed");
                return;
            }
            Source src = path.startsWith("/m/") ? find(path.substring(3).replaceAll("[?#].*$", "")) : null;
            if (src == null) {
                simple(out, 404, "Not Found");
                return;
            }
            Opened o;
            try {
                o = src.open(range);
            } catch (IOException e) {
                host.log("preview open: " + e);
                simple(out, 502, "Bad Gateway");
                return;
            }
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP/1.1 ").append(o.status).append(o.status == 206 ? " Partial Content" : o.status == 416 ? " Range Not Satisfiable" : " OK").append("\r\n");
                sb.append("Content-Type: ").append(o.type).append("\r\n");
                if (o.length >= 0) sb.append("Content-Length: ").append(o.length).append("\r\n");
                if (o.contentRange.length() > 0) sb.append("Content-Range: ").append(o.contentRange).append("\r\n");
                sb.append("Accept-Ranges: bytes\r\n");
                sb.append("Cache-Control: no-store\r\n");
                sb.append("Connection: close\r\n\r\n");
                out.write(sb.toString().getBytes(ASCII));
                if (!head && o.in != null) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = o.in.read(buf)) > 0) out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                close(o);
            }
        } catch (SocketTimeoutException ignored) {
            // the player went away
        } catch (IOException ignored) {
            // the player stops reading as soon as it has enough: normal
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    private static void close(Opened o) {
        if (o.in != null) {
            try {
                o.in.close();
            } catch (IOException ignored) {
                // closed
            }
        }
        if (o.closer != null) o.closer.run();
    }

    private static void simple(OutputStream out, int code, String msg) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + msg + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(ASCII));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        if (b < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    // ------------------------------------------------------------------ sources

    /** A {from, to} byte range from a Range header ("bytes=100-", "bytes=100-199"); to=-1 open-ended; null if none. */
    public static long[] parseRange(String range) {
        if (range == null) return null;
        String r = range.trim().toLowerCase(Locale.ROOT);
        if (!r.startsWith("bytes=") || r.contains(",")) return null;
        String[] p = r.substring(6).split("-", -1);
        if (p.length != 2) return null;
        try {
            if (p[0].trim().length() == 0) return null; // "the last n bytes": not used by players
            long from = Long.parseLong(p[0].trim());
            long to = p[1].trim().length() == 0 ? -1 : Long.parseLong(p[1].trim());
            if (from < 0 || (to >= 0 && to < from)) return null;
            return new long[]{from, to};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A file on a site: fetched with the headers the site wants (and its cookies, if any). */
    public static Source web(final String url, final Map<String, String> headers, final String type) {
        return new Source() {
            @Override
            public Opened open(String range) throws IOException {
                String cur = url;
                for (int hop = 0; hop < 8; hop++) {
                    HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    c.setUseCaches(false);
                    boolean ua = false;
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                        if (e.getKey().equalsIgnoreCase("User-Agent")) ua = true;
                    }
                    if (!ua) c.setRequestProperty("User-Agent", Http.APP_UA);
                    c.setRequestProperty("Accept-Encoding", "identity");
                    if (range != null) c.setRequestProperty("Range", range);
                    int code = c.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) throw new IOException("redirect without a place");
                        cur = new URL(new URL(cur), loc).toString();
                        continue;
                    }
                    if (code != 200 && code != 206 && code != 416) {
                        c.disconnect();
                        throw new IOException("HTTP " + code);
                    }
                    Opened o = new Opened();
                    o.status = code;
                    String ct = c.getContentType();
                    o.type = type != null && type.length() > 0 ? type : ct != null ? ct : o.type;
                    o.length = c.getContentLengthLong();
                    String cr = c.getHeaderField("Content-Range");
                    if (cr != null) o.contentRange = cr;
                    o.in = code == 416 ? null : c.getInputStream();
                    final HttpURLConnection conn = c;
                    o.closer = new Runnable() {
                        @Override
                        public void run() {
                            conn.disconnect();
                        }
                    };
                    return o;
                }
                throw new IOException("too many redirects");
            }
        };
    }

    /** Bytes the app has (a picture made here): served whole or in parts. */
    public static Source bytes(final byte[] data, final String type) {
        return new Source() {
            @Override
            public Opened open(String range) {
                return part(new java.io.ByteArrayInputStream(data), data.length, range, type);
            }
        };
    }

    /**
     * The answer for a stream of known length, positioned at its start: the whole of it, or
     * the part the player asked for (the stream is skipped to it).
     */
    public static Opened part(InputStream in, long size, String range, String type) {
        Opened o = new Opened();
        o.type = type;
        long[] r = parseRange(range);
        if (r == null) {
            o.in = in;
            o.length = size;
            return o;
        }
        if (r[0] >= size) {
            o.status = 416;
            o.length = 0;
            o.contentRange = "bytes */" + size;
            try {
                in.close();
            } catch (IOException ignored) {
                // closed
            }
            return o;
        }
        long to = r[1] < 0 || r[1] >= size ? size - 1 : r[1];
        try {
            long skip = r[0];
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
        } catch (IOException e) {
            // read from where it is: the player checks the bytes it gets
        }
        o.status = 206;
        o.length = to - r[0] + 1;
        o.contentRange = "bytes " + r[0] + "-" + to + "/" + size;
        o.in = new Limited(in, o.length);
        return o;
    }

    /** At most n bytes of a stream. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private long left;

        Limited(InputStream in, long n) {
            this.in = in;
            this.left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
