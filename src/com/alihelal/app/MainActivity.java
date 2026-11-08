package com.alihelal.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.alihelal.core.Engine;
import com.alihelal.core.Host;
import com.alihelal.core.I18n;
import com.alihelal.core.Json;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only screen: a web page (the same design as the Windows app) talking to the engine
 * through a small bridge. Links shared from other apps (YouTube's "Share" button) land here,
 * and so do sound and video files shared to have their music removed.
 */
public final class MainActivity extends Activity implements App.Dispatcher.Sink {
    /** Opens the downloads list (from the notification). */
    static final String ACTION_DOWNLOADS = "com.alihelal.app.OPEN_DOWNLOADS";
    private static final String PHONE = "01500025887";
    private static final String WHATSAPP = "https://wa.me/201500025887";
    private static final int PICK_FILES = 7;
    private static final int PICK_STATUSES = 8;
    /** WhatsApp's statuses (WaStatuses), and the app whose folder the picker is open for. */
    private WaStatuses statuses;
    private volatile String askingFor;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private volatile boolean pageReady;
    private volatile String screen = "boot";
    private volatile String pendingShared;
    private volatile String pendingTab;
    // Files shared or picked for music removal before the page was listening: what was added.
    private volatile String pendingFiles;
    private boolean dark;
    private boolean started;
    private boolean askedNotifications;
    // Space taken by the status bar, navigation bar and keyboard, in CSS pixels.
    private volatile int insetTop, insetBottom, insetLeft, insetRight;
    // While the screen shows: the internet coming back is a moment to look for HELINK's update.
    private ConnectivityManager.NetworkCallback netWatch;
    /** Whether HELINK's screen is in front (Android's install window can be shown over it now). */
    static volatile boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Window w = getWindow();
        // Draw behind the system bars; the page pads itself (see applyInsets).
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(dark ? 0xFF121212 : 0xFFF4F4F4);
        web = new WebView(this);
        web.setBackgroundColor(dark ? 0xFF121212 : 0xFFF4F4F4);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            // White status bar icons (the header is black); navigation icons follow the page.
            int nav = dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(nav, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                float d = getResources().getDisplayMetrics().density;
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                insetTop = Math.round(bars.top / d);
                insetBottom = Math.round(Math.max(bars.bottom, ime.bottom) / d);
                insetLeft = Math.round(bars.left / d);
                insetRight = Math.round(bars.right / d);
                applyInsets();
                return WindowInsets.CONSUMED;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false); // the page itself comes from the app's assets
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Respect big fonts, but not so big that the buttons stop fitting.
        float scale = getResources().getConfiguration().fontScale;
        s.setTextZoom(Math.max(90, Math.min(125, Math.round(scale * 100))));
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "HelalAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the page never navigates away
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                applyInsets();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page's renderer crashed (low memory): start the screen again.
                Logs.note("web renderer gone, crash=" + detail.didCrash());
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                view.destroy();
                web = null;
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Logs.note("page: " + m.message() + " (" + m.lineNumber() + ")");
                }
                return true;
            }
        });
        handleIntent(getIntent());
        App.engine(this);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** A link shared from another app ("Share" → HELINK), or the notification opening the list. */
    private void handleIntent(Intent i) {
        if (i != null && ACTION_DOWNLOADS.equals(i.getAction())) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            if (pageReady) js("window.nzOpen && window.nzOpen('downloads')");
            else pendingTab = "downloads";
            return;
        }
        if (i == null) return;
        if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> streams = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            setIntent(new Intent(Intent.ACTION_MAIN));
            if (streams != null && !streams.isEmpty()) addFiles(streams, "shared");
            return;
        }
        if (!Intent.ACTION_SEND.equals(i.getAction())) return;
        // A sound or video file: its music is removed.
        Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
        String type = i.getType() == null ? "" : i.getType();
        if (stream != null && (type.startsWith("audio/") || type.startsWith("video/") || i.getStringExtra(Intent.EXTRA_TEXT) == null)) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            List<Uri> one = new ArrayList<Uri>();
            one.add(stream);
            addFiles(one, "shared");
            return;
        }
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = t == null ? "" : t.toString();
        String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject != null && !text.contains("http")) text = subject + " " + text;
        // Handled once, even if Android shows this screen again later.
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (text.trim().isEmpty()) return;
        if (pageReady) js("window.nzShared && window.nzShared(" + Json.quote(text) + ")");
        else pendingShared = text;
    }

    // ------------------------------------------------------------------ files for music removal

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_STATUSES) {
            WaStatuses.AppDir a = WaStatuses.app(askingFor == null ? "" : askingFor);
            askingFor = null;
            String op = "cancelled";
            if (a != null && resultCode == RESULT_OK && data != null && data.getData() != null) op = wa().accept(a, data.getData());
            js("window.nzEvent && window.nzEvent('wa', " + Json.write(Json.obj("op", op, "app", a == null ? "" : a.id)) + ")");
            return;
        }
        if (requestCode != PICK_FILES) return;
        List<Uri> uris = new ArrayList<Uri>();
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) {
                    Uri u = clip.getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            js("window.nzFilesAdded && window.nzFilesAdded(null)");
            return;
        }
        for (Uri u : uris) {
            try {
                // So that a removal paused today can read its file again tomorrow.
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // not offered for this file: it is copied in right away anyway
            }
        }
        addFiles(uris, "picked");
    }

    /** What the system says about a file: its name, size and type. */
    private Host.Content describe(Uri u) {
        Host.Content f = new Host.Content();
        f.uri = u.toString();
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) f.name = c.getString(0);
                if (!c.isNull(1)) f.size = c.getLong(1);
            }
        } catch (RuntimeException e) {
            Logs.note("describe " + u + ": " + e);
        } finally {
            if (c != null) c.close();
        }
        try {
            String m = getContentResolver().getType(u);
            if (m != null) f.mime = m;
        } catch (RuntimeException ignored) {
            // by its name, then
        }
        if (f.name.isEmpty()) {
            String last = u.getLastPathSegment();
            f.name = last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
        }
        return f;
    }

    /** Adds the files to the downloads list, their music to be removed; the page is told what was added. */
    private void addFiles(final List<Uri> uris, final String how) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                List<Host.Content> files = new ArrayList<Host.Content>();
                for (Uri u : uris) files.add(describe(u));
                Engine e = App.engine(MainActivity.this);
                Map<String, Object> r = e.addFiles(files);
                r.put("how", how);
                if (e.downloading()) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            DownloadService.start(MainActivity.this);
                            askNotifications();
                        }
                    });
                }
                String json = Json.write(r);
                if (pageReady) js("window.nzFilesAdded && window.nzFilesAdded(" + json + ")");
                else pendingFiles = json;
            }
        }, "add files");
        t.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.dispatcher(this).attach(this);
        // Coming back after a while: the download may have finished meanwhile.
        if (started && pageReady) js("window.nzResync && window.nzResync()");
        started = true;
        // A new version of HELINK: looked for when the screen shows (at most every few hours),
        // and when the internet comes back.
        App.engine(this).updates().checkIfDue();
        watchNetwork(true);
    }

    @Override
    protected void onStop() {
        watchNetwork(false);
        App.dispatcher(this).detach(this);
        super.onStop();
    }

    private void watchNetwork(boolean on) {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        try {
            if (on && netWatch == null) {
                netWatch = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        App.engine(MainActivity.this).updates().checkIfDue();
                    }
                };
                cm.registerDefaultNetworkCallback(netWatch);
            } else if (!on && netWatch != null) {
                cm.unregisterNetworkCallback(netWatch);
                netWatch = null;
            }
        } catch (RuntimeException e) {
            Logs.note("network watch: " + e);
            netWatch = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (web != null) web.onResume();
        // Back from Settings (allowed to install updates), or Android's install window came while
        // HELINK was not in front (UpdateReceiver keeps it for now).
        if (pageReady) js("window.nzUpdResume && window.nzUpdResume()");
        final Intent confirm = UpdateInstaller.pendingConfirm;
        if (confirm != null) {
            UpdateInstaller.pendingConfirm = null;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(confirm);
                    } catch (RuntimeException e) {
                        Logs.note("install confirmation again: " + e);
                    }
                }
            }, 400);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        App.dispatcher(this).detach(this);
        if (web != null) {
            web.removeJavascriptInterface("HelalAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        String s = screen;
        if (s.equals("choose") || s.equals("course") || s.equals("sheet") || s.equals("error") || s.equals("loading")
                || s.equals("downloads") || s.equals("about")) {
            js("window.nzBack && window.nzBack()");
            return;
        }
        // Home or setting up: leave the app, the downloads go on.
        moveTaskToBack(true);
    }

    private void applyInsets() {
        final String code = "window.nzInsets && window.nzInsets(" + insetTop + "," + insetRight + "," + insetBottom + ","
                + insetLeft + ")";
        if (pageReady) js(code);
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    @Override
    public void deliver(String name, String json) {
        if (pageReady) js("window.nzEvent && window.nzEvent(" + Json.quote(name) + "," + json + ")");
    }

    private void askNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return;
        askedNotifications = true;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void toast(String msg) {
        js("window.nzToast && window.nzToast(" + Json.quote(msg) + ")");
    }

    private synchronized WaStatuses wa() {
        if (statuses == null) statuses = new WaStatuses(this);
        return statuses;
    }

    /** What the page can ask for. Runs on a background thread of the web view. */
    final class Bridge {
        private Engine engine() {
            return App.engine(MainActivity.this);
        }

        @JavascriptInterface
        public String env() {
            Map<String, Object> m = Json.obj("theme", dark ? "dark" : "light", "lang", engine().lang());
            m.put("insets", java.util.Arrays.asList(insetTop, insetRight, insetBottom, insetLeft));
            m.put("sdk", Build.VERSION.SDK_INT);
            return Json.write(m);
        }

        @JavascriptInterface
        public String init() {
            // The page's script is running and listening: events can be sent from now on
            // (onPageFinished comes later, and events sent before it would be lost).
            pageReady = true;
            Map<String, Object> r = engine().init();
            String shared = pendingShared;
            pendingShared = null;
            if (shared != null) r.put("shared", shared);
            String tab = pendingTab;
            pendingTab = null;
            if (tab != null) r.put("tab", tab);
            String files = pendingFiles;
            pendingFiles = null;
            if (files != null) {
                try {
                    r.put("filesAdded", Json.parse(files));
                } catch (Json.ParseError ignored) {
                    // never: it was written above
                }
            }
            return Json.write(r);
        }

        @JavascriptInterface
        public void setup() {
            engine().startSetup();
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                ClipData d = cm == null ? null : cm.getPrimaryClip();
                if (d == null || d.getItemCount() == 0) return "";
                CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (RuntimeException e) {
                return "";
            }
        }

        @JavascriptInterface
        public int fetch(String text) {
            return engine().fetch(text);
        }

        @JavascriptInterface
        public void cancelFetch(boolean forget) {
            engine().cancelFetch(forget);
        }

        /** Switches the app's language ("ar" or "en"): the engine, the notifications. */
        @JavascriptInterface
        public void setLang(String lang) {
            engine().setLang(lang);
            Notifications.createChannels(MainActivity.this);
            DownloadService.refresh(MainActivity.this);
        }

        /**
         * Adds a download to the list; returns {"id": …} or {"error": …}. noMusic: for an MP3,
         * remove the music after downloading.
         */
        @JavascriptInterface
        public String download(int item, String key, boolean noMusic) {
            Engine e = engine();
            Map<String, Object> r = e.download(item, key, noMusic);
            keepRunning(e);
            return Json.write(r);
        }

        /**
         * Adds the videos ticked of the course on screen (their places in the list, as a JSON
         * array) to the downloads, all in one quality. Returns {"ids", "added", "skipped", "folder"} or {"error"}.
         */
        @JavascriptInterface
        public String downloadList(int seq, String picks, String key, boolean noMusic) {
            List<Integer> p = new ArrayList<Integer>();
            try {
                Object v = Json.parse(picks == null ? "[]" : picks);
                if (v instanceof List) {
                    for (Object o : (List<?>) v) if (o instanceof Number) p.add(((Number) o).intValue());
                }
            } catch (Json.ParseError ignored) {
                // nothing ticked, then
            }
            Engine e = engine();
            Map<String, Object> r = e.downloadList(seq, p, key, noMusic);
            keepRunning(e);
            return Json.write(r);
        }

        // ---- a part of a video (Engine.clipFetch, clipDownload)

        @JavascriptInterface
        public int clipFetch(String text) {
            return engine().clipFetch(text);
        }

        @JavascriptInterface
        public void cancelClip(boolean forget) {
            engine().cancelClip(forget);
        }

        /** Adds the part from start to end (seconds); returns {"id": …} or {"error": …}. */
        @JavascriptInterface
        public String clipDownload(int seq, int item, String key, double start, double end, boolean noMusic) {
            Engine e = engine();
            Map<String, Object> r = e.clipDownload(seq, item, key, start, end, noMusic);
            keepRunning(e);
            return Json.write(r);
        }

        // ---- WhatsApp statuses (WaStatuses)

        @JavascriptInterface
        public String waList() {
            return Json.write(wa().list(engine().previews()));
        }

        /** Opens Android's folder picker at the statuses folder of "wa" or "w4b". */
        @JavascriptInterface
        public void waAsk(final String app) {
            final WaStatuses.AppDir a = WaStatuses.app(app);
            if (a == null) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    askingFor = a.id;
                    try {
                        startActivityForResult(wa().askIntent(a), PICK_STATUSES);
                    } catch (RuntimeException e) {
                        Logs.note("statuses picker: " + e);
                        askingFor = null;
                        js("window.nzEvent && window.nzEvent('wa', " + Json.write(Json.obj("op", "cancelled", "app", a.id)) + ")");
                    }
                }
            });
        }

        /** Saves statuses (their ids, a JSON array) in the background; the page hears how it went. */
        @JavascriptInterface
        public void waSave(final String idsJson) {
            final List<String> ids = new ArrayList<String>();
            try {
                Object v = Json.parse(idsJson == null ? "[]" : idsJson);
                if (v instanceof List) for (Object o : (List<?>) v) if (o instanceof String) ids.add((String) o);
            } catch (Json.ParseError ignored) {
                // nothing to save
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    List<String> done = wa().save(ids);
                    Map<String, Object> ev = Json.obj("op", "saved", "ids", done, "folderLabel", WaStatuses.folderLabel());
                    ev.put("failed", done.size() < ids.size());
                    js("window.nzEvent && window.nzEvent('wa', " + Json.write(ev) + ")");
                }
            }, "save statuses").start();
        }

        // ---- HELINK's own update (Updates.java)

        @JavascriptInterface
        public String update() {
            return Json.write(engine().updates().view());
        }

        @JavascriptInterface
        public void checkUpdate() {
            engine().updates().checkNow();
        }

        @JavascriptInterface
        public String updateNow() {
            return Json.write(engine().updates().updateNow());
        }

        @JavascriptInterface
        public void updateLater() {
            engine().updates().later();
        }

        @JavascriptInterface
        public void cancelUpdate() {
            engine().updates().cancel();
        }

        @JavascriptInterface
        public void retryUpdate() {
            engine().updates().reset();
        }

        /**
         * Hands the downloaded update to Android's installer: "started", "permission" (Android
         * must first let HELINK install its updates: the page shows how), or "none".
         */
        @JavascriptInterface
        public String installUpdate() {
            if (!UpdateInstaller.allowed(MainActivity.this)) return "permission";
            final Engine e = engine();
            final File apk = e.updates().startInstall();
            if (apk == null) return "none";
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        UpdateInstaller.install(MainActivity.this, apk);
                    } catch (IOException | RuntimeException ex) {
                        Logs.note("install: " + ex);
                        e.updates().installEnded(false, ex.toString());
                    }
                }
            }, "install update").start();
            return "started";
        }

        /** Opens the Settings page where the user lets HELINK install its updates. */
        @JavascriptInterface
        public void openInstallSettings() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(UpdateInstaller.settings(MainActivity.this));
                    } catch (RuntimeException e) {
                        Logs.note("install settings: " + e);
                        toast(I18n.t("افتح الإعدادات ← التطبيقات ← HELINK ← «تثبيت تطبيقات غير معروفة».",
                                "Open Settings › Apps › HELINK › Install unknown apps."));
                    }
                }
            });
        }

        /** HELINK's download page, in the browser. */
        @JavascriptInterface
        public void openUpdatePage() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(com.alihelal.core.Updates.PAGE))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        toast(com.alihelal.core.Updates.PAGE);
                    }
                }
            });
        }

        /** Removes the music from a finished download: a new entry makes the copy. Returns {"id": …} or {"error": …}. */
        @JavascriptInterface
        public String removeMusic(String id) {
            Engine e = engine();
            Map<String, Object> r = e.removeMusic(id);
            keepRunning(e);
            return Json.write(r);
        }

        /** Opens the system's file picker for sound and video files (the answer comes as nzFilesAdded). */
        @JavascriptInterface
        public void pickFiles() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"})
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(i, PICK_FILES);
                    } catch (ActivityNotFoundException e) {
                        toast(I18n.t("لا يوجد على الهاتف تطبيق لاختيار الملفات.", "There's no app on this phone to choose files."));
                        js("window.nzFilesAdded && window.nzFilesAdded(null)");
                    }
                }
            });
        }

        @JavascriptInterface
        public void pause(String id) {
            engine().pause(id);
        }

        @JavascriptInterface
        public void resume(String id) {
            Engine e = engine();
            e.resume(id);
            keepRunning(e);
        }

        /** Cancels a download and deletes its partial files (a finished one only leaves the list). */
        @JavascriptInterface
        public void deleteTask(String id) {
            engine().delete(id);
        }

        @JavascriptInterface
        public void clearFinished() {
            engine().clearFinished();
        }

        @JavascriptInterface
        public String tasks() {
            return Json.write(engine().taskList());
        }

        /** Starts the service that keeps the downloads going in the background. */
        private void keepRunning(Engine e) {
            if (!e.downloading()) return;
            DownloadService.start(MainActivity.this);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    askNotifications();
                }
            });
        }

        /** "Call" and "WhatsApp" on the About screen (only these two addresses). */
        @JavascriptInterface
        public void contact(final String how) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if ("whatsapp".equals(how)) {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WHATSAPP))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } else {
                            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + PHONE))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    } catch (ActivityNotFoundException e) {
                        toast("whatsapp".equals(how) ? I18n.t("واتساب غير مثبّت على هذا الهاتف.", "WhatsApp isn't installed on this phone.")
                                : I18n.t("لا يوجد تطبيق اتصال على هذا الهاتف.", "There's no phone app on this device."));
                    } catch (RuntimeException e) {
                        Logs.note("contact: " + e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void screen(String name) {
            screen = name == null ? "" : name;
        }

        @JavascriptInterface
        public void openFile(final String uri, final String mime) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(Notifications.viewIntent(uri, mime));
                    } catch (ActivityNotFoundException e) {
                        toast(I18n.t("لا يوجد تطبيق على الهاتف يستطيع فتح هذا الملف.", "No app on this phone can open this file."));
                    } catch (RuntimeException e) {
                        Logs.note("open: " + e);
                        toast(I18n.t("تعذّر فتح الملف. ستجده في التنزيلات ← HELINK.", "Couldn't open the file. You'll find it in Download › HELINK."));
                    }
                }
            });
        }

        @JavascriptInterface
        public void shareFile(final String uri, final String mime, final String kind) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(Notifications.shareIntent(uri, mime, "audio".equals(kind)));
                    } catch (RuntimeException e) {
                        Logs.note("share: " + e);
                        toast(I18n.t("تعذّر فتح المشاركة الآن.", "Couldn't open sharing right now."));
                    }
                }
            });
        }

        @JavascriptInterface
        public void copyText(String text) {
            try {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("HELINK", text));
            } catch (RuntimeException ignored) {
                // nothing to do
            }
        }

        @JavascriptInterface
        public String log() {
            return Logs.recent(40);
        }
    }
}
