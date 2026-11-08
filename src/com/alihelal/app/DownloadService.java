package com.alihelal.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.alihelal.core.DownloadTask;
import com.alihelal.core.Engine;
import com.alihelal.core.I18n;
import com.alihelal.core.UserError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the app alive while downloads run or wait (Android stops background apps
 * otherwise), shows their progress in one notification, and keeps the phone from sleeping
 * in the middle. It stops by itself when nothing is left to download.
 */
public final class DownloadService extends Service {
    private static DownloadService running;
    private static long lastUpdate;
    /** The last state and revision seen for each download, to notice when one finishes. */
    private static final Map<String, Object[]> seen = new HashMap<String, Object[]>();
    private PowerManager.WakeLock wake;
    private boolean finished;

    static void start(Context c) {
        Intent i = new Intent(c, DownloadService.class);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            // Not allowed right now (the app was not in front): the downloads still run.
            Logs.note("cannot start the download service: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            finished = false; // the same service can be started again for the next download
        }
        Engine engine = App.engine(this);
        Notification n = summary(this, engine.activeTasks());
        if (n == null) n = Notifications.progress(this, "", I18n.t("جارٍ تجهيز التنزيل…", "Preparing the download…"), -1, null);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifications.ID_PROGRESS, n);
            }
        } catch (RuntimeException e) {
            Logs.note("startForeground failed: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (DownloadService.class) {
            running = this;
        }
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alihelal:download");
                wake.setReferenceCounted(false);
                wake.acquire(6L * 3600 * 1000);
            }
        }
        // Everything may already be over (a very small file).
        if (!engine.downloading()) finish();
        return START_NOT_STICKY;
    }

    /** Ends the service (called from the main thread or the engine's threads). */
    private synchronized void finish() {
        synchronized (DownloadService.class) {
            if (running == this) running = null;
        }
        if (finished) return;
        finished = true;
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        synchronized (DownloadService.class) {
            if (running == this) running = null;
        }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId) {
        // Paused, not cancelled: the user resumes them later from where they stopped.
        App.engine(this).pauseAll();
        finish();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 limits background data work to 6 hours a day.
        App.engine(this).pauseAll();
        finish();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** What a download is doing in a step without measurable progress ("" otherwise). */
    private static String stageText(String stage) {
        if (stage.equals("preparing")) return I18n.t("جارٍ تجهيز التنزيل…", "Preparing the download…");
        if (stage.equals("updating")) return I18n.t("غيّر الموقع طريقة عمله، جارٍ تحديث التطبيق…", "The site changed something, updating the app…");
        if (stage.equals("merge")) return I18n.t("جارٍ دمج الصوت مع الصورة…", "Joining sound and picture…");
        if (stage.equals("convert")) return I18n.t("جارٍ التحويل إلى MP3…", "Converting to MP3…");
        if (stage.equals("save")) return I18n.t("جارٍ حفظ الملف…", "Saving the file…");
        if (stage.equals("finish")) return I18n.t("جارٍ إنهاء الملف…", "Finishing up…");
        if (stage.equals("sepprep")) return I18n.t("جارٍ تجهيز أداة إزالة الموسيقى…", "Preparing the music remover…");
        if (stage.equals("sepwait")) return I18n.t("في انتظار إزالة الموسيقى…", "Waiting to remove the music…");
        return "";
    }

    /** One line about a download: its text and percent (-1 when there is nothing to measure). */
    static Object[] describe(DownloadTask t) {
        if (t.state.equals(DownloadTask.QUEUED)) return new Object[]{I18n.t("في انتظار دوره…", "Waiting its turn…"), -1};
        if (t.stopReason.equals("pause")) return new Object[]{I18n.t("جارٍ الإيقاف المؤقت…", "Pausing…"), -1};
        if (t.stopReason.equals("delete")) return new Object[]{I18n.t("جارٍ الإلغاء…", "Cancelling…"), -1};
        String st = t.type.equals("sep") && t.stage.equals("preparing") ? I18n.t("جارٍ تجهيز الملف…", "Preparing the file…")
                : stageText(t.stage);
        if (st.length() > 0) return new Object[]{st, -1};
        int pct = (int) Math.floor(Math.max(0, Math.min(100, t.percent)));
        StringBuilder b = new StringBuilder();
        // The music removal: its own steps, each with its own progress.
        if (t.stage.equals("sepmodel")) b.append(I18n.t("تنزيل أداة إزالة الموسيقى: ", "Downloading the music remover: "));
        if (t.stage.equals("separate")) b.append(I18n.t("إزالة الموسيقى: ", "Removing the music: "));
        if (t.stage.equals("copy")) b.append(I18n.t("تجهيز الملف: ", "Preparing the file: "));
        b.append(pct).append("%");
        String total = t.totalText();
        if (!total.isEmpty()) b.append(" • ").append(t.doneText()).append(I18n.t(" من ", " of ")).append(total);
        String eta = t.etaText();
        if (!eta.isEmpty()) b.append(" • ").append(eta);
        return new Object[]{b.toString(), pct};
    }

    private static String runningText(int n) {
        if (I18n.english()) return n == 1 ? "1 download running" : n + " downloads running";
        if (n == 1) return "تنزيل جارٍ";
        if (n == 2) return "تنزيلان جاريان";
        if (n <= 10) return n + " تنزيلات جارية";
        return n + " تنزيلًا جاريًا";
    }

    private static String waitingText(int n) {
        if (I18n.english()) return ", " + n + " waiting";
        if (n == 1) return "وواحد في الانتظار";
        if (n == 2) return "واثنان في الانتظار";
        return "و" + n + " في الانتظار";
    }

    /** The notification for the downloads that run or wait, or null when there are none. */
    static Notification summary(Context c, List<DownloadTask> active) {
        if (active.isEmpty()) return null;
        if (active.size() == 1) {
            DownloadTask t = active.get(0);
            Object[] d = describe(t);
            return Notifications.progress(c, t.title, (String) d[0], (Integer) d[1], null);
        }
        int run = 0, wait = 0, sum = 0, measured = 0;
        // The ones that run first (the collapsed notification shows the first line).
        List<String> lines = new ArrayList<String>();
        List<String> waiting = new ArrayList<String>();
        for (DownloadTask t : active) {
            Object[] d = describe(t);
            String title = t.title.length() > 48 ? t.title.substring(0, 47) + "…" : t.title;
            // (a file waiting for the music remover counts as waiting)
            if (t.state.equals(DownloadTask.RUNNING) && !t.stage.equals("sepwait")) {
                run++;
                int p = (Integer) d[1];
                if (p >= 0) {
                    sum += p;
                    measured++;
                }
                lines.add(d[0] + " • " + title);
            } else {
                wait++;
                waiting.add(d[0] + " • " + title);
            }
        }
        lines.addAll(waiting);
        String title = run == 0 ? I18n.t("التنزيلات في انتظار دورها", "Downloads waiting their turn")
                : runningText(run) + (wait > 0 ? (I18n.english() ? "" : " ") + waitingText(wait) : "");
        String text = lines.get(0);
        int pct = measured > 0 ? sum / measured : -1;
        return Notifications.progress(c, title, text, pct, lines);
    }

    /** Called for every "task" event of the engine (from its background threads). */
    @SuppressWarnings("unchecked")
    static void onTaskEvent(Context c, Map<String, Object> ev) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        boolean changed = false;
        String op = String.valueOf(ev.get("op"));
        if (op.equals("remove")) {
            String id = String.valueOf(ev.get("id"));
            synchronized (seen) {
                seen.remove(id);
            }
            nm.cancel(Notifications.resultId(id));
            changed = true;
        } else if (ev.get("task") instanceof Map) {
            Map<String, Object> t = (Map<String, Object>) ev.get("task");
            String id = String.valueOf(t.get("id"));
            String state = String.valueOf(t.get("state"));
            long rev = t.get("rev") instanceof Number ? ((Number) t.get("rev")).longValue() : 0;
            String before;
            synchronized (seen) {
                Object[] last = seen.get(id);
                if (last != null && (Long) last[1] > rev) return; // an older event that came late
                before = last == null ? null : (String) last[0];
                seen.put(id, new Object[]{state, rev});
            }
            changed = !state.equals(before) || t.containsKey("stopping");
            if (changed) onStateChange(c, nm, t, id, state, before);
        }
        updateOngoing(c, nm, changed);
    }

    private static void onStateChange(Context c, NotificationManager nm, Map<String, Object> t, String id, String state,
                                      String before) {
        boolean visible = App.dispatcher(c).pageVisible();
        if (state.equals(DownloadTask.DONE) && before != null && !before.equals(DownloadTask.DONE)) {
            // Only when the user is elsewhere: the list already says it.
            if (!visible) {
                Object r = t.get("result");
                if (r instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) r;
                    nm.notify(Notifications.resultId(id), Notifications.done(c, id, String.valueOf(m.get("name")),
                            String.valueOf(m.get("path")), String.valueOf(m.get("mime")), "audio".equals(m.get("kind")),
                            Boolean.TRUE.equals(t.get("noMusic"))));
                }
            }
        } else if (state.equals(DownloadTask.ERROR) && before != null && !before.equals(DownloadTask.ERROR)) {
            if (!visible) {
                Object e = t.get("error");
                String title = e instanceof UserError ? ((UserError) e).title() : I18n.t("حدثت مشكلة", "Something went wrong");
                String msg = e instanceof UserError ? ((UserError) e).message() : "";
                nm.notify(Notifications.resultId(id), Notifications.failed(c, String.valueOf(t.get("title")), title, msg));
            }
        } else if (state.equals(DownloadTask.QUEUED) || state.equals(DownloadTask.RUNNING)) {
            // Resumed: its old "did not finish" notification is out of date.
            if (before != null && before.equals(DownloadTask.ERROR)) nm.cancel(Notifications.resultId(id));
        }
    }

    /** Redraws the notification (after a change of language). */
    static void refresh(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) updateOngoing(c, nm, true);
    }

    private static void updateOngoing(Context c, NotificationManager nm, boolean force) {
        List<DownloadTask> active = App.engine(c).activeTasks();
        DownloadService s;
        synchronized (DownloadService.class) {
            s = running;
        }
        if (active.isEmpty()) {
            if (s != null) s.finish();
            nm.cancel(Notifications.ID_PROGRESS);
            return;
        }
        if (s == null) return; // the notification belongs to the service
        synchronized (DownloadService.class) {
            long now = System.currentTimeMillis();
            if (!force && now - lastUpdate < 1000) return;
            lastUpdate = now;
        }
        Notification n = summary(c, active);
        if (n != null) nm.notify(Notifications.ID_PROGRESS, n);
    }
}
