package com.aghani.q20messenger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Resumable, bounded-memory synchronization. Each 200-row page is committed before fetching the next. */
public final class SyncCoordinator {
    public interface ProgressListener { void onProgress(String message); }

    public static final class Result {
        public String accountName = "";
        public String error;
        public String historyProgress;
        public boolean bootstrapped;
        public int serverTotalMessages;
        public int serverTotalChats;
        public int localMessages;
        public int localChats;
    }

    static String describeHistory(JSONObject history) {
        if(history.optBoolean("running",false))return "Importing history · "+history.optInt("conversations_done")+"/"+history.optInt("conversations_total")+" chats · "+history.optInt("messages_imported")+" messages";
        if("waiting_for_phone".equals(history.optString("phase")))return "Waiting for older messages from phone · "+history.optInt("waiting_chats")+" chats";
        if(history.optInt("errors")>0)return "History will resume · "+history.optInt("errors")+" chats need retry";
        return "";
    }
    private static volatile long finishedAt;
    static long lastFinishedAt(){return finishedAt;}
    private SyncCoordinator() { }

    public static synchronized Result sync(Context context, ApiClient api, ProgressListener progress) {
        Result result = new Result();
        LocalStore store = LocalStore.get(context);
        try {
            if(AppPrefs.backgroundEnabled(context))MessageNotifications.prepare(context);
            JSONObject health = api.getHealth();
            if (!health.optBoolean("connected", false)) throw new IllegalStateException("WhatsApp session is not connected on the server");
            JSONObject account = api.getAccount();
            result.accountName = account.optJSONObject("account") == null ? "WhatsApp account"
                    : account.optJSONObject("account").optString("pushname", "WhatsApp account");

            if (!store.isBootstrapComplete()) {
                long cursor = store.getSyncCursor();
                long snapshot = -1;
                boolean more = true;
                while (more) {
                    JSONObject page = api.getBootstrap(cursor, 200, snapshot);
                    if (snapshot < 0) {
                        snapshot = page.optLong("snapshot_max_id", cursor);
                        result.serverTotalMessages = page.optInt("total_messages", 0);
                        result.serverTotalChats = page.optInt("total_chats", 0);
                    }
                    JSONArray messages = page.optJSONArray("messages");
                    if (messages != null) store.upsertMessages(messages);
                    long next = page.optLong("next_after_id", cursor);
                    if (next > cursor) {
                        cursor = next;
                        store.saveSyncCursor(cursor);
                    }
                    more = page.optBoolean("has_more", false);
                    if (progress != null) progress.onProgress("Importing server history: " + store.countMessages()
                            + " messages, " + store.countChats() + " conversations");
                    if (messages == null || messages.length() == 0) break;
                }
                store.setBootstrapComplete(true);
                result.bootstrapped = true;
            }

            boolean moreUpdates = true;
            while (moreUpdates) {
                long cursor = store.getSyncCursor();
                JSONObject page = api.getUpdates(cursor, 200);
                JSONArray messages = page.optJSONArray("messages");
                if (messages != null) store.upsertMessages(messages);
                long next = page.optLong("next_after_id", cursor);
                if (next > cursor) store.saveSyncCursor(next);
                moreUpdates = page.optBoolean("has_more", false);
                if (messages == null || messages.length() == 0) break;
                if (progress != null) progress.onProgress("Syncing new messages...");
            }

            if(progress!=null)progress.onProgress("Messages synchronized");
            // Resolve contact names before fetching summaries. A first sync can otherwise
            // persist generic placeholders locally and keep showing them after server repair.
            boolean refreshContacts = System.currentTimeMillis() - store.getState("contacts_sync_at") > 900000;
            int contactPage = 0;
            boolean moreContacts = refreshContacts;
            while (moreContacts && contactPage < 1000) {
                JSONObject contactResult = api.getContacts("", contactPage++);
                JSONArray contacts = contactResult.optJSONArray("contacts");
                if (contacts == null || contacts.length() == 0) break;
                store.upsertContactNames(contacts);
                moreContacts = contactResult.optBoolean("has_more", false);
            }
            if(refreshContacts) store.putState("contacts_sync_at",System.currentTimeMillis());

            if(System.currentTimeMillis()-store.getState("summaries_sync_at")>10000) {
                JSONObject chatSummary = api.getChats();
                store.upsertChatSummaries(chatSummary.optJSONArray("chats"));
                store.putState("summaries_sync_at",System.currentTimeMillis());
            }
            store.initializeUnreadCursors();
            // Import the linked session, not just the messages already recorded by this server.
            if(System.currentTimeMillis()-store.getState("history_checked_at")>15000) {
                try {
                    JSONObject history=api.getHistoryImportStatus();
                    store.putState("history_checked_at",System.currentTimeMillis());
                    long lastRequest=store.getState("history_requested_at");
                    boolean running=history.optBoolean("running",false);
                    long retry=history.optBoolean("completed",false)?21600000L:300000L;
                    if(!running && ("idle".equals(history.optString("phase")) || System.currentTimeMillis()-lastRequest>retry)) {
                        JSONObject started=api.startHistoryImport();
                        store.putState("history_requested_at",System.currentTimeMillis());
                        history=started.optJSONObject("status")==null?history:started.optJSONObject("status");
                    }
                    store.putState("history_running",history.optBoolean("running",false)?1:0);
                    result.historyProgress=describeHistory(history);
                    store.putTextState("history_progress",result.historyProgress);
                } catch(Exception historyError) { result.historyProgress="History retry pending: "+historyError.getMessage(); }
            } else result.historyProgress=store.getTextState("history_progress");
        } catch (Exception error) {
            result.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        result.localMessages = store.countMessages();
        result.localChats = store.countChats();
        finishedAt=android.os.SystemClock.elapsedRealtime();
        try{ConversationCache.refresh(context);if(store.isBootstrapComplete())MessageNotifications.afterSync(context);}catch(Exception ignored){}
        return result;
    }
}
