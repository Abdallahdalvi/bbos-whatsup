package com.aghani.q20messenger;
public final class MessengerApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();CrashReporter.install(this);}
}
