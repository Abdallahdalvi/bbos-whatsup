package com.aghani.q20messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Durable local message store. Server IDs are unique so sync batches are safe to replay. */
public final class LocalStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "q20-local.db";
    private static final int DB_VERSION = 5;
    private static LocalStore instance;

    public static synchronized LocalStore get(Context context) {
        if (instance == null) { ConversationCache.clear(); instance = new LocalStore(context.getApplicationContext()); }
        return instance;
    }

    private LocalStore(Context context) { super(context, DB_NAME, null, DB_VERSION); setWriteAheadLoggingEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats (chat_jid TEXT PRIMARY KEY, display_name TEXT NOT NULL, is_group INTEGER NOT NULL DEFAULT 0, last_message TEXT, last_timestamp INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0, phone_jid TEXT)");
        db.execSQL("CREATE TABLE messages (local_id INTEGER PRIMARY KEY AUTOINCREMENT, server_id INTEGER NOT NULL UNIQUE, wa_message_id TEXT, chat_jid TEXT NOT NULL, sender_jid TEXT, receiver_jid TEXT, participant_jid TEXT, sender_name TEXT, body TEXT, message_type TEXT, timestamp INTEGER NOT NULL DEFAULT 0, status TEXT, from_me INTEGER NOT NULL DEFAULT 0, media_filename TEXT, media_original_filename TEXT, mime_type TEXT, media_size INTEGER NOT NULL DEFAULT 0, media_download_status TEXT, reply_to_id TEXT, link_url TEXT, rich_json TEXT)");
        db.execSQL("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_local_messages_chat_time ON messages(chat_jid,timestamp DESC,server_id DESC)");
        db.execSQL("CREATE INDEX idx_local_messages_time ON messages(timestamp DESC,server_id DESC)");
        createVersionFive(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN sender_name TEXT");
        if (oldVersion < 3) db.execSQL("ALTER TABLE chats ADD COLUMN phone_jid TEXT");
        if (oldVersion < 4) db.execSQL("ALTER TABLE messages ADD COLUMN rich_json TEXT");
        if (oldVersion < 5) createVersionFive(db);
    }

    private void createVersionFive(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_unread ON messages(chat_jid,from_me,server_id,timestamp)");
        db.execSQL("CREATE TABLE IF NOT EXISTS call_history (call_id TEXT PRIMARY KEY,peer_jid TEXT NOT NULL,display_name TEXT,direction TEXT,started_at INTEGER,connected_at INTEGER,ended_at INTEGER,outcome TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_names (jid TEXT PRIMARY KEY,name TEXT NOT NULL)");
    }

    private void mergeChat(SQLiteDatabase db, ContentValues values) {
        String jid = values.getAsString("chat_jid");
        Cursor contact = db.rawQuery("SELECT name FROM contact_names WHERE jid=? OR jid=? ORDER BY CASE WHEN jid=? THEN 0 ELSE 1 END LIMIT 1",new String[]{jid,values.getAsString("phone_jid") == null ? "" : values.getAsString("phone_jid"),jid});
        try { if(contact.moveToFirst()) values.put("display_name",contact.getString(0)); } finally { contact.close(); }
        long timestamp = values.getAsLong("last_timestamp");
        if (db.insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0) return;
        // Older history may update its message rows, but cannot move a chat backwards.
        db.update("chats", values, "chat_jid=? AND last_timestamp<=?", new String[]{jid, String.valueOf(timestamp)});
        ContentValues metadata = new ContentValues();
        String name = values.getAsString("display_name");
        if (!isPlaceholderName(name, jid)) metadata.put("display_name", name);
        String phone = values.getAsString("phone_jid");
        if (phone != null && phone.length() > 0) metadata.put("phone_jid", phone);
        if (metadata.size() > 0) db.update("chats", metadata, "chat_jid=?", new String[]{jid});
    }

    public int upsertMessages(JSONArray batch) {
        SQLiteDatabase db = getWritableDatabase();
        int imported = 0;
        java.util.HashSet<String> changed=new java.util.HashSet<String>();
        db.beginTransaction();
        try {
            for (int i = 0; i < batch.length(); i++) {
                JSONObject item = batch.optJSONObject(i);
                if (item == null) continue;
                long serverId = item.optLong("_id", -1);
                String chatJid = resolveChatJid(item.optString("chat_jid", ""));
                if (serverId < 0 || chatJid.length() == 0) continue;
                boolean fromMe = item.optBoolean("from_me", false);
                ContentValues values = new ContentValues();
                values.put("server_id", serverId);
                values.put("wa_message_id", item.optString("whatsapp_message_id", null));
                values.put("chat_jid", chatJid);
                values.put("sender_jid", item.optString("sender", ""));
                values.put("receiver_jid", item.optString("receiver", ""));
                values.put("participant_jid", item.optString("participant_jid", ""));
                values.put("sender_name", item.optString("sender_name", ""));
                values.put("body", item.optString("message", ""));
                values.put("message_type", item.optString("chat_type", "chat"));
                values.put("timestamp", item.optLong("timestamp_ms", 0));
                values.put("status", item.optString("status", ""));
                values.put("from_me", fromMe ? 1 : 0);
                values.put("media_filename", item.optString("media_filename", ""));
                values.put("media_original_filename", item.optString("media_original_filename", ""));
                values.put("mime_type", item.optString("media_mimetype", ""));
                values.put("media_size", item.optLong("media_filesize", 0));
                values.put("media_download_status", item.optString("media_download_status", "remote"));
                values.put("reply_to_id", item.optString("reply_to_id", ""));
                values.put("link_url", item.optString("link_url", ""));
                values.put("rich_json", item.optJSONObject("rich") == null ? "{}" : item.optJSONObject("rich").toString());
                long inserted = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                if (inserted < 0) continue;
                imported++;changed.add(chatJid);

                String title = item.optString("chat_name", chatJid);
                String savedName = getSavedChatName(db, chatJid);
                if (isPlaceholderName(title, chatJid)) {
                    title = !isPlaceholderName(savedName, chatJid) ? savedName
                            : (chatJid.endsWith("@g.us") ? "WhatsApp group"
                            : (chatJid.endsWith("@newsletter") ? "Channel" : "WhatsApp contact"));
                }
                ContentValues chat = new ContentValues();
                chat.put("chat_jid", chatJid);
                chat.put("display_name", title.length() == 0 ? chatJid : title);
                chat.put("is_group", chatJid.endsWith("@g.us") ? 1 : 0);
                chat.put("last_message", item.optString("message", ""));
                chat.put("last_timestamp", item.optLong("timestamp_ms", 0));
                chat.put("phone_jid", getSavedPhoneJid(db, chatJid));
                mergeChat(db, chat);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        ConversationCache.invalidateMessages(changed);
        return imported;
    }

    public void upsertChatSummaries(JSONArray chats) {
        if (chats == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < chats.length(); i++) {
                JSONObject item = chats.optJSONObject(i);
                if (item == null) continue;
                String rawJid = item.optString("chat_jid", "");
                String jid = resolveChatJid(rawJid);
                if (jid.length() == 0) continue;
                String name = item.optString("name", "").trim();
                if (isPlaceholderName(name, jid)) {
                    String savedName = getSavedChatName(db, jid);
                    if (!isPlaceholderName(savedName, jid)) name = savedName;
                }
                if (isPlaceholderName(name, jid)) {
                    name = jid.endsWith("@g.us") ? "WhatsApp group"
                            : (jid.endsWith("@newsletter") ? "Channel" : "WhatsApp contact");
                }
                ContentValues values = new ContentValues();
                values.put("chat_jid", jid);
                values.put("display_name", name);
                String phone = item.optString("phone_jid", "");
                if (phone.length() == 0 && !jid.equals(rawJid)) phone = rawJid;
                if (phone.length() == 0) phone = getSavedPhoneJid(db,jid);
                values.put("phone_jid", phone);
                values.put("is_group", item.optBoolean("is_group", jid.endsWith("@g.us")) ? 1 : 0);
                values.put("last_message", item.optString("last_message", ""));
                values.put("last_timestamp", item.optLong("timestamp_ms", 0));
                if (jid.endsWith("@lid") && phone.length() > 0 && !phone.equals(jid)) {
                    Cursor old = db.rawQuery("SELECT last_timestamp,last_message FROM chats WHERE chat_jid=?",new String[]{phone});
                    try { if(old.moveToFirst() && old.getLong(0) > values.getAsLong("last_timestamp")) { values.put("last_timestamp",old.getLong(0)); values.put("last_message",old.getString(1)); } } finally {old.close();}
                    db.execSQL("UPDATE messages SET chat_jid=? WHERE chat_jid=?",new Object[]{jid,phone});
                    putState("read:"+jid,Math.max(getState("read:"+jid),getState("read:"+phone)));
                    putState("read_time:"+jid,Math.max(getState("read_time:"+jid),getState("read_time:"+phone)));
                    db.delete("chats","chat_jid=?",new String[]{phone});
                }
                mergeChat(db, values);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Apply resolved phone-contact names to matching chats, including chats stored under a linked LID. */
    public void upsertContactNames(JSONArray contacts) {
        if (contacts == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < contacts.length(); i++) {
                JSONObject contact = contacts.optJSONObject(i);
                if (contact == null) continue;
                String jid = contact.optString("id", "").trim();
                String name = contact.optString("name", "").trim();
                if (jid.length() == 0 || isPlaceholderName(name, jid)) continue;
                ContentValues cached = new ContentValues(); cached.put("jid",jid); cached.put("name",name);
                db.insertWithOnConflict("contact_names",null,cached,SQLiteDatabase.CONFLICT_REPLACE);
                ContentValues update = new ContentValues();
                update.put("display_name", name);
                db.update("chats", update, "chat_jid=? OR phone_jid=?", new String[] { jid, jid });
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static boolean isPlaceholderName(String name, String jid) {
        return name == null || name.length() == 0 || name.equalsIgnoreCase(jid)
                || name.contains("@") || name.matches("\\+?[0-9][0-9 ().-]{5,}")
                || "WhatsApp contact".equalsIgnoreCase(name) || "WhatsApp group".equalsIgnoreCase(name)
                || "Unknown contact".equalsIgnoreCase(name);
    }

    private static String getSavedChatName(SQLiteDatabase db, String jid) {
        Cursor c = db.rawQuery("SELECT display_name FROM chats WHERE chat_jid=?", new String[] { jid });
        try { return c.moveToFirst() ? c.getString(0) : ""; }
        finally { c.close(); }
    }

    private static String getSavedPhoneJid(SQLiteDatabase db, String jid) {
        Cursor c = db.rawQuery("SELECT phone_jid FROM chats WHERE chat_jid=?", new String[] { jid });
        try { return c.moveToFirst() ? c.getString(0) : ""; }
        finally { c.close(); }
    }

    public void markChatRead(String chatJid) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(server_id),0),COALESCE(MAX(timestamp),0) FROM messages WHERE chat_jid=?",
                new String[] { chatJid });
        long lastId = 0;
        long lastTime = 0;
        try { if (c.moveToFirst()) { lastId = c.getLong(0); lastTime = c.getLong(1); } }
        finally { c.close(); }
        ContentValues values = new ContentValues();
        values.put("key", "read:" + chatJid);
        values.put("value", String.valueOf(lastId));
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        putState("read_time:" + chatJid, lastTime);
    }

    public int getUnreadCount(String chatJid) {
        Cursor read = getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key=?",
                new String[] { "read:" + chatJid });
        long lastRead = 0;
        try { if (read.moveToFirst()) lastRead = Long.parseLong(read.getString(0)); }
        catch (Exception ignored) { }
        finally { read.close(); }
        Cursor count = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE chat_jid=? AND server_id>? AND from_me=0 AND timestamp>=?",
                new String[] { chatJid, String.valueOf(lastRead),String.valueOf(getState("read_time:"+chatJid)) });
        try { return count.moveToFirst() ? count.getInt(0) : 0; }
        finally { count.close(); }
    }

    public void initializeUnreadCursors() {
        Cursor state = getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key='unread_initialized'", null);
        boolean initialized;
        try { initialized = state.moveToFirst() && "1".equals(state.getString(0)); }
        finally { state.close(); }
        if (initialized) return;
        Cursor chats = getReadableDatabase().rawQuery("SELECT chat_jid FROM chats", null);
        try {
            while (chats.moveToNext()) markChatRead(chats.getString(0));
        } finally { chats.close(); }
        ContentValues values = new ContentValues();
        values.put("key", "unread_initialized");
        values.put("value", "1");
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public JSONArray getChats() {
        JSONArray rows = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery("SELECT c.chat_jid,c.display_name,c.is_group,substr(c.last_message,1,256),c.last_timestamp,(SELECT COUNT(*) FROM messages m WHERE m.chat_jid=c.chat_jid AND m.from_me=0 AND m.server_id>COALESCE(CAST(r.value AS INTEGER),0) AND m.timestamp>=COALESCE(CAST(rt.value AS INTEGER),0)),c.phone_jid FROM chats c LEFT JOIN sync_state r ON r.key='read:'||c.chat_jid LEFT JOIN sync_state rt ON rt.key='read_time:'||c.chat_jid ORDER BY c.last_timestamp DESC,c.rowid DESC", null);
        try {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("sender", c.getString(0));
                row.put("senderName", c.getString(1));
                row.put("is_group", c.getInt(2) != 0);
                row.put("message", c.getString(3));
                row.put("createdAtMs", c.getLong(4));
                row.put("unread_count", c.getInt(5));
                row.put("phone_jid", c.getString(6));
                rows.put(row);
            }
        } catch (Exception ignored) { }
        finally { c.close(); }
        return rows;
    }

    public ArrayList<JSONObject> getMessages(String chatJid, int limit, long beforeTimestamp) {
        return getMessages(chatJid,limit,beforeTimestamp,Long.MAX_VALUE);
    }
    public ArrayList<JSONObject> getMessages(String chatJid,int limit,long beforeTimestamp,long beforeId) {
        ArrayList<JSONObject> result = new ArrayList<JSONObject>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT server_id,wa_message_id,chat_jid,sender_jid,receiver_jid,participant_jid,sender_name,body,message_type,timestamp,status,from_me,media_filename,media_original_filename,mime_type,media_size,media_download_status,reply_to_id,link_url,rich_json FROM messages WHERE chat_jid=? AND (timestamp<? OR (timestamp=? AND server_id<?)) ORDER BY timestamp DESC,server_id DESC LIMIT ?",
                new String[] { chatJid, String.valueOf(beforeTimestamp), String.valueOf(beforeTimestamp), String.valueOf(beforeId), String.valueOf(limit) });
        try {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("_id", c.getLong(0)); row.put("whatsapp_message_id", c.getString(1));
                row.put("chat_jid", c.getString(2)); row.put("sender", c.getString(3));
                row.put("receiver", c.getString(4)); row.put("participant_jid", c.getString(5));
                row.put("sender_name", c.getString(6)); row.put("message", c.getString(7)); row.put("chatType", c.getString(8));
                row.put("timestamp_ms", c.getLong(9)); row.put("status", c.getString(10));
                row.put("from_me", c.getInt(11) != 0); row.put("media_filename", c.getString(12));
                row.put("media_original_filename", c.getString(13)); row.put("media_mimetype", c.getString(14));
                row.put("media_filesize", c.getLong(15)); row.put("media_download_status", c.getString(16));
                row.put("reply_to_id", c.getString(17)); row.put("link_url", c.getString(18));
                try { row.put("rich", new JSONObject(c.getString(19) == null ? "{}" : c.getString(19))); } catch (Exception ignoredRich) { }
                result.add(0, row);
            }
        } catch (Exception ignored) { }
        finally { c.close(); }
        return result;
    }

    public void putTextState(String key,String value) { ContentValues entry=new ContentValues();entry.put("key",key);entry.put("value",value);getWritableDatabase().insertWithOnConflict("sync_state",null,entry,SQLiteDatabase.CONFLICT_REPLACE); }
    public String getTextState(String key) { Cursor c=getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key=?",new String[]{key});try{return c.moveToFirst()?c.getString(0):"";}finally{c.close();} }

    public JSONArray getContactNames(){JSONArray data=new JSONArray();Cursor c=getReadableDatabase().rawQuery("SELECT jid,name FROM contact_names ORDER BY name COLLATE NOCASE",null);try{while(c.moveToNext())try{data.put(new JSONObject().put("id",c.getString(0)).put("name",c.getString(1)));}catch(Exception ignored){}}finally{c.close();}return data;}
    public long getSyncCursor() {
        Cursor c = getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key='server_message_id'", null);
        try { return c.moveToFirst() ? Long.parseLong(c.getString(0)) : 0; }
        catch (Exception e) { return 0; }
        finally { c.close(); }
    }

    public void saveSyncCursor(long cursor) {
        ContentValues values = new ContentValues(); values.put("key", "server_message_id"); values.put("value", String.valueOf(cursor));
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean isBootstrapComplete() {
        Cursor c = getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key='bootstrap_complete'", null);
        try { return c.moveToFirst() && "1".equals(c.getString(0)); } finally { c.close(); }
    }

    public long getState(String key) {
        Cursor c = getReadableDatabase().rawQuery("SELECT value FROM sync_state WHERE key=?", new String[]{key});
        try { return c.moveToFirst() ? Long.parseLong(c.getString(0)) : 0; } catch (Exception ignored) { return 0; } finally { c.close(); }
    }
    public void putState(String key, long value) {
        ContentValues values = new ContentValues(); values.put("key",key); values.put("value",String.valueOf(value));
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public String resolveChatJid(String jid) {
        if (jid == null || jid.length() == 0) return "";
        Cursor c = getReadableDatabase().rawQuery("SELECT chat_jid FROM chats WHERE phone_jid=? AND chat_jid LIKE '%@lid' LIMIT 1",new String[]{jid});
        try { return c.moveToFirst() ? c.getString(0) : jid; } finally { c.close(); }
    }
    public String getChatName(String jid) {
        String canonical=resolveChatJid(jid);
        Cursor c=getReadableDatabase().rawQuery("SELECT display_name FROM chats WHERE chat_jid=? OR phone_jid=? LIMIT 1",new String[]{canonical,jid});
        try { if(c.moveToFirst() && !isPlaceholderName(c.getString(0),jid))return c.getString(0); } finally {c.close();}
        Cursor names=getReadableDatabase().rawQuery("SELECT name FROM contact_names WHERE jid=?",new String[]{jid});
        try {return names.moveToFirst()?names.getString(0):"WhatsApp contact";}finally{names.close();}
    }
    public void finishMissedCall(String id, String outcome) {
        ContentValues values=new ContentValues();values.put("outcome",outcome);values.put("ended_at",System.currentTimeMillis());
        getWritableDatabase().update("call_history",values,"call_id=? AND connected_at=0",new String[]{id});
    }
    public void saveCall(String id, String peer, String name, String direction, long started, long connected, long ended, String outcome) {
        ContentValues values = new ContentValues(); values.put("call_id",id); values.put("peer_jid",peer); values.put("display_name",name); values.put("direction",direction);
        values.put("started_at",started); values.put("connected_at",connected); values.put("ended_at",ended); values.put("outcome",outcome);
        getWritableDatabase().insertWithOnConflict("call_history",null,values,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public JSONArray getCalls() {
        JSONArray result = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery("SELECT call_id,peer_jid,display_name,direction,started_at,connected_at,ended_at,outcome FROM call_history ORDER BY started_at DESC LIMIT 200",null);
        try { while(c.moveToNext()) { JSONObject item=new JSONObject(); item.put("call_id",c.getString(0)); item.put("peer_jid",c.getString(1)); item.put("name",c.getString(2)); item.put("direction",c.getString(3)); item.put("started",c.getLong(4)); item.put("connected",c.getLong(5)); item.put("ended",c.getLong(6)); item.put("outcome",c.getString(7)); result.put(item); } } catch(Exception ignored) {} finally { c.close(); }
        return result;
    }

    public void setBootstrapComplete(boolean complete) {
        ContentValues values = new ContentValues(); values.put("key", "bootstrap_complete"); values.put("value", complete ? "1" : "0");
        getWritableDatabase().insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int countMessages() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public int countChats() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM chats", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }
}
