package com.alihelal.app;

import android.app.Application;
import android.content.Context;

import com.alihelal.core.Engine;
import com.alihelal.core.Json;

import java.util.Map;

/**
 * Holds the engine for the whole app process, so the downloads keep going (and their
 * state survives) when the screen is closed and opened again.
 */
public final class App extends Application {
    private static Engine engine;
    private static Dispatcher dispatcher;

    @Override
    public void onCreate() {
        super.onCreate();
        engine(this); // reads the chosen language before the channels are named
        Notifications.createChannels(this);
    }

    static synchronized Engine engine(Context c) {
        if (engine == null) {
            Context app = c.getApplicationContext();
            dispatcher = new Dispatcher(app);
            engine = new Engine(new AndroidHost(app), dispatcher);
            engine.startMaintenance();
        }
        return engine;
    }

    static synchronized Dispatcher dispatcher(Context c) {
        engine(c);
        return dispatcher;
    }

    /** Sends engine events to the visible page and to the download notification. */
    static final class Dispatcher implements Engine.Events {
        interface Sink {
            void deliver(String name, String json);
        }

        private final Context ctx;
        private volatile Sink sink;

        Dispatcher(Context ctx) {
            this.ctx = ctx;
        }

        void attach(Sink s) {
            sink = s;
        }

        void detach(Sink s) {
            if (sink == s) sink = null;
        }

        boolean pageVisible() {
            return sink != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void emit(String name, Object data) {
            Sink s = sink;
            if (s != null) s.deliver(name, Json.write(data));
            if (name.equals("task") && data instanceof Map) {
                DownloadService.onTaskEvent(ctx, (Map<String, Object>) data);
            }
        }
    }
}
