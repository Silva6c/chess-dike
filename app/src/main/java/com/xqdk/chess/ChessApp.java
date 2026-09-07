package com.xqdk.chess;

import android.app.Application;
import android.content.Context;

public class ChessApp extends Application {
    private static Context appContext;

    /** Get the application context. */
    public static Context getContext() {
        return appContext;
    }

    public ChessApp() {
        appContext = this;
    }
}
