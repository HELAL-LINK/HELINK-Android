package com.alihelal.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import com.alihelal.core.Http;
import com.alihelal.core.I18n;
import com.alihelal.core.Json;
import com.alihelal.core.PreviewServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The WhatsApp statuses the user has viewed. WhatsApp keeps a copy of each in a hidden folder
 * (Android/media/com.whatsapp/WhatsApp/Media/.Statuses, and the same for WhatsApp Business)
 * for a day. Android lets HELINK read that folder once the user allows it in the folder
 * picker (opened right at it); the permission stays. The statuses are shown through the
 * engine's preview server and copied, when asked, to Download/HELINK/WhatsApp.
 */
final class WaStatuses {
    static final String SUBDIR = "WhatsApp";
    private static final String AUTHORITY = "com.android.externalstorage.documents";

    static final class AppDir {
        final String id, pkg, path;

        AppDir(String id, String pkg, String path) {
            this.id = id;
            this.pkg = pkg;
            this.path = path;
        }
    }

    static final AppDir[] APPS = {
            new AppDir("wa", "com.whatsapp", "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
            new AppDir("w4b", "com.whatsapp.w4b", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
    };

    /** One status, as listed last. */
    static final class Item {
        String id = "", app = "", name = "", mime = "", kind = "";
        Uri uri;
        long size, time;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<String, Item> listed = new HashMap<String, Item>();

    WaStatuses(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("wa_statuses", Context.MODE_PRIVATE);
    }

    static AppDir app(String id) {
        for (AppDir a : APPS) if (a.id.equals(id)) return a;
        return null;
    }

    boolean installed(AppDir a) {
        try {
            ctx.getPackageManager().getPackageInfo(a.pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The folder Android lets HELINK read for this app, or null. */
    Uri granted(AppDir a) {
        String s = prefs.getString("tree_" + a.id, null);
        if (s == null) return null;
        Uri tree = Uri.parse(s);
        for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) return tree;
        }
        return null;
    }

    /** Android's folder picker, opened at the app's statuses folder. */
    Intent askIntent(AppDir a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, "primary:" + a.path));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    /** The folder the user picked: "granted" when it is the statuses folder, otherwise "wrongFolder". */
    String accept(AppDir a, Uri tree) {
        String doc;
        try {
            doc = DocumentsContract.getTreeDocumentId(tree);
        } catch (RuntimeException e) {
            return "wrongFolder";
        }
        String want = "primary:" + a.path;
        boolean ok = doc != null && (doc.equalsIgnoreCase(want)
                || (doc.toLowerCase(Locale.ROOT).endsWith("/.statuses") && doc.contains(a.pkg + "/")));
        if (!ok) return "wrongFolder";
        try {
            ctx.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            Logs.note("statuses permission: " + e);
            return "wrongFolder";
        }
        prefs.edit().putString("tree_" + a.id, tree.toString()).apply();
        return "granted";
    }

    private static String kindOf(String name, String mime) {
        String m = mime == null ? "" : mime;
        String n = name.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) return "image";
        if (m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".webm")) return "video";
        return "";
    }

    private static String mimeOf(String kind, String name, String mime) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) return mime;
        String n = name.toLowerCase(Locale.ROOT);
        if (kind.equals("video")) return n.endsWith(".webm") ? "video/webm" : n.endsWith(".3gp") ? "video/3gpp" : "video/mp4";
        return n.endsWith(".png") ? "image/png" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
    }

    /** The statuses of the apps allowed, newest first, with addresses on the preview server. */
    synchronized Map<String, Object> list(PreviewServer ps) {
        List<Object> apps = new ArrayList<Object>();
        List<Item> found = new ArrayList<Item>();
        for (AppDir a : APPS) {
            Uri tree = granted(a);
            apps.add(Json.obj("id", a.id, "installed", installed(a), "granted", tree != null));
            if (tree != null) found.addAll(read(a, tree));
        }
        Collections.sort(found, new Comparator<Item>() {
            @Override
            public int compare(Item x, Item y) {
                return Long.compare(y.time, x.time);
            }
        });
        ps.drop("wa");
        listed.clear();
        List<Object> items = new ArrayList<Object>();
        for (Item it : found) {
            if (items.size() >= 300) break;
            listed.put(it.id, it);
            Map<String, Object> m = Json.obj("id", it.id, "app", it.app, "kind", it.kind, "name", it.name);
            m.put("size", it.size);
            m.put("time", it.time);
            m.put("saved", savedUri(it) != null);
            try {
                m.put("src", ps.add("wa", file(it)));
                m.put("thumb", ps.add("wa", thumb(it)));
            } catch (IOException e) {
                Logs.note("statuses preview: " + e);
                continue;
            }
            items.add(m);
        }
        Map<String, Object> r = Json.obj("apps", apps, "items", items);
        r.put("folderLabel", folderLabel());
        return r;
    }

    static String folderLabel() {
        return I18n.t("التنزيلات ← HELINK ← " + SUBDIR, "Download › HELINK › " + SUBDIR);
    }

    private List<Item> read(AppDir a, Uri tree) {
        List<Item> out = new ArrayList<Item>();
        Cursor c = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            c = ctx.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
            while (c != null && c.moveToNext()) {
                String doc = c.getString(0), name = c.isNull(1) ? "" : c.getString(1), mime = c.isNull(2) ? "" : c.getString(2);
                if (name.startsWith(".") || mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) continue;
                String kind = kindOf(name, mime);
                if (kind.isEmpty()) continue;
                Item it = new Item();
                it.app = a.id;
                it.name = name;
                it.kind = kind;
                it.mime = mimeOf(kind, name, mime);
                it.size = c.isNull(3) ? 0 : c.getLong(3);
                it.time = c.isNull(4) ? 0 : c.getLong(4);
                it.uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc);
                it.id = a.id + "|" + name;
                out.add(it);
            }
        } catch (RuntimeException e) {
            // The folder is gone (WhatsApp moved or removed it): nothing to show from it.
            Logs.note("statuses " + a.id + ": " + e);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ played and pictured

    /** The status itself, read in parts as the page's player asks. */
    private PreviewServer.Source file(final Item it) {
        return new PreviewServer.Source() {
            @Override
            public PreviewServer.Opened open(String range) throws IOException {
                final ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(it.uri, "r");
                if (pfd == null) throw new IOException("cannot open " + it.name);
                long size = pfd.getStatSize();
                if (size < 0) size = it.size;
                PreviewServer.Opened o = PreviewServer.part(new FileInputStream(pfd.getFileDescriptor()), size, range, it.mime);
                o.closer = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pfd.close();
                        } catch (IOException ignored) {
                            // closed
                        }
                    }
                };
                return o;
            }
        };
    }

    /** A small picture of the status for the grid (made once, kept in the cache). */
    private PreviewServer.Source thumb(final Item it) {
        return new PreviewServer.Source() {
            @Override
            public PreviewServer.Opened open(String range) throws IOException {
                byte[] jpeg = thumbBytes(it);
                return PreviewServer.part(new java.io.ByteArrayInputStream(jpeg), jpeg.length, range, "image/jpeg");
            }
        };
    }

    private byte[] thumbBytes(Item it) throws IOException {
        File dir = new File(ctx.getCacheDir(), "wa-thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, key(it.uri + "|" + it.size + "|" + it.time) + ".jpg");
        if (f.length() > 0) return readAll(f);
        Bitmap b = null;
        try {
            if (it.kind.equals("video")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(ctx, it.uri);
                    b = Build.VERSION.SDK_INT >= 27
                            ? r.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)
                            : r.getFrameAtTime(500_000);
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                        // released
                    }
                }
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                decode(it.uri, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 360 && o.outHeight / (sample * 2) >= 360) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                b = decode(it.uri, o2);
            }
        } catch (RuntimeException e) {
            throw new IOException("picture of " + it.name + ": " + e, e);
        }
        if (b == null) throw new IOException("no picture for " + it.name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        b.recycle();
        byte[] jpeg = out.toByteArray();
        File tmp = new File(dir, f.getName() + ".tmp");
        OutputStream w = new FileOutputStream(tmp);
        try {
            w.write(jpeg);
        } finally {
            w.close();
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
        return jpeg;
    }

    private Bitmap decode(Uri u, BitmapFactory.Options o) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("cannot read " + u);
        try {
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return Http.readAll(in);
        } finally {
            in.close();
        }
    }

    private static String key(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            return Http.hex(d);
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    // ------------------------------------------------------------------ saving

    /** Where a status was saved before, if that file is still there. */
    private String savedUri(Item it) {
        String u = prefs.getString("saved_" + it.id, null);
        if (u == null) return null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse(u), new String[]{MediaStore.MediaColumns._ID}, null, null, null);
            if (c != null && c.moveToFirst()) return u;
        } catch (RuntimeException ignored) {
            // gone
        } finally {
            if (c != null) c.close();
        }
        prefs.edit().remove("saved_" + it.id).apply();
        return null;
    }

    /** Copies the statuses (ids from the latest list) to Download/HELINK/WhatsApp; returns the ids saved. */
    List<String> save(List<String> ids) {
        List<String> done = new ArrayList<String>();
        for (String id : ids) {
            Item it;
            synchronized (this) {
                it = listed.get(id);
            }
            if (it == null) continue;
            if (savedUri(it) != null) {
                done.add(id);
                continue;
            }
            try {
                String uri = copy(it);
                prefs.edit().putString("saved_" + it.id, uri).apply();
                done.add(id);
            } catch (IOException e) {
                Logs.note("save status " + it.name + ": " + e);
            }
        }
        return done;
    }

    private String copy(Item it) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + AndroidHost.FOLDER + "/" + SUBDIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item;
        try {
            item = cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        } catch (RuntimeException e) {
            throw new IOException("media store: " + e, e);
        }
        if (item == null) throw new IOException("media store refused " + it.name);
        boolean ok = false;
        try {
            InputStream in = cr.openInputStream(it.uri);
            OutputStream out = cr.openOutputStream(item, "w");
            if (in == null || out == null) throw new IOException("cannot copy " + it.name);
            try {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
            ContentValues pub = new ContentValues();
            pub.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, pub, null, null);
            ok = true;
        } catch (RuntimeException e) {
            throw new IOException("copy: " + e, e);
        } finally {
            if (!ok) {
                try {
                    cr.delete(item, null, null);
                } catch (RuntimeException ignored) {
                    // nothing more to do
                }
            }
        }
        return item.toString();
    }
}
