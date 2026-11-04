package com.aghani.q20messenger;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class DeclineCallReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        final String id = intent.getStringExtra("call_id");
        if (id == null || id.length() == 0) return;
        ((android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(201);
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try { new ApiClient(context).rejectVoiceCall(id); LocalStore.get(context).finishMissedCall(id,"declined"); } catch (Exception ignored) { }
                finally { pending.finish(); }
            }
        }, "Q20-decline-call").start();
    }
}
