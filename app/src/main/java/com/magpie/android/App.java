package com.magpie.android;

import android.app.Application;
import android.content.Context;

/**
 * Holds an application context for the static helpers in {@link GatewayService}
 * (the log ring, the log file) that have no Activity to ask for one.
 */
public class App extends Application {

    private static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
        // the Web UI's signing key is generated before anything else, so the
        // first process magpie starts already has one to carry
        Prefs.webKey(this);
    }

    public static Context context() {
        return ctx;
    }
}
