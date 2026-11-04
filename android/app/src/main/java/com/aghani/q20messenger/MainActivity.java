package com.aghani.q20messenger;

import android.support.v7.app.AppCompatActivity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.support.v7.widget.PopupMenu;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends BaseActivity {
    private static final int TAB_CHATS = 0;
    private static final int TAB_GROUPS = 1;
    private static final int TAB_CONTACTS = 2;
    private static final int TAB_CALLS = 3;
    private static final int[] AVATAR_COLORS = new int[] {
            0xff128c7e, 0xff075e54, 0xff34b7f1, 0xff8bc34a, 0xff607d8b, 0xff795548
    };

    private final Handler handler = new Handler();
    private final ArrayList<JSONObject> rows = new ArrayList<JSONObject>();
    private final ArrayList<JSONObject> cachedRows = new ArrayList<JSONObject>();
    private LocalStore store;
    private ListView list;
    private TextView status;
    private EditText search;
    private RowAdapter adapter;
    private int currentTab = TAB_CHATS;
    private int navigationPosition=-1;
    private String navigationIdentity="";
    private boolean tabNavigationFocused;
    private boolean syncing;
    private long lastUiRefresh;
    private boolean contactsLoading;
    private boolean localReading;
    private int cachedTab=-1;
    private JSONArray contacts = new JSONArray();
    private final Runnable poller = new Runnable() {
        public void run() {
            if (!isFinishing()) {
                synchronize(false);
                handler.postDelayed(this, 5000);
            }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = LocalStore.get(this);
        buildScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poller);
        if (AppPrefs.getPhoneNumber(this).length() == 0 || AppPrefs.getApiToken(this).length() == 0) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            loadLocalChats();
            if(AppPrefs.backgroundEnabled(this))startService(new Intent(this, IncomingCallService.class));
            synchronize(cachedRows.isEmpty());
            handler.postDelayed(poller, 3000);

        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(poller);
        super.onPause();
    }
    @Override public void onBackPressed(){
        if(AppPrefs.backgroundEnabled(this)){
            startService(new Intent(this,IncomingCallService.class));
            if(moveTaskToBack(true))return;
        }
        super.onBackPressed();
    }

    private void buildScreen() {
        setContentView(R.layout.activity_main);
        if(BuildConfig.STAGING_BUILD)((TextView)findViewById(R.id.title)).setText("Q20 TEST · 8035");
        status = (TextView) findViewById(R.id.status);
        list = (ListView) findViewById(R.id.conversation_list);
        search = (EditText) findViewById(R.id.search_field);
        TextView empty = (TextView) findViewById(R.id.empty_state);
        list.setEmptyView(empty);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnHoverListener(new View.OnHoverListener(){public boolean onHover(View view,android.view.MotionEvent event){if(event.getAction()==android.view.MotionEvent.ACTION_HOVER_MOVE){int position=list.pointToPosition((int)event.getX(),(int)event.getY());if(position>=0&&position!=navigationPosition)selectForNavigation(position,false);}return false;}});
        list.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDrawSelectorOnTop(false);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                JSONObject row = rows.get(position);navigationPosition=position;navigationIdentity=rowIdentity(row);
                if(currentTab == TAB_CALLS) { showCallActions(row); return; }
                String jid = row.optString(currentTab == TAB_CONTACTS ? "id" : "sender", "");
                String name = row.optString("senderName", row.optString("name", jid));
                openChat(jid, name);
            }
        });

        findViewById(R.id.tab_chats).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { setTab(TAB_CHATS); }
        });
        findViewById(R.id.tab_groups).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { setTab(TAB_GROUPS); }
        });
        findViewById(R.id.tab_contacts).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { setTab(TAB_CONTACTS); }
        });
        findViewById(R.id.tab_calls).setOnClickListener(new View.OnClickListener(){public void onClick(View view){setTab(TAB_CALLS);}});
        findViewById(R.id.search_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                search.setVisibility(search.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                if (search.getVisibility() == View.VISIBLE) search.requestFocus();
                applyFilter();
            }
        });
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        findViewById(R.id.menu_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                PopupMenu menu = new PopupMenu(MainActivity.this, view);
                menu.getMenu().add("Refresh").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(android.view.MenuItem item) { store.putState("contacts_sync_at",0);store.putState("summaries_sync_at",0); synchronize(true); return true; }
                });
                menu.getMenu().add("Connection settings").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(android.view.MenuItem item) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class)); return true;
                    }
                });
                menu.show();
            }
        });
        findViewById(R.id.new_chat).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { setTab(TAB_CONTACTS); }
        });
        ((android.widget.ImageButton)findViewById(R.id.search_button)).setColorFilter(getResources().getColor(R.color.ink));
        ((android.widget.ImageButton)findViewById(R.id.menu_button)).setColorFilter(getResources().getColor(R.color.ink));
        ((android.widget.ImageButton)findViewById(R.id.new_chat)).setColorFilter(0xff0b141a);
        setTab(TAB_CHATS);
    }

    private void setTab(int tab) {
        if(currentTab!=tab)cachedTab=-1;
        currentTab = tab;navigationPosition=-1;navigationIdentity="";tabNavigationFocused=false;
        int selected = getResources().getColor(R.color.green_dark);
        int idle = getResources().getColor(R.color.ink);
        int[] ids={R.id.tab_chats,R.id.tab_groups,R.id.tab_contacts,R.id.tab_calls};
        for(int i=0;i<ids.length;i++){Button button=(Button)findViewById(ids[i]);button.setFocusableInTouchMode(true);button.setBackgroundDrawable(Ui.tabBackground(this,i==tab));button.setTextColor(i==tab?selected:idle);button.setAlpha(1f);button.setTextSize(12);button.setIncludeFontPadding(false);button.setPadding(0,0,0,0);button.setCompoundDrawables(null,null,null,null);}
        search.setText("");
        list.requestFocus();
        if (tab == TAB_CONTACTS) loadContacts(); else loadLocalChats();
    }

    private void loadContacts() {
        JSONArray saved=ConversationCache.contacts();if(saved!=null){contacts=saved;applyFilter();}
        if(saved==null)AppExecutors.DATABASE.execute(new Runnable(){public void run(){final JSONArray names=store.getContactNames();handler.post(new Runnable(){public void run(){if(contacts.length()==0&&currentTab==TAB_CONTACTS){contacts=names;applyFilter();}}});}});
        if (contactsLoading) return;
        contactsLoading = true;
        status.setText("Loading contacts from the linked WhatsApp account…");
        new AsyncTask<Void, Void, JSONObject>() {
            protected JSONObject doInBackground(Void... params) {
                try {
                    ApiClient api = new ApiClient(MainActivity.this);
                    JSONArray all = new JSONArray();
                    int page = 0;
                    boolean more = true;
                    while (more && page < 1000) {
                        JSONObject response = api.getContacts("", page++);
                        JSONArray batch = response.optJSONArray("contacts");
                        if (batch == null) break;
                        for (int i = 0; i < batch.length(); i++) all.put(batch.optJSONObject(i));
                        more = response.optBoolean("has_more", false);
                        if (batch.length() == 0) break;
                    }
                    JSONObject result = new JSONObject();
                    store.upsertContactNames(all);
                    result.put("contacts", all);
                    return result;
                }
                catch (Exception error) { JSONObject result = new JSONObject(); try { result.put("error", error.getMessage()); } catch (Exception ignored) { } return result; }
            }
            protected void onPostExecute(JSONObject result) {
                contactsLoading = false;
                JSONArray data = result.optJSONArray("contacts");
                if (data == null) {
                    status.setText("Contacts unavailable: " + result.optString("error", "server error"));
                    if(contacts.length()==0)contacts = new JSONArray();
                } else {
                    contacts = data;ConversationCache.publishContacts(data);
                    status.setText(data.length() + " contacts from server · groups and history are stored locally");
                }
                applyFilter();
            }
        }.executeOnExecutor(AppExecutors.NETWORK);
    }

    private void synchronize(final boolean showProgress) {
        if(!showProgress&&android.os.SystemClock.elapsedRealtime()-SyncCoordinator.lastFinishedAt()<3000)return;
        if (syncing || AppPrefs.getApiToken(this).length() == 0) return;
        syncing = true;
        if (showProgress) status.setText("Syncing messages…");
        new AsyncTask<Void, String, SyncCoordinator.Result>() {
            protected SyncCoordinator.Result doInBackground(Void... params) {
                return SyncCoordinator.sync(MainActivity.this, new ApiClient(MainActivity.this), new SyncCoordinator.ProgressListener() {
                    public void onProgress(String message) { publishProgress(message); }
                });
            }
            protected void onProgressUpdate(String... values) { if (values.length > 0) status.setText(values[0]);if(System.currentTimeMillis()-lastUiRefresh>1000){lastUiRefresh=System.currentTimeMillis();loadLocalChats();} }
            protected void onPostExecute(SyncCoordinator.Result result) {
                syncing = false;
                loadLocalChats();
                if (result.error != null) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("Offline: " + result.error + " · showing " + result.localChats
                            + " cached conversations / " + result.localMessages + " messages");
                } else {
                    status.setVisibility(result.historyProgress!=null&&result.historyProgress.length()>0?View.VISIBLE:View.GONE);
                    if(result.historyProgress!=null&&result.historyProgress.length()>0){status.setText(result.historyProgress);return;}
                    status.setText("Connected as " + result.accountName + " · " + result.localChats
                            + " conversations · " + result.localMessages + " stored messages");
                }
            }
        }.executeOnExecutor(AppExecutors.SYNC);
    }

    private void showLocalRows(JSONArray data,int tab){
        if(data==null||currentTab!=tab)return;
        ArrayList<JSONObject> next=new ArrayList<JSONObject>();
        for(int i=0;i<data.length();i++){JSONObject row=data.optJSONObject(i);if(row==null)continue;
            if(tab==TAB_CALLS)try{row=new JSONObject(row.toString());row.put("sender",row.optString("peer_jid"));String name=ConversationCache.name(row.optString("peer_jid"));row.put("senderName",name.length()>0?name:row.optString("name","Contact"));row.put("createdAtMs",row.optLong("started"));row.put("message",row.optString("direction")+" · "+row.optString("outcome"));}catch(Exception ignored){}
            if(tab!=TAB_GROUPS||row.optBoolean("is_group",false))next.add(row);
        }
        if(cachedTab==tab&&cachedRows.equals(next))return;
        cachedTab=tab;
        cachedRows.clear();cachedRows.addAll(next);applyFilter();
    }
    private void loadLocalChats() {
        if(currentTab==TAB_CONTACTS)return;final int tab=currentTab;
        showLocalRows(tab==TAB_CALLS?ConversationCache.calls():ConversationCache.chats(),tab);
        if(ConversationCache.recent()||localReading)return;
        localReading=true;
        new AsyncTask<Void,Void,Void>(){
            protected Void doInBackground(Void... ignored){try{ConversationCache.refresh(MainActivity.this);}catch(Exception ignoredFailure){}return null;}
            protected void onPostExecute(Void result){localReading=false;if(!isFinishing()){if(currentTab==TAB_CONTACTS){JSONArray names=ConversationCache.contacts();if(names!=null){contacts=names;applyFilter();}}else showLocalRows(currentTab==TAB_CALLS?ConversationCache.calls():ConversationCache.chats(),currentTab);}}
        }.executeOnExecutor(AppExecutors.DATABASE);
    }

    private void applyFilter() {
        int first=list.getFirstVisiblePosition();View firstChild=list.getChildAt(0);int offset=firstChild==null?0:firstChild.getTop();String firstIdentity=first<rows.size()?rowIdentity(rows.get(first)):"";
        int oldPosition=list.getSelectedItemPosition();
        String selectedJid=navigationIdentity.length()>0?navigationIdentity:oldPosition>=0&&oldPosition<rows.size()?rowIdentity(rows.get(oldPosition)):"";
        if (currentTab == TAB_CONTACTS) {
            rows.clear();
            String query = search.getText().toString().toLowerCase(Locale.US);
            for (int i = 0; i < contacts.length(); i++) {
                JSONObject contact = contacts.optJSONObject(i);
                if (contact == null) continue;
                if (contact.optString("name", "").toLowerCase(Locale.US).contains(query)
                        || contact.optString("id", "").toLowerCase(Locale.US).contains(query)) rows.add(contact);
            }
        } else {
            rows.clear();
            String query = search.getText().toString().toLowerCase(Locale.US);
            for (JSONObject row : cachedRows) {
                if (row.optString("senderName", "").toLowerCase(Locale.US).contains(query)
                        || row.optString("message", "").toLowerCase(Locale.US).contains(query)) rows.add(row);
            }
        }
        adapter.notifyDataSetChanged();
        navigationPosition=-1;
        for(int i=0;i<rows.size();i++){String identity=rowIdentity(rows.get(i));if(selectedJid.equals(identity))navigationPosition=i;if(firstIdentity.equals(identity))list.setSelectionFromTop(i,offset);}
        refreshSelection();
    }

    private String rowIdentity(JSONObject row){return row.optString("call_id",row.optString("sender",row.optString("id")));}

    private void showCallActions(final JSONObject row) {
        new android.support.v7.app.AlertDialog.Builder(this).setTitle(row.optString("senderName","Call"))
                .setItems(new String[]{"Voice call","Open chat"},new android.content.DialogInterface.OnClickListener(){
                    public void onClick(android.content.DialogInterface dialog,int which){
                        if(which==1){openChat(row.optString("peer_jid"),row.optString("senderName"));return;}
                        Intent call=new Intent(MainActivity.this,CallActivity.class);call.putExtra("peer_jid",row.optString("peer_jid"));call.putExtra("peer_name",row.optString("senderName"));startActivity(call);
                    }
                }).show();
    }

    private void openChat(final String jid, String name) {
        if (jid.length() == 0) return;
        AppExecutors.DATABASE.execute(new Runnable(){public void run(){store.markChatRead(jid);}});
        MessageNotifications.cancelChat(this,jid);
        JSONObject row=findChatRow(jid);if(row!=null)try{row.put("unread_count",0);}catch(Exception ignored){}
        adapter.notifyDataSetChanged();
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("peer_jid", jid);
        intent.putExtra("peer_name", name);
        JSONObject selected = currentTab == TAB_CONTACTS ? null : findChatRow(jid);
        if (selected != null) intent.putExtra("peer_phone_jid", selected.optString("phone_jid", ""));
        startActivity(intent);
    }

    private JSONObject findChatRow(String jid) {
        for (JSONObject row : rows) if (jid.equals(row.optString("sender", ""))) return row;
        return null;
    }

    private int tabId(int tab){return new int[]{R.id.tab_chats,R.id.tab_groups,R.id.tab_contacts,R.id.tab_calls}[tab];}
    private void refreshSelection(){for(int i=0;i<list.getChildCount();i++)paintSelection(list.getChildAt(i),!tabNavigationFocused&&list.getFirstVisiblePosition()+i==navigationPosition);}
    private void paintSelection(View row,boolean selected){row.setActivated(selected);row.setBackgroundColor(selected?getResources().getColor(R.color.selection):android.graphics.Color.TRANSPARENT);}
    private void focusTabs(){tabNavigationFocused=true;findViewById(tabId(currentTab)).requestFocus();refreshSelection();}
    private boolean atListTop(){return list.getFirstVisiblePosition()==0&&(list.getChildCount()==0||list.getChildAt(0).getTop()>=list.getPaddingTop());}
    private void selectForNavigation(int position,boolean scroll) {
        if(position<0||position>=rows.size())return;
        tabNavigationFocused=false;navigationPosition=position;navigationIdentity=rowIdentity(rows.get(position));
        list.requestFocus();refreshSelection();
        if(scroll&&(position<list.getFirstVisiblePosition()||position>list.getLastVisiblePosition()))list.setSelection(position);
        ConversationCache.prefetch(this,rows.get(position).optString("sender",rows.get(position).optString("id")));
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(search!=null&&search.hasFocus()){
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(down){if(rows.isEmpty())list.requestFocus();else selectForNavigation(0,true);}return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP){if(down)focusTabs();return true;}
            return super.dispatchKeyEvent(event);
        }
        View focus=getCurrentFocus();
        if(findViewById(R.id.search_button).hasFocus()||findViewById(R.id.menu_button).hasFocus()||findViewById(R.id.new_chat).hasFocus()){
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(down)focusTabs();return true;}
        }
        boolean tabFocus=tabNavigationFocused;
        for(int i=0;i<4;i++)if(findViewById(tabId(i)).hasFocus())tabFocus=true;
        if(tabFocus){
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
                if(down&&event.getRepeatCount()==0){int target=Math.max(0,Math.min(3,currentTab+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1)));if(target!=currentTab)setTab(target);focusTabs();}return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(down){tabNavigationFocused=false;if(rows.isEmpty())list.requestFocus();else selectForNavigation(0,true);}return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP){if(down){tabNavigationFocused=false;findViewById(R.id.search_button).requestFocus();}return true;}
        }
        boolean listFocus=!tabFocus&&(focus==list||focus==null||list.hasFocus());
        if(listFocus){
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)return true;
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
                if(down){
                    if(key==KeyEvent.KEYCODE_DPAD_UP&&navigationPosition<=0&&atListTop())focusTabs();
                    else if(!rows.isEmpty())selectForNavigation(Math.max(0,Math.min(rows.size()-1,navigationPosition<0?0:navigationPosition+(key==KeyEvent.KEYCODE_DPAD_DOWN?1:-1))),true);
                }return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(down&&event.getRepeatCount()==0){int position=navigationPosition>=0?navigationPosition:list.getSelectedItemPosition();if(position<0&&!rows.isEmpty())position=0;if(position>=0&&position<rows.size())list.performItemClick(list.getChildAt(position-list.getFirstVisiblePosition()),position,adapter.getItemId(position));}return true;}
        }
        return super.dispatchKeyEvent(event);
    }

    private static String initials(String name) {
        String[] parts = (name == null ? "" : name.trim()).split("\\s+");
        if (parts.length == 0 || parts[0].length() == 0) return "?";
        String result = parts[0].substring(0, 1).toUpperCase(Locale.US);
        if (parts.length > 1 && parts[1].length() > 0) result += parts[1].substring(0, 1).toUpperCase(Locale.US);
        return result;
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView == null ? getLayoutInflater().inflate(R.layout.row_conversation, parent, false) : convertView;
            paintSelection(row,!tabNavigationFocused&&position==navigationPosition);
            JSONObject item = rows.get(position);
            boolean isContact = currentTab == TAB_CONTACTS;
            String jid = item.optString(isContact ? "id" : "sender", "");
            String name = item.optString(isContact ? "name" : "senderName", "").trim();
            if (name.length() == 0 || name.equals(jid) || name.contains("@") || looksLikePhone(name)
                    || "WhatsApp contact".equalsIgnoreCase(name) || "WhatsApp group".equalsIgnoreCase(name)) {
                if (isContact) name = looksLikePhone(jid.replace("@c.us", "")) ? "WhatsApp contact" : jid.replace("@c.us", "");
                else if (jid.endsWith("@g.us")) name = "WhatsApp group";
                else if (jid.endsWith("@newsletter")) name = "Channel";
                else name = "WhatsApp contact";
            }
            TextView avatar = (TextView) row.findViewById(R.id.avatar);
            avatar.setText(initials(name));
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(AVATAR_COLORS[Math.abs(jid.hashCode() % AVATAR_COLORS.length)]);
            avatar.setBackgroundDrawable(shape);
            ((TextView) row.findViewById(R.id.name)).setText(EmojiText.render(MainActivity.this, name, Ui.dp(MainActivity.this, 18)));
            String preview=isContact?jid:item.optString("message", "");((TextView) row.findViewById(R.id.preview)).setText(preview.length()>256?preview.substring(0,256):preview);
            TextView time = (TextView) row.findViewById(R.id.time);
            long timestamp = item.optLong("createdAtMs", 0);
            time.setText(!isContact && timestamp > 0 ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timestamp)) : "");
            TextView unread = (TextView) row.findViewById(R.id.unread);
            int unreadCount = isContact || currentTab==TAB_CALLS ? 0 : item.optInt("unread_count",0);
            unread.setText(unreadCount > 99 ? "99+" : unreadCount > 0 ? String.valueOf(unreadCount) : "");
            unread.setVisibility(unreadCount > 0 ? View.VISIBLE : View.GONE);
            return row;
        }
    }

    private static boolean looksLikePhone(String value) {
        return value != null && value.matches("\\+?[0-9][0-9 .()-]{5,}");
    }
}





