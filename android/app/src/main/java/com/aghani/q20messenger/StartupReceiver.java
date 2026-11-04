package com.aghani.q20messenger;
import android.content.*;
public class StartupReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent){String action=intent.getAction();if((Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))&&AppPrefs.backgroundEnabled(context)&&AppPrefs.getPhoneNumber(context).length()>0)context.startService(new Intent(context,IncomingCallService.class));}
}
