package com.alihelal.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The notification's button: pause every download (they are resumed from the list). */
public final class ActionReceiver extends BroadcastReceiver {
    static final String PAUSE_ALL = "com.alihelal.app.PAUSE_ALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && PAUSE_ALL.equals(intent.getAction())) {
            App.engine(context).pauseAll();
        }
    }
}
