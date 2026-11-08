package com.alihelal.core;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Unpacks zip files, including symbolic links (the Python and ffmpeg bundles contain
 * them) and archives with something in front (a yt-dlp zipapp starts with "#!...").
 * java.util.zip cannot tell a symbolic link from a file.
 */
public final class Zip {
    private Zip() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Entry {
        public String name;
        int method, flags;
        long compSize, size, localOffset;
        int mode; // unix mode bits, 0 when unknown

        public boolean isDir() {
            return name.endsWith("/") || (mode & 0170000) == 0040000;
        }

        public boolean isSymlink() {
            return (mode & 0170000) == 0120000;
        }

        public long size() {
            return size;
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff)) | ((long) (b[o + 1] & 0xff)) << 8 | ((long) (b[o + 2] & 0xff)) << 16
                | ((long) (b[o + 3] & 0xff)) << 24;
    }

    /** Reads the list of entries from the central directory. */
    public static List<Entry> entries(RandomAccessFile f) throws IOException {
        long len = f.length();
        int tail = (int) Math.min(len, 65557);
        byte[] t = new byte[tail];
        f.seek(len - tail);
        f.readFully(t);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) throw new IOException("not a zip file");
        int count = u16(t, eocd + 10);
        long cdSize = u32(t, eocd + 12);
        long cdOffset = u32(t, eocd + 16);
        if (count == 0xffff || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) throw new IOException("zip64 is not supported");
        long eocdPos = len - tail + eocd;
        // Bytes in front of the archive (a "#!" line) shift every offset.
        long delta = eocdPos - cdSize - cdOffset;
        if (delta < 0) throw new IOException("bad zip directory");
        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOffset + delta);
        f.readFully(cd);
        List<Entry> out = new ArrayList<Entry>(count);
        int p = 0;
        for (int i = 0; i < count; i++) {
            if (p + 46 > cd.length || u32(cd, p) != 0x02014b50L) throw new IOException("bad zip entry");
            Entry e = new Entry();
            int made = u16(cd, p + 4) >> 8;
            e.flags = u16(cd, p + 8);
            e.method = u16(cd, p + 10);
            e.compSize = u32(cd, p + 20);
            e.size = u32(cd, p + 24);
            int nameLen = u16(cd, p + 28), extraLen = u16(cd, p + 30), commentLen = u16(cd, p + 32);
            long ext = u32(cd, p + 38);
            e.localOffset = u32(cd, p + 42) + delta;
            e.name = new String(cd, p + 46, nameLen, UTF8);
            if (made == 3) e.mode = (int) (ext >>> 16); // made on Unix
            out.add(e);
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    /** Opens the (uncompressed) data of an entry. */
    public static InputStream open(final RandomAccessFile f, Entry e) throws IOException {
        byte[] lh = new byte[30];
        f.seek(e.localOffset);
        f.readFully(lh);
        if (u32(lh, 0) != 0x04034b50L) throw new IOException("bad local header for " + e.name);
        long dataStart = e.localOffset + 30 + u16(lh, 26) + u16(lh, 28);
        if (e.method == 0) return new Bounded(f, dataStart, e.compSize, false);
        if (e.method == 8) {
            final Inflater inf = new Inflater(true);
            return new InflaterInputStream(new Bounded(f, dataStart, e.compSize, true), inf, 65536) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    super.close();
                    inf.end(); // free the native memory now instead of at garbage collection
                }
            };
        }
        throw new IOException("unsupported compression for " + e.name);
    }

    /** Reads part of a RandomAccessFile as a stream (not thread-safe; one stream at a time). */
    private static final class Bounded extends InputStream {
        private final RandomAccessFile f;
        private long pos;
        private long left;
        private boolean dummySent;

        Bounded(RandomAccessFile f, long start, long len, boolean dummyAtEnd) {
            this.f = f;
            this.pos = start;
            this.left = len;
            this.dummySent = !dummyAtEnd;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                // Raw deflate may need one extra "dummy" byte at the very end (like java.util.zip does).
                if (!dummySent && len > 0) {
                    dummySent = true;
                    b[off] = 0;
                    return 1;
                }
                return -1;
            }
            int n = (int) Math.min(len, left);
            f.seek(pos);
            int r = f.read(b, off, n);
            if (r < 0) throw new EOFException();
            pos += r;
            left -= r;
            return r;
        }
    }

    public interface Filter {
        /** Returns the path to unpack the entry to (relative), or null to skip it. */
        String map(String name);
    }

    /**
     * Unpacks zipFile into dest. Every path is checked to stay inside dest. Progress
     * reports uncompressed bytes written.
     */
    public static void extract(File zipFile, File dest, Filter filter, Host host, Http.ByteProgress progress,
                               Cancel cancel) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        RandomAccessFile f = new RandomAccessFile(zipFile, "r");
        try {
            List<Entry> list = entries(f);
            long total = 0;
            for (Entry e : list) total += e.size;
            long done = 0;
            byte[] buf = new byte[65536];
            for (Entry e : list) {
                if (cancel != null && cancel.isCancelled()) throw new InterruptedIOException("cancelled");
                String name = filter == null ? e.name : filter.map(e.name);
                if (name == null || name.length() == 0) continue;
                File out = new File(dest, name);
                String canon = out.getCanonicalPath();
                if (!canon.startsWith(root) && !(canon + File.separator).equals(root)) {
                    throw new IOException("entry outside the target folder: " + e.name);
                }
                if (e.isDir()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create " + out);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                if (e.isSymlink()) {
                    InputStream in = open(f, e);
                    String target = new String(Http.readUpTo(in, 4096), UTF8);
                    if (out.exists() || isLink(out)) {
                        if (!out.delete()) throw new IOException("cannot replace " + out);
                    }
                    host.symlink(target, out);
                    done += e.size;
                    continue;
                }
                InputStream in = open(f, e);
                OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 65536);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (progress != null) progress.onBytes(done, total);
                    }
                } finally {
                    os.close();
                    in.close();
                }
                if ((e.mode & 0111) != 0) {
                    //noinspection ResultOfMethodCallIgnored
                    out.setExecutable(true, true);
                }
            }
            if (progress != null) progress.onBytes(total, total);
        } finally {
            f.close();
        }
    }

    static boolean isLink(File f) {
        try {
            File canon = f.getParentFile() == null ? f : new File(f.getParentFile().getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads one small entry of a zip as text, or null. */
    public static String readText(File zipFile, String name, int max) {
        try {
            RandomAccessFile f = new RandomAccessFile(zipFile, "r");
            try {
                for (Entry e : entries(f)) {
                    if (e.name.equals(name)) return new String(Http.readUpTo(open(f, e), max), UTF8);
                }
            } finally {
                f.close();
            }
        } catch (IOException ignored) {
            // not there
        }
        return null;
    }

    /** Deletes a file or a whole folder (without following symbolic links). */
    public static boolean deleteTree(File f) {
        if (f == null) return true;
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        return f.delete() || !f.exists();
    }
}
