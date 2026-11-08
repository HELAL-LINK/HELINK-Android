package com.alihelal.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.alihelal.core.Engine;

/**
 * How Android's installer did with HELINK's new version (UpdateInstaller), and the first
 * start of the new version (MY_PACKAGE_REPLACED): the downloads that were running continue,
 * and a notification says HELINK was updated.
 */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String a = i.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            // The engine (App) noticed the update when it started, and queued the downloads again.
            Engine e = App.engine(c);
            if (e.downloading()) DownloadService.start(c);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    nm.notify(Notifications.ID_UPDATED, Notifications.updated(c, Engine.VERSION, e.downloading()));
                } catch (RuntimeException ex) {
                    Logs.note("updated notification: " + ex);
                }
            }
            return;
        }
        if (!UpdateInstaller.ACTION_STATUS.equals(a)) return;
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                App.engine(c).updates().installEnded(false, "no confirmation window");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (MainActivity.resumed) {
                UpdateInstaller.pendingConfirm = null;
                try {
                    c.startActivity(confirm);
                    return;
                } catch (RuntimeException e) {
                    Logs.note("install confirmation: " + e);
                }
            }
            // HELINK is not in front (Android would not show the window now): its screen shows
            // it when it comes back.
            UpdateInstaller.pendingConfirm = confirm;
            return;
        }
        UpdateInstaller.pendingConfirm = null;
        if (status == PackageInstaller.STATUS_SUCCESS) return; // the new version takes over
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Logs.note("update install: status " + status + " " + msg);
        App.engine(c).updates().installEnded(status == PackageInstaller.STATUS_FAILURE_ABORTED, status + ": " + msg);
    }
}
