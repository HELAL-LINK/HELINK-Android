package com.alihelal.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands HELINK's new version (downloaded and checked by the engine, Updates.java) to Android's
 * installer. Android shows its own window to confirm ("Do you want to update this app?"), keeps
 * the app's data, and replaces the app; UpdateReceiver hears how it went.
 */
final class UpdateInstaller {
    private UpdateInstaller() {}

    static final String ACTION_STATUS = "com.alihelal.app.UPDATE_STATUS";

    /** Android's window asking to confirm the install, while it waits (shown again if it was missed). */
    static volatile Intent pendingConfirm;

    /** Whether Android lets HELINK install its updates (the user allows it once, in Settings). */
    static boolean allowed(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** The Settings page where the user lets HELINK install its updates. */
    static Intent settings(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Writes the APK into an install session and commits it; Android then asks the user. */
    static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            // Without asking when Android allows it (HELINK installed itself before); it asks otherwise.
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        boolean committed = false;
        try {
            OutputStream out = s.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            Intent i = new Intent(c, UpdateReceiver.class).setAction(ACTION_STATUS).setPackage(c.getPackageName());
            // Android fills in how it went: the intent must be mutable.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent cb = PendingIntent.getBroadcast(c, 77, i, flags);
            s.commit(cb.getIntentSender());
            committed = true;
        } catch (RuntimeException e) {
            throw new IOException("install session: " + e, e);
        } finally {
            s.close();
            if (!committed) {
                try {
                    pi.abandonSession(id);
                } catch (RuntimeException ignored) {
                    // gone already
                }
            }
        }
    }
}
