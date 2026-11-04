package com.aghani.q20messenger;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import org.json.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=19)
public class ConversationStateTest {
 private LocalStore store;
 @Before public void reset(){org.robolectric.util.ReflectionHelpers.setStaticField(LocalStore.class,"instance",null);store=LocalStore.get(RuntimeEnvironment.getApplication());for(String table:new String[]{"messages","chats","sync_state","contact_names","call_history"})store.getWritableDatabase().delete(table,null,null);}
 private JSONObject message(int id,String jid,long time,String text)throws Exception{return new JSONObject().put("_id",id).put("chat_jid",jid).put("chat_name","Profile").put("timestamp_ms",time).put("message",text).put("chat_type","chat").put("from_me",false);}
 private void put(JSONObject message){store.upsertMessages(new JSONArray().put(message));}
 @Test public void newMessageSortsFirstAndOldHistoryCannotUndoUnreadOrTimestamp()throws Exception{
  put(message(1,"a@c.us",100,"old a"));put(message(2,"b@c.us",200,"old b"));store.initializeUnreadCursors();
  JSONObject fresh=message(3,"a@c.us",300,"new a");put(fresh);put(fresh);
  put(message(4,"a@c.us",50,"imported older history"));
  JSONObject top=store.getChats().getJSONObject(0);assertEquals("a@c.us",top.getString("sender"));assertEquals(300,top.getLong("createdAtMs"));assertEquals("new a",top.getString("message"));assertEquals(1,top.getInt("unread_count"));
  store.markChatRead("a@c.us");assertEquals(0,store.getChats().getJSONObject(0).getInt("unread_count"));
 }
 @Test public void phoneAndLidShareHistorySavedNameAndUnreadCursor()throws Exception{
  store.upsertContactNames(new JSONArray("[{id:'1001@c.us',name:'Ada saved name'}]"));
  put(message(1,"1001@c.us",100,"phone history"));store.markChatRead("1001@c.us");
  store.upsertChatSummaries(new JSONArray("[{chat_jid:'9001@lid',phone_jid:'1001@c.us',name:'Profile name',timestamp_ms:50,last_message:'older summary'}]"));
  assertEquals("9001@lid",store.resolveChatJid("1001@c.us"));assertEquals(1,store.getChats().length());assertEquals("Ada saved name",store.getChatName("9001@lid"));
  put(message(2,"1001@c.us",200,"new phone-addressed message"));assertEquals(2,store.getMessages("9001@lid",100,Long.MAX_VALUE).size());assertEquals(1,store.getChats().getJSONObject(0).getInt("unread_count"));
 }
 @Test public void callHistoryRetainsCompletedAndMissedCalls()throws Exception{
  store.saveCall("complete","1001@c.us","Ada","Outgoing",100,110,300,"completed");store.finishMissedCall("complete","missed");
  store.saveCall("ring","1002@c.us","Bob","Incoming",400,0,0,"ringing");store.finishMissedCall("ring","missed");
  JSONArray calls=store.getCalls();assertEquals(2,calls.length());assertEquals("missed",calls.getJSONObject(0).getString("outcome"));assertEquals("completed",calls.getJSONObject(1).getString("outcome"));
 }
 @Test public void versionFourDatabaseUpgradePreservesMessages()throws Exception{
  store.close();org.robolectric.util.ReflectionHelpers.setStaticField(LocalStore.class,"instance",null);
  android.content.Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("q20-local.db");
  android.database.sqlite.SQLiteDatabase old=context.openOrCreateDatabase("q20-local.db",0,null);old.execSQL("CREATE TABLE messages(server_id INTEGER,chat_jid TEXT,from_me INTEGER,timestamp INTEGER)");old.execSQL("INSERT INTO messages VALUES(1,'a@c.us',0,100)");old.setVersion(4);old.close();
  LocalStore migrated=LocalStore.get(context);assertEquals(1,migrated.countMessages());assertEquals(0,migrated.getCalls().length());assertEquals(5,migrated.getReadableDatabase().getVersion());
 }
 @Test public void controlRecordsDoNotConsumeMessagePagesOrUnreadBadges()throws Exception {
  put(message(1,"a@c.us",100,"first"));put(message(2,"a@c.us",200,"second"));
  put(message(3,"a@c.us",300,"e2e_notification received").put("chat_type","e2e_notification"));put(message(4,"a@c.us",400,"protocol received").put("chat_type","protocol"));
  java.util.ArrayList<JSONObject> page=store.getMessages("a@c.us",2,Long.MAX_VALUE);assertEquals(2,page.size());assertEquals("first",page.get(0).getString("message"));assertEquals("second",page.get(1).getString("message"));assertEquals(2,store.getUnreadCount("a@c.us"));assertEquals(4,store.countMessages());
 }
 @Test public void templatesKeepAvailableTextAndControlsHaveReadableLabels()throws Exception {
  assertEquals("Order shipped",MessageContent.body("notification_template","notification_template received",new JSONObject("{caption:'Order shipped'}")));
  assertEquals("Group updated",MessageContent.body("gp2","gp2 received",null));assertEquals("Hello",MessageContent.body("chat","Hello",null));assertEquals("",MessageContent.preview(""));assertTrue(MessageContent.body("ciphertext","ciphertext received",null).startsWith("Waiting"));
 }}

