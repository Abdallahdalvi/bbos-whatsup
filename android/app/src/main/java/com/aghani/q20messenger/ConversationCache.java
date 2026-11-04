package com.aghani.q20messenger;
import android.content.Context;import android.util.LruCache;import org.json.*;import java.util.*;
/** All history stays in SQLite. A bounded recent-page cache makes opening chats independent of HTTP. */
final class ConversationCache {
 private static JSONArray chats,contacts,calls;private static long refreshedAt,revision;private static boolean warming;
 private static final HashSet<String> pending=new HashSet<String>();
 private static final class Page extends ArrayList<JSONObject>{final int bytes;Page(java.util.List<JSONObject> values){super(values);long estimate=128;for(JSONObject row:values)estimate+=512L+row.toString().length()*2L;bytes=(int)Math.min(Integer.MAX_VALUE,estimate);}}
 private static final LruCache<String,Page> pages=new LruCache<String,Page>(3*1024*1024){protected int sizeOf(String key,Page value){return value.bytes;}};
 static synchronized void trim(){pages.evictAll();refreshedAt=0;}
 static synchronized void clear(){chats=null;contacts=null;calls=null;refreshedAt=0;revision++;pages.evictAll();pending.clear();}
 static synchronized JSONArray chats(){return chats;} static synchronized JSONArray contacts(){return contacts;} static synchronized JSONArray calls(){return calls;}
 static synchronized ArrayList<JSONObject> messages(String jid){ArrayList<JSONObject> found=pages.get(jid);return found==null?null:new ArrayList<JSONObject>(found);}
 static synchronized void invalidateMessages(Set<String> jids){revision++;for(String jid:jids)pages.remove(jid);}
 static void remember(String jid,ArrayList<JSONObject> values){if(values!=null){int start=Math.max(0,values.size()-100);Page page=new Page(values.subList(start,values.size()));synchronized(ConversationCache.class){pages.put(jid,page);}}}
 static synchronized String name(String jid){if(chats!=null)for(int i=0;i<chats.length();i++){JSONObject item=chats.optJSONObject(i);if(item!=null&&jid.equals(item.optString("sender")))return item.optString("senderName","");}return "";}
 static synchronized boolean recent(){return chats!=null&&android.os.SystemClock.elapsedRealtime()-refreshedAt<1000;}
 static void refresh(Context context){
  LocalStore store=LocalStore.get(context);JSONArray list=store.getChats(),names=store.getContactNames(),history=store.getCalls();
  synchronized(ConversationCache.class){chats=list;contacts=names;calls=history;refreshedAt=android.os.SystemClock.elapsedRealtime();}
  warmRecent(context,list);
 }
 static synchronized void publishContacts(JSONArray data){contacts=data;}
 static void prefetch(final Context context,final String jid){
  if(jid==null||jid.length()==0||jid.startsWith("local:"))return;
  synchronized(ConversationCache.class){if(pages.get(jid)!=null||!pending.add(jid))return;}
  final Context app=context.getApplicationContext();AppExecutors.PRELOAD.execute(new Runnable(){public void run(){try{warmOne(app,jid);}catch(Exception ignored){}finally{synchronized(ConversationCache.class){pending.remove(jid);}}}});
 }
 private static void warmOne(Context context,String jid){
  long stamp;synchronized(ConversationCache.class){if(pages.get(jid)!=null)return;stamp=revision;}
  ArrayList<JSONObject> values=LocalStore.get(context).getMessages(jid,100,Long.MAX_VALUE);
  Page page=new Page(values);synchronized(ConversationCache.class){if(stamp==revision)pages.put(jid,page);}
 }
 private static void warmRecent(Context context,final JSONArray list){
  synchronized(ConversationCache.class){if(warming)return;warming=true;}
  final Context app=context.getApplicationContext();AppExecutors.PRELOAD.execute(new Runnable(){public void run(){try{for(int i=0;i<Math.min(8,list.length());i++){JSONObject item=list.optJSONObject(i);if(item!=null)warmOne(app,item.optString("sender"));}}catch(Exception ignored){}finally{synchronized(ConversationCache.class){warming=false;}}}});
 }
}

