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
    private volatile String ringingId = "";
    private volatile boolean destroyed;
    private String silencedId = "";
    private NotificationManager notifications;
    private android.os.PowerManager.WakeLock listeningLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.media.Ringtone ringtone;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private static PendingIntent restartIntent(android.content.Context context){return PendingIntent.getBroadcast(context,8,new Intent(context,StartupReceiver.class).setAction(StartupReceiver.RESTART),PendingIntent.FLAG_UPDATE_CURRENT);}
    private void watchdog(boolean enabled){android.app.AlarmManager alarm=(android.app.AlarmManager)getSystemService(ALARM_SERVICE);if(enabled)alarm.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,android.os.SystemClock.elapsedRealtime()+60000,60000,restartIntent(this));else alarm.cancel(restartIntent(this));}
    private long recordedAt;
    private void recordCheck(String error){long now=System.currentTimeMillis();if(now-recordedAt<15000)return;recordedAt=now;getSharedPreferences("listener_state",0).edit().putLong("checked_at",now).putString("error",error==null?"":error).apply();}
    private void silence(){if(ringtone!=null){ringtone.stop();ringtone=null;}((android.os.Vibrator)getSystemService(VIBRATOR_SERVICE)).cancel();}
    private void ring(){if(ringingId.equals(silencedId))return;try{int mode=((android.media.AudioManager)getSystemService(AUDIO_SERVICE)).getRingerMode();if(mode==android.media.AudioManager.RINGER_MODE_NORMAL){if(ringtone==null){ringtone=android.media.RingtoneManager.getRingtone(this,android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE));if(ringtone!=null)ringtone.setStreamType(android.media.AudioManager.STREAM_RING);}if(ringtone!=null&&!ringtone.isPlaying())ringtone.play();}else if(ringtone!=null){ringtone.stop();ringtone=null;}}catch(RuntimeException ignored){}}

    @Override public void onCreate() {
        super.onCreate();
        if(!AppPrefs.backgroundEnabled(this)){stopSelf();return;}
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        listeningLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Q20Messenger:incoming-calls");
        listeningLock.setReferenceCounted(false);listeningLock.acquire();
        try{android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);if(wifi!=null){wifiLock=wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Q20Messenger:incoming-wifi");wifiLock.setReferenceCounted(false);wifiLock.acquire();}}catch(RuntimeException ignored){}
        watchdog(true);
        Intent open = new Intent(this, MainActivity.class);
        startForeground(200, new Notification.Builder(this).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(BuildConfig.STAGING_BUILD ? "Q20 Messenger TEST" : "Q20 Messenger").setContentText("Listening for calls and messages")
                .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop listening", PendingIntent.getService(this, 4, new Intent(this, IncomingCallService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT)).build());
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(new Runnable() { public void run() { checkCall(); } }, 0, 1, TimeUnit.SECONDS);
        syncWorker=Executors.newSingleThreadScheduledExecutor();
        syncWorker.scheduleWithFixedDelay(new Runnable(){public void run(){
            if(activeCall||!AppPrefs.backgroundEnabled(IncomingCallService.this)||AppPrefs.getPhoneNumber(IncomingCallService.this).length()==0||AppPrefs.getServerUrl(IncomingCallService.this).length()==0||android.os.SystemClock.elapsedRealtime()-SyncCoordinator.lastFinishedAt()<2500||!syncingMessages.compareAndSet(false,true))return;
            AppExecutors.SYNC.execute(new Runnable(){public void run(){try{SyncCoordinator.sync(IncomingCallService.this,new ApiClient(IncomingCallService.this),null);}finally{syncingMessages.set(false);}}});
        }},5,10,TimeUnit.SECONDS);
    }
    private void checkCall() {
        if (AppPrefs.getApiToken(this).length() == 0) return;
        try {
            JSONObject call = new ApiClient(this).getVoiceCallStatus();
            recordCheck(null);handleCall(call);
        } catch (Exception error) {recordCheck(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
    }
    void handleCall(JSONObject call) {
        if(destroyed)return;
        activeCall=call.optBoolean("active");
            if (!call.optBoolean("active") || !call.optBoolean("incoming")) {
                if(!call.optBoolean("active") && ringingId.length()>0){final String missedId=ringingId;AppExecutors.DATABASE.execute(new Runnable(){public void run(){try{LocalStore.get(IncomingCallService.this).finishMissedCall(missedId,"missed");}catch(Exception ignored){}}});}
                boolean wasRinging=ringingId.length()>0;
                ringingId = ""; if(wasRinging)main.post(new Runnable(){public void run(){silence();}}); notifications.cancel(201); return;
            }
            String id = call.optString("call_id", "");
            if (id.length() == 0) return;
            if(id.equals(ringingId)){main.post(new Runnable(){public void run(){ring();}});return;}
            ringingId = id;
            String peer = call.optString("peer", "");
            String cachedName = ConversationCache.name(peer);
            String name = cachedName.length()>0?cachedName:"WhatsApp contact";

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
            // The service owns ringing on BB10; avoid a duplicate notification ringtone.
            Notification incoming=builder.build();
            incoming.flags |= Notification.FLAG_INSISTENT;
            notifications.notify(201, incoming);
            // API 18 permits the foreground listener to present incoming UI while the app is closed.
            final Intent incomingPopup=popup;
            main.post(new Runnable(){public void run(){
                if(destroyed)return;
                ring();
                if(((android.media.AudioManager)getSystemService(AUDIO_SERVICE)).getRingerMode()!=android.media.AudioManager.RINGER_MODE_SILENT)((android.os.Vibrator)getSystemService(VIBRATOR_SERVICE)).vibrate(new long[]{0,700,500},0);
                try{android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);android.os.PowerManager.WakeLock wake=power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK|android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"Q20Messenger:ring");wake.acquire(10000);startActivity(incomingPopup);}catch(RuntimeException ignored){}
            }});
            final String savedId=id,savedPeer=peer,savedName=name;
            AppExecutors.DATABASE.execute(new Runnable(){public void run(){try{LocalStore.get(IncomingCallService.this).saveCall(savedId,savedPeer,savedName,"Incoming",System.currentTimeMillis(),0,0,"ringing");}catch(Exception ignored){}}});
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if(intent!=null&&"silence".equals(intent.getAction())){silencedId=ringingId;silence();return START_STICKY;}
        if (intent != null && "stop".equals(intent.getAction())) { AppPrefs.setBackgroundEnabled(this,false); stopSelf(); return START_NOT_STICKY; }
        if(!AppPrefs.backgroundEnabled(this)){stopSelf();return START_NOT_STICKY;}
        watchdog(true);
        return START_STICKY;
    }
    @Override public void onTaskRemoved(Intent root){if(AppPrefs.backgroundEnabled(this)){android.app.AlarmManager alarm=(android.app.AlarmManager)getSystemService(ALARM_SERVICE);alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,android.os.SystemClock.elapsedRealtime()+3000,restartIntent(this));}super.onTaskRemoved(root);}
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed=true;
        if(!AppPrefs.backgroundEnabled(this))watchdog(false);
        if(!AppPrefs.backgroundEnabled(this))((android.app.AlarmManager)getSystemService(ALARM_SERVICE)).cancel(PendingIntent.getService(this,7,new Intent(this,IncomingCallService.class),PendingIntent.FLAG_UPDATE_CURRENT));
        main.removeCallbacksAndMessages(null);silence();
        if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();
        if (worker != null) worker.shutdownNow();
        if(syncWorker!=null)syncWorker.shutdownNow();
        if (notifications != null) notifications.cancel(201);
        if (listeningLock != null && listeningLock.isHeld()) listeningLock.release();
        super.onDestroy();
    }
}




