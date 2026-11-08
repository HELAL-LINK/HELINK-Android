package com.alihelal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.alihelal.core.I18n;

import java.util.Arrays;
import java.util.List;

/** The notification that shows the running downloads, and the ones that say a download finished. */
final class Notifications {
    private Notifications() {}

    static final String CH_PROGRESS = "progress";
    static final String CH_RESULT = "result";
    static final int ID_PROGRESS = 1;
    static final int ID_UPDATED = 2;
    static final int ORANGE = 0xFFF27221;

    /** Creates the channels, or renames them after a change of language. */
    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, I18n.t("التنزيلات الجارية", "Active downloads"),
                NotificationManager.IMPORTANCE_LOW);
        p.setDescription(I18n.t("يعرض تقدّم التنزيلات", "Shows how far the downloads are"));
        p.setShowBadge(false);
        NotificationChannel r = new NotificationChannel(CH_RESULT, I18n.t("التنزيلات المكتملة", "Finished downloads"),
                NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription(I18n.t("ينبّهك عند اكتمال تنزيل الفيديو", "Tells you when a video has finished downloading"));
        nm.createNotificationChannels(Arrays.asList(p, r));
    }

    private static int flags() {
        return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    }

    /** Each finished download gets its own notification (and its own buttons). */
    static int resultId(String taskId) {
        return 1000 + (taskId.hashCode() & 0x3ffff);
    }

    /** Opens the app on the downloads list. */
    static PendingIntent openDownloads(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .setAction(MainActivity.ACTION_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 10, i, flags());
    }

    static PendingIntent pauseAll(Context c) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(ActionReceiver.PAUSE_ALL);
        return PendingIntent.getBroadcast(c, 11, i, flags());
    }

    /** lines: one line per download when several run (shown when the notification is expanded). */
    static Notification progress(Context c, String title, String text, int percent, List<String> lines) {
        Notification.Builder b = new Notification.Builder(c, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_helal)
                .setColor(ORANGE)
                .setContentTitle(title == null || title.isEmpty() ? I18n.t("جارٍ تنزيل الفيديو…", "Downloading video…") : title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openDownloads(c))
                .setProgress(100, Math.max(0, Math.min(100, percent)), percent < 0)
                .addAction(new Notification.Action.Builder(null, I18n.t("إيقاف مؤقت", "Pause"), pauseAll(c)).build());
        if (lines != null && lines.size() > 1) {
            Notification.InboxStyle s = new Notification.InboxStyle();
            for (String l : lines) s.addLine(l);
            b.setStyle(s);
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    static Intent viewIntent(String uri, String mime) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent shareIntent(String uri, String mime, boolean audio) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, audio ? I18n.t("مشاركة الصوت", "Share audio") : I18n.t("مشاركة الفيديو", "Share video"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static Notification done(Context c, String taskId, String name, String uri, String mime, boolean audio, boolean noMusic) {
        // Request codes differ per download: share intents that only differ in their extras
        // would otherwise replace each other.
        int rc = 100 + (resultId(taskId) - 1000) * 2;
        PendingIntent view = PendingIntent.getActivity(c, rc, viewIntent(uri, mime), flags());
        PendingIntent share = PendingIntent.getActivity(c, rc + 1, shareIntent(uri, mime, audio), flags());
        return new Notification.Builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat_helal)
                .setColor(ORANGE)
                .setContentTitle(noMusic ? I18n.t("اكتملت إزالة الموسيقى", "Music removed")
                        : audio ? I18n.t("اكتمل تنزيل الصوت", "Audio downloaded") : I18n.t("اكتمل تنزيل الفيديو", "Video downloaded"))
                .setContentText(name)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(view)
                .addAction(new Notification.Action.Builder(null, I18n.t("تشغيل", "Play"), view).build())
                .addAction(new Notification.Action.Builder(null, I18n.t("مشاركة", "Share"), share).build())
                .build();
    }

    /** HELINK was just updated (the first start of the new version); opens it, or its downloads. */
    static Notification updated(Context c, String version, boolean downloading) {
        PendingIntent open = downloading ? openDownloads(c)
                : PendingIntent.getActivity(c, 12, new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags());
        String text = downloading
                ? I18n.t("الإصدار " + version + " جاهز، وتكمل التنزيلات من حيث توقفت.", "Version " + version + " is ready, and downloads continue where they stopped.")
                : I18n.t("الإصدار " + version + " جاهز. اضغط لفتح HELINK.", "Version " + version + " is ready. Tap to open HELINK.");
        return new Notification.Builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat_helal)
                .setColor(ORANGE)
                .setContentTitle(I18n.t("تم تحديث HELINK", "HELINK was updated"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open)
                .build();
    }

    static Notification failed(Context c, String videoTitle, String title, String message) {
        String body = (videoTitle.isEmpty() ? "" : videoTitle + "\n") + title + (message.isEmpty() ? "" : "\n" + message);
        return new Notification.Builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat_helal)
                .setColor(ORANGE)
                .setContentTitle(I18n.t("لم يكتمل التنزيل", "Download didn't finish"))
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(openDownloads(c))
                .build();
    }
}
