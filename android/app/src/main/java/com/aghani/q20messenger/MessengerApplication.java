package com.aghani.q20messenger;
public final class MessengerApplication extends android.app.Application {
    @Override public void onLowMemory(){super.onLowMemory();ConversationCache.trim();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW)ConversationCache.trim();}
    @Override public void onCreate(){super.onCreate();CrashReporter.install(this);EmojiText.warm(this);}
}

