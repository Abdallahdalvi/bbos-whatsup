package com.aghani.q20messenger;
import android.app.*;import android.content.*;import android.database.Cursor;import android.media.*;import org.json.*;import java.util.*;
/** Notification watermark is independent of history import IDs and Q20 read cursors. */
final class MessageNotifications {
 static void prepare(Context context){LocalStore store=LocalStore.get(context);if(store.getState("notifications_since")==0){store.putState("notifications_since",System.currentTimeMillis());store.putState("notifications_after_id",store.getSyncCursor());}}
 static void applySound(Context context,Notification.Builder builder,boolean call){
  int mode=((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).getRingerMode();
  if(mode==AudioManager.RINGER_MODE_NORMAL){builder.setSound(RingtoneManager.getDefaultUri(call?RingtoneManager.TYPE_RINGTONE:RingtoneManager.TYPE_NOTIFICATION));builder.setDefaults(Notification.DEFAULT_VIBRATE);}
  else if(mode==AudioManager.RINGER_MODE_VIBRATE)builder.setDefaults(Notification.DEFAULT_VIBRATE);
 }
 static int id(String jid){return 10000+(jid.hashCode()&0x0fffffff);}
 static void cancelChat(Context context,String jid){((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(id(jid));}
 static void afterSync(Context context){
  if(!AppPrefs.backgroundEnabled(context))return;
  LocalStore store=LocalStore.get(context);long since=store.getState("notifications_since"),cursor=store.getState("notifications_after_id"),snapshot=store.getSyncCursor();
  if(since==0){prepare(context);return;}if(snapshot<=cursor)return;
  NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
  HashSet<String> alerted=new HashSet<String>();
  while(cursor<snapshot){
   Cursor c=store.getReadableDatabase().rawQuery("SELECT server_id,chat_jid,body,message_type,rich_json FROM messages WHERE server_id>? AND server_id<=? AND from_me=0 AND message_type NOT IN ("+MessageContent.NO_UNREAD_SQL+") AND timestamp>=? ORDER BY server_id LIMIT 200",new String[]{String.valueOf(cursor),String.valueOf(snapshot),String.valueOf(since)});
   LinkedHashMap<String,String> latest=new LinkedHashMap<String,String>();long next=cursor;
   try{while(c.moveToNext()){next=c.getLong(0);String jid=c.getString(1);if(jid.equals(AppVisibility.visibleChat))continue;
      if(next<=store.getState("read:"+jid))continue;
      JSONObject rich=null;try{rich=new JSONObject(c.getString(4));}catch(Exception ignored){}
      String text=MessageContent.body(c.getString(3),c.getString(2),rich);if(text.length()==0)text=c.getString(3);latest.put(jid,text.length()>160?text.substring(0,160)+"…":text);
   }}finally{c.close();}
   for(Map.Entry<String,String> item:latest.entrySet()){
    String jid=item.getKey(),name=store.getChatName(jid);Intent open=new Intent(context,ChatActivity.class).putExtra("peer_jid",jid).putExtra("peer_name",name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    Notification.Builder builder=new Notification.Builder(context).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(name).setContentText(item.getValue()).setTicker(name+": "+item.getValue()).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).setContentIntent(PendingIntent.getActivity(context,id(jid),open,PendingIntent.FLAG_UPDATE_CURRENT));
    if(!alerted.add(jid))builder.setOnlyAlertOnce(true);
    applySound(context,builder,false);manager.notify(id(jid),builder.build());
   }
   if(next==cursor){cursor=snapshot;}else cursor=next;
   store.putState("notifications_after_id",cursor);
  }
 }
 private MessageNotifications(){}
}

