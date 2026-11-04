package com.aghani.q20messenger;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

/** Keeps incoming-call discovery alive after leaving the chat screen on API 18. */
public class IncomingCallService extends Service {
    private ScheduledExecutorService worker;
    private ScheduledExecutorService syncWorker;
    private volatile boolean activeCall;
    private final java.util.concurrent.atomic.AtomicBoolean syncingMessages=new java.util.concurrent.atomic.AtomicBoolean();
    private String ringingId = "";
    private NotificationManager notifications;
    private android.os.PowerManager.WakeLock listeningLock;
    @Override public void onCreate() {
        super.onCreate();
        if(!AppPrefs.backgroundEnabled(this)){stopSelf();return;}
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        listeningLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Q20Messenger:incoming-calls");
        listeningLock.acquire();
        Intent open = new Intent(this, MainActivity.class);
        startForeground(200, new Notification.Builder(this).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Q20 Messenger").setContentText("Listening for calls and messages")
                .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop listening", PendingIntent.getService(this, 4, new Intent(this, IncomingCallService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT)).build());
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(new Runnable() { public void run() { checkCall(); } }, 0, 3, TimeUnit.SECONDS);
        syncWorker=Executors.newSingleThreadScheduledExecutor();
        syncWorker.scheduleWithFixedDelay(new Runnable(){public void run(){
            if(activeCall||!AppPrefs.backgroundEnabled(IncomingCallService.this)||AppPrefs.getPhoneNumber(IncomingCallService.this).length()==0||AppPrefs.getServerUrl(IncomingCallService.this).length()==0||android.os.SystemClock.elapsedRealtime()-SyncCoordinator.lastFinishedAt()<2500||!syncingMessages.compareAndSet(false,true))return;
            AppExecutors.SYNC.execute(new Runnable(){public void run(){try{SyncCoordinator.sync(IncomingCallService.this,new ApiClient(IncomingCallService.this),null);}finally{syncingMessages.set(false);}}});
        }},5,5,TimeUnit.SECONDS);
    }
    private void checkCall() {
        if (AppPrefs.getApiToken(this).length() == 0) return;
        try {
            JSONObject call = new ApiClient(this).getVoiceCallStatus();
            handleCall(call);
        } catch (Exception ignored) { /* Keep the listener alive through transient network loss. */ }
    }
    void handleCall(JSONObject call) {
        activeCall=call.optBoolean("active");
            if (!call.optBoolean("active") || !call.optBoolean("incoming")) {
                if(!call.optBoolean("active") && ringingId.length()>0) LocalStore.get(this).finishMissedCall(ringingId,"missed");
                ringingId = ""; notifications.cancel(201); return;
            }
            String id = call.optString("call_id", "");
            if (id.length() == 0 || id.equals(ringingId)) return;
            ringingId = id;
            String peer = call.optString("peer", "");
            String name = LocalStore.get(this).getChatName(peer);
            LocalStore.get(this).saveCall(id,peer,name,"Incoming",System.currentTimeMillis(),0,0,"ringing");
            Intent answer = new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            answer.putExtra("incoming", true); answer.putExtra("call_id", id);
            answer.putExtra("peer_jid", peer); answer.putExtra("peer_name", name);
            Intent popup = new Intent(this,IncomingCallActivity.class).putExtra("call_id",id).putExtra("peer_jid",peer).putExtra("peer_name",name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent display=PendingIntent.getActivity(this,1,popup,PendingIntent.FLAG_UPDATE_CURRENT);
            Intent decline = new Intent(this, DeclineCallReceiver.class).putExtra("call_id", id);
            Notification.Builder builder = new Notification.Builder(this).setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Incoming WhatsApp call").setContentText(name).setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setContentIntent(display).setFullScreenIntent(display,true)
                    .addAction(android.R.drawable.ic_menu_call, "Answer", PendingIntent.getActivity(this, 2, answer, PendingIntent.FLAG_UPDATE_CURRENT))
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", PendingIntent.getBroadcast(this, 3, decline, PendingIntent.FLAG_UPDATE_CURRENT))
                    ;
            MessageNotifications.applySound(this,builder,true);
            Notification incoming=builder.build();
            incoming.flags |= Notification.FLAG_INSISTENT;
            notifications.notify(201, incoming);
            // API 18 permits the foreground listener to present incoming UI while the app is closed.
            try{startActivity(popup);}catch(Exception ignored){}
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) { AppPrefs.setBackgroundEnabled(this,false); stopSelf(); return START_NOT_STICKY; }
        if(!AppPrefs.backgroundEnabled(this)){stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    @Override public void onTaskRemoved(Intent root){if(AppPrefs.backgroundEnabled(this)){android.app.AlarmManager alarm=(android.app.AlarmManager)getSystemService(ALARM_SERVICE);alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,android.os.SystemClock.elapsedRealtime()+3000,PendingIntent.getService(this,7,new Intent(this,IncomingCallService.class),PendingIntent.FLAG_UPDATE_CURRENT));}super.onTaskRemoved(root);}
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if(!AppPrefs.backgroundEnabled(this))((android.app.AlarmManager)getSystemService(ALARM_SERVICE)).cancel(PendingIntent.getService(this,7,new Intent(this,IncomingCallService.class),PendingIntent.FLAG_UPDATE_CURRENT));
        if (worker != null) worker.shutdownNow();
        if(syncWorker!=null)syncWorker.shutdownNow();
        if (notifications != null) notifications.cancel(201);
        if (listeningLock != null && listeningLock.isHeld()) listeningLock.release();
        super.onDestroy();
    }
}
