package com.aghani.q20messenger;
import android.content.*;
public class StartupReceiver extends BroadcastReceiver {
 static final String RESTART="com.aghani.q20messenger.RESTART_LISTENER";
 @Override public void onReceive(Context context,Intent intent){String action=intent.getAction();if((Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)||RESTART.equals(action))&&AppPrefs.backgroundEnabled(context)&&AppPrefs.getApiToken(context).length()>0){try{context.startService(new Intent(context,IncomingCallService.class));}catch(RuntimeException error){android.util.Log.w("Q20-listener","Could not restart listener",error);}}}
}
