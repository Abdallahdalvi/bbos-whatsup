package com.aghani.q20messenger;

import android.support.v7.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Button;
import android.media.MediaRecorder;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.util.LruCache;
import android.widget.Toast;
import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

public class ChatActivity extends BaseActivity {
    private static final int REQUEST_MEDIA = 41;
    private String peerJid;
    private String peerName;
    private String peerPhoneJid;
    private String ownJid;
    private LocalStore store;
    private ListView messageList;
    private EditText composer;
    private TextView status;
    private MessageAdapter adapter;
    private final ArrayList<JSONObject> messages = new ArrayList<JSONObject>();
    private final LruCache<String, Bitmap> mediaThumbs = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final HashSet<String> thumbnailsLoading = new HashSet<String>();
    private final java.util.HashMap<String, ArrayList<java.lang.ref.WeakReference<ImageView>>> thumbnailTargets = new java.util.HashMap<String, ArrayList<java.lang.ref.WeakReference<ImageView>>>();
    private AudioPlaybackController audioPlayback;
    private boolean selectedAsDocument;
    private boolean loading = false;
    private boolean sending = false;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording = false;
    private boolean voiceDraftReady = false;
    private long recordingStartedAt;
    private android.widget.ImageButton voiceButton;
    private Button cancelVoiceButton;
    private TextView recordingTime;
    private final Handler handler = new Handler();
    private final Runnable recordingClock = new Runnable() {
        public void run() {
            if (!recording) return;
            long seconds = Math.max(0, (System.currentTimeMillis() - recordingStartedAt) / 1000);
            recordingTime.setText(String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            handler.postDelayed(this, 250);
        }
    };
    private final Runnable poller = new Runnable() {
        public void run() {
            if (!isFinishing()) {
                loadMessages(false);
                handler.postDelayed(this, 4000);
            }
        }
    };
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        peerJid = getIntent().getStringExtra("peer_jid");
        peerName = getIntent().getStringExtra("peer_name");
        peerPhoneJid = getIntent().getStringExtra("peer_phone_jid");
        if (peerJid == null) peerJid = "";
        if (peerPhoneJid == null) peerPhoneJid = "";
        if (peerName == null || peerName.length() == 0 || peerName.equals(peerJid)
                || peerName.contains("@") || looksLikePhone(peerName)) {
            peerName = peerJid.endsWith("@g.us") ? "WhatsApp group"
                    : (peerJid.endsWith("@newsletter") ? "Channel" : "WhatsApp contact");
        }
        ownJid = AppPrefs.ownJid(this);
        store = LocalStore.get(this);
        peerJid=store.resolveChatJid(peerJid);
        String savedName=ConversationCache.name(peerJid);if(savedName.length()>0)peerName=savedName;
        audioPlayback = new AudioPlaybackController(this);
        buildScreen();
        loadMessages(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppVisibility.visibleChat=peerJid;MessageNotifications.cancelChat(this,peerJid);
        if(AppPrefs.backgroundEnabled(this))startService(new Intent(this,IncomingCallService.class));
        handler.removeCallbacks(poller);
        handler.postDelayed(poller, 4000);

    }

    @Override
    protected void onPause() {
        if(peerJid.equals(AppVisibility.visibleChat))AppVisibility.visibleChat="";
        if (audioPlayback != null) audioPlayback.pause();
        if (recording) finishVoiceRecording();
        handler.removeCallbacks(poller);
        super.onPause();
    }

    private void buildScreen() {
        setContentView(R.layout.activity_chat);
        TextView title = (TextView) findViewById(R.id.chat_title);
        title.setText(peerName);
        status = (TextView) findViewById(R.id.chat_status);
        status.setText("WhatsApp chat");
        findViewById(R.id.chat_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { finish(); }
        });
        findViewById(R.id.chat_refresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { loadMessages(true); }
        });
        findViewById(R.id.chat_call).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { startDirectVoiceCall(); }
        });
        messageList = (ListView) findViewById(R.id.message_list);
        messageList.setDivider(null);
        messageList.setFocusable(true);
        messageList.setFocusableInTouchMode(true);
        adapter = new MessageAdapter();
        messageList.setAdapter(adapter);
        messageList.setItemsCanFocus(false);
        messageList.setDrawSelectorOnTop(true);
        messageList.setSelector(R.drawable.message_selector);
        messageList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener(){
            public void onItemClick(android.widget.AdapterView<?> parent,View view,int position,long id){activateMessage(position);}
        });
        messageList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) { }
            public void onScroll(android.widget.AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                if (firstVisible == 0 && visibleCount > 0 && !loading) loadOlderMessages();
            }
        });
        findViewById(R.id.chat_attach).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { chooseMedia(); }
        });
        voiceButton = (android.widget.ImageButton) findViewById(R.id.chat_mic);
        voiceButton.setColorFilter(android.graphics.Color.WHITE);
        ((android.widget.ImageButton) findViewById(R.id.chat_send)).setColorFilter(android.graphics.Color.WHITE);
        recordingTime = (TextView) findViewById(R.id.chat_record_time);
        cancelVoiceButton = (Button) findViewById(R.id.chat_record_cancel);
        voiceButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { toggleVoiceRecording(); }
        });
        cancelVoiceButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { cancelVoiceRecording(); }
        });
        composer = (EditText) findViewById(R.id.chat_composer);
        composer.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { updateComposeAction(); }
            public void afterTextChanged(android.text.Editable text) { }
        });
        findViewById(R.id.chat_emoji).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                final String[] emoji = { "😀", "😂", "🤣", "😊", "😍", "🥰", "😎", "😭", "😢", "😡", "🤔", "🙏", "👍", "👎", "👏", "❤️", "🔥", "🎉", "✅", "👋", "💯", "💕", "🤝", "😅", "🥺", "🙂", "😴", "🤩", "💪", "✨" };
                CharSequence[] labels = new CharSequence[emoji.length];
                for (int i = 0; i < emoji.length; i++) labels[i] = EmojiText.render(ChatActivity.this, emoji[i], Ui.dp(ChatActivity.this, 28));
                new android.support.v7.app.AlertDialog.Builder(ChatActivity.this).setTitle("Emoji").setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        int position = Math.max(0, composer.getSelectionStart());
                        composer.getText().insert(position, emoji[which]);
                    }
                }).show();
            }
        });
        View send = findViewById(R.id.chat_send);
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (voiceDraftReady) sendVoiceDraft();
                else sendText();
            }
        });
        for(int iconId:new int[]{R.id.chat_back,R.id.chat_call,R.id.chat_refresh,R.id.chat_attach,R.id.chat_emoji})((android.widget.ImageButton)findViewById(iconId)).setColorFilter(getResources().getColor(R.color.ink));
        messageList.requestFocus();
    }

    private void loadMessages(final boolean showStatus) {
        if (loading || peerJid.length() == 0) return;
        loading = true;
        if(messages.isEmpty())renderCachedMessages();
        if (showStatus) status.setText(messages.isEmpty() ? "Connecting…" : "Updating conversation");
        final int loadedLimit=Math.max(100,messages.size());

        new AsyncTask<Void, Void, MessageResult>() {
            protected MessageResult doInBackground(Void... params) {
                MessageResult result = new MessageResult();
                try {
                    JSONObject response = new ApiClient(ChatActivity.this).getChatPage(peerJid, Long.MAX_VALUE, Long.MAX_VALUE, 100);
                    JSONArray remote = response.optJSONArray("messages");
                    if (remote != null) store.upsertMessages(remote);
                } catch (Exception error) { result.error = error.getMessage(); }
                result.items = store.getMessages(peerJid, loadedLimit, Long.MAX_VALUE);
                ConversationCache.remember(peerJid,result.items);
                return result;
            }

            protected void onPostExecute(MessageResult result) {
                loading = false;
                if(isFinishing())return;
                noMoreOlder=false;
                boolean changed=!messages.toString().equals(result.items.toString());
                boolean atBottom=messageList.getLastVisiblePosition()>=messages.size()-3;
                int first=messageList.getFirstVisiblePosition();View firstView=messageList.getChildAt(0);int top=firstView==null?0:firstView.getTop();
                if(changed){messages.clear();messages.addAll(result.items);}
                AppExecutors.DATABASE.execute(new Runnable(){public void run(){if(peerJid.equals(AppVisibility.visibleChat))store.markChatRead(peerJid);}});
                if(changed){adapter.notifyDataSetChanged();if(atBottom&&messages.size()>0)messageList.setSelection(messages.size()-1);else messageList.setSelectionFromTop(first,top);}
                status.setText(result.error == null ? "Connected" : "Offline · showing saved messages");
                status.setVisibility(result.error == null ? View.GONE : View.VISIBLE);
            }
        }.executeOnExecutor(AppExecutors.CHAT_PAGES);
    }

    private void renderCachedMessages() {
        ArrayList<JSONObject> ready=ConversationCache.messages(peerJid);
        if(ready!=null&&!ready.isEmpty()){messages.clear();messages.addAll(ready);adapter.notifyDataSetChanged();messageList.setSelection(messages.size()-1);status.setVisibility(View.GONE);return;}
        new AsyncTask<Void,Void,ArrayList<JSONObject>>(){
            protected ArrayList<JSONObject> doInBackground(Void... ignored){ArrayList<JSONObject> cached=store.getMessages(peerJid,100,Long.MAX_VALUE);ConversationCache.remember(peerJid,cached);return cached;}
            protected void onPostExecute(ArrayList<JSONObject> cached){if(isFinishing()||!messages.isEmpty()||cached.isEmpty())return;messages.addAll(cached);adapter.notifyDataSetChanged();messageList.setSelection(messages.size()-1);status.setVisibility(View.GONE);}
        }.executeOnExecutor(AppExecutors.DATABASE);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            int position = messageList.getSelectedItemPosition();
            if (position >= 0 && position < messages.size()) {
                activateMessage(position); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void activateMessage(int position) {
        if(position<0 || position>=messages.size())return;
        JSONObject message=messages.get(position);String type=message.optString("chatType","");
        if("audio".equals(type)||"ptt".equals(type))audioPlayback.toggle(message);
        else if(isMedia(type))downloadAndOpenMedia(message,"image".equals(type)||"video".equals(type),false);
        else if(message.optJSONObject("rich")!=null && (message.optJSONObject("rich").has("poll")||message.optJSONObject("rich").has("actions"))) {
            final ArrayList<Button> actions=new ArrayList<Button>();collectMessageActions(adapter.getView(position,null,messageList),actions);
            if(actions.size()==1){actions.get(0).performClick();return;}
            if(actions.size()>1){String[] labels=new String[actions.size()];for(int i=0;i<labels.length;i++)labels[i]=actions.get(i).getText().toString();
                new android.support.v7.app.AlertDialog.Builder(this).setTitle("Message actions").setItems(labels,new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int which){actions.get(which).performClick();}}).show();}
        } else {
            final String text=message.optString("message","");
            new android.support.v7.app.AlertDialog.Builder(this).setMessage(EmojiText.render(this,text,Ui.dp(this,17))).setPositiveButton("Copy",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Message",text));}}).setNegativeButton("Close",null).show();
        }
    }

    private void collectMessageActions(View view,ArrayList<Button> result){if(view instanceof Button && view.isClickable())result.add((Button)view);else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectMessageActions(group.getChildAt(i),result);}}
    private boolean loadingOlder;
    private boolean noMoreOlder;
    private void loadOlderMessages() {
        if (loadingOlder || noMoreOlder || messages.isEmpty()) return;
        loadingOlder = true;
        final long before = messages.get(0).optLong("timestamp_ms", Long.MAX_VALUE);
        final long beforeId = messages.get(0).optLong("_id", Long.MAX_VALUE);
        new AsyncTask<Void, Void, ArrayList<JSONObject>>() {
            protected ArrayList<JSONObject> doInBackground(Void... params) {
                ArrayList<JSONObject> cached=store.getMessages(peerJid,100,before,beforeId);
                if(cached.size()<100)try{
                    JSONObject page=new ApiClient(ChatActivity.this).getChatPage(peerJid,before,beforeId,100);
                    store.upsertMessages(page.optJSONArray("messages"));
                    return store.getMessages(peerJid,100,before,beforeId);
                }catch(Exception error){ /* Keep the cached page while offline. */ }
                return cached;
            }
            protected void onPostExecute(ArrayList<JSONObject> older) {
                loadingOlder = false;
                if (older == null || older.isEmpty()) { noMoreOlder = true; return; }
                if (older.size() < 100) noMoreOlder = true;
                int previousPosition = messageList.getFirstVisiblePosition();
                View first = messageList.getChildAt(0);
                int offset = first == null ? 0 : first.getTop();
                messages.addAll(0, older);
                adapter.notifyDataSetChanged();
                messageList.setSelectionFromTop(previousPosition + older.size(), offset);
            }
        }.executeOnExecutor(AppExecutors.CHAT_PAGES);
    }

    private void sendText() {
        final String text = composer.getText().toString().trim();
        if (text.length() == 0 || sending) return;
        sending = true;
        status.setText("Sending...");

        new AsyncTask<Void, Void, String>() {
            protected String doInBackground(Void... params) {
                try {
                    JSONObject result = new ApiClient(ChatActivity.this).sendText(peerJid, text);
                    return result.optString("message", "Message sent");
                } catch (Exception error) {
                    return "ERROR: " + error.getMessage();
                }
            }
            protected void onPostExecute(String result) {
                sending = false;
                if (result.startsWith("ERROR:")) {
                    status.setText(result);
                    SafeToast.makeText(ChatActivity.this, result, Toast.LENGTH_LONG).show();
                } else {
                    composer.setText("");
                    status.setText("Message sent");
                    loadMessages(false);
                }
            }
        }.executeOnExecutor(AppExecutors.NETWORK);
    }

    private void chooseCallType() {
        startDirectVoiceCall();
    }

    private void startDirectVoiceCall() {
        if (peerJid.endsWith("@g.us") || peerJid.endsWith("@newsletter")) {
            SafeToast.makeText(this, "Direct voice calls are supported for private contacts only.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("peer_jid", peerJid);
        intent.putExtra("peer_name", peerName);
        startActivity(intent);
    }

    private void createCallLink(final String type) {
        if (sending) return;
        sending = true;
        status.setText("Creating and sending call invitation...");
        new AsyncTask<Void, Void, JSONObject>() {
            private String error;
            protected JSONObject doInBackground(Void... params) {
                try { return new ApiClient(ChatActivity.this).createCallLink(peerJid, type); }
                catch (Exception failure) { error = failure.getMessage(); return null; }
            }
            protected void onPostExecute(final JSONObject result) {
                sending = false;
                if (result == null || result.optString("link", "").length() == 0) {
                    if ("voice".equals(type) && openPhoneDialer()) {
                        status.setText("Opening phone dialer");
                        SafeToast.makeText(ChatActivity.this, "WhatsApp call links are unavailable on this server; continue with a regular phone call.", Toast.LENGTH_LONG).show();
                    } else {
                        status.setText("Call link unavailable");
                        SafeToast.makeText(ChatActivity.this, error == null ? "Could not create the call link" : error, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                final String link = result.optString("link");
                final boolean invitationSent = result.optBoolean("invitation_sent", true);
                status.setText(invitationSent ? "Call invitation sent" : "Call link created; invitation needs sharing");
                new android.support.v7.app.AlertDialog.Builder(ChatActivity.this)
                        .setTitle(invitationSent ? "Invitation sent" : "Call link created")
                        .setMessage(invitationSent
                                ? "WhatsApp created a " + type + " call link and sent it in this chat. Open the link to join."
                                : result.optString("warning", "The link could not be sent automatically. Share it with the contact."))
                        .setPositiveButton(invitationSent ? "Open link" : "Share link", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (invitationSent) {
                                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
                                    catch (Exception error) { SafeToast.makeText(ChatActivity.this, "No browser can open this call link.", Toast.LENGTH_LONG).show(); }
                                } else {
                                    Intent share = new Intent(Intent.ACTION_SEND);
                                    share.setType("text/plain");
                                    share.putExtra(Intent.EXTRA_TEXT, link);
                                    try { startActivity(Intent.createChooser(share, "Share WhatsApp call link")); }
                                    catch (Exception error) { SafeToast.makeText(ChatActivity.this, "Could not open the share menu.", Toast.LENGTH_LONG).show(); }
                                }
                            }
                        }).setNegativeButton("Later", null).show();
            }
        }.executeOnExecutor(AppExecutors.NETWORK);
    }

    private boolean openPhoneDialer() {
        String phone = peerPhoneJid;
        if (phone.length() == 0 && peerJid.endsWith("@c.us")) phone = peerJid;
        if (phone.length() == 0 || peerJid.endsWith("@g.us") || peerJid.endsWith("@newsletter")) return false;
        String number = phone.replace("@c.us", "").replaceAll("[^0-9+*#]", "");
        if (number.length() == 0) return false;
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void toggleVoiceRecording() {
        if (recording) finishVoiceRecording();
        else if (voiceDraftReady) sendVoiceDraft();
        else startVoiceRecording();
    }

    private void updateComposeAction() {
        boolean textReady = !recording && !voiceDraftReady && composer != null && composer.getText().toString().trim().length() > 0;
        findViewById(R.id.chat_send).setVisibility(textReady ? View.VISIBLE : View.GONE);
        voiceButton.setVisibility(textReady ? View.GONE : View.VISIBLE);
    }

    private void setVoiceLabel(String label) {
        voiceButton.setImageResource("Done".equals(label) ? android.R.drawable.ic_media_pause : "Send".equals(label) ? R.drawable.ic_send : R.drawable.ic_mic);
        updateComposeAction();
    }

    private void startVoiceRecording() {
        try {
            recordingFile = new File(getCacheDir(), "voice-" + System.currentTimeMillis() + ".3gp");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            voiceDraftReady = false;
            recordingStartedAt = System.currentTimeMillis();
            recordingTime.setText("0:00");
            recordingTime.setVisibility(View.VISIBLE);
            cancelVoiceButton.setVisibility(View.VISIBLE);
            setVoiceLabel("Done");
            voiceButton.setContentDescription("Finish voice recording");
            handler.removeCallbacks(recordingClock);
            handler.post(recordingClock);
            status.setText("Recording voice note · tap Done to review");
        } catch (Exception error) {
            releaseRecorder();
            recording = false;
            setVoiceLabel("Mic");
            SafeToast.makeText(this, "Microphone unavailable: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void finishVoiceRecording() {
        try {
            if (recorder != null) recorder.stop();
        } catch (RuntimeException error) {
            SafeToast.makeText(this, "Voice note was too short. Please record again.", Toast.LENGTH_LONG).show();
            if (recordingFile != null) recordingFile.delete();
            releaseRecorder();
            recording = false;
            setVoiceLabel("Mic");
            voiceButton.setContentDescription("Record voice note");
            recordingTime.setVisibility(View.GONE);
            cancelVoiceButton.setVisibility(View.GONE);
            return;
        }
        releaseRecorder();
        recording = false;
        handler.removeCallbacks(recordingClock);
        if (recordingFile != null && recordingFile.exists() && recordingFile.length() > 0) {
            voiceDraftReady = true;
            setVoiceLabel("Send");
            voiceButton.setContentDescription("Send recorded voice note");
            cancelVoiceButton.setText("Cancel");
            recordingTime.setText(formatDuration((System.currentTimeMillis() - recordingStartedAt) / 1000));
            status.setText("Voice note ready · " + formatDuration((System.currentTimeMillis() - recordingStartedAt) / 1000));
        } else {
            setVoiceLabel("Mic");
            voiceButton.setContentDescription("Record voice note");
            recordingTime.setVisibility(View.GONE);
            cancelVoiceButton.setVisibility(View.GONE);
            status.setText("Voice recording was empty.");
        }
    }

    private String formatDuration(long seconds) {
        seconds = Math.max(0, seconds);
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void sendVoiceDraft() {
        if (!voiceDraftReady || recordingFile == null || !recordingFile.exists()) return;
        final Uri voiceUri = Uri.fromFile(recordingFile);
        voiceDraftReady = false;
        setVoiceLabel("Mic");
        voiceButton.setContentDescription("Record voice note");
        recordingTime.setVisibility(View.GONE);
        cancelVoiceButton.setVisibility(View.GONE);
        status.setText("Uploading voice note...");
        uploadSelected(voiceUri, false, true);
    }

    private void cancelVoiceRecording() {
        handler.removeCallbacks(recordingClock);
        if (recording) {
            try { if (recorder != null) recorder.stop(); } catch (Exception ignored) { }
            releaseRecorder();
            recording = false;
        }
        if (recordingFile != null && recordingFile.exists()) recordingFile.delete();
        recordingFile = null;
        voiceDraftReady = false;
        setVoiceLabel("Mic");
        voiceButton.setContentDescription("Record voice note");
        recordingTime.setVisibility(View.GONE);
        cancelVoiceButton.setVisibility(View.GONE);
        status.setText("Voice note discarded");
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    private void chooseMedia() {
        new android.support.v7.app.AlertDialog.Builder(this).setTitle("Attach")
                .setItems(new String[] { "Photo", "Video", "Document", "Audio" }, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        selectedAsDocument = which == 2;
                        pickFile(which == 0 ? "image/*" : which == 1 ? "video/*" : which == 3 ? "audio/*" : "*/*");
                    }
                }).show();
    }

    private void pickFile(String mime) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Choose photo, video, audio or document"), REQUEST_MEDIA);
        } catch (Exception error) {
            SafeToast.makeText(this, "No file picker is available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri selected = data.getData();
            String mime = getContentResolver().getType(selected);
            if (selectedAsDocument) { uploadSelected(selected, true); }
            else if (mime != null && mime.toLowerCase(java.util.Locale.US).startsWith("video/")) {
                new android.support.v7.app.AlertDialog.Builder(this)
                        .setTitle("Send video")
                        .setMessage("WhatsApp compresses regular video messages. Send as a file to preserve the original resolution and aspect ratio?")
                        .setPositiveButton("Original quality", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface dialog, int which) { uploadSelected(selected, true); }
                        })
                        .setNegativeButton("Send video", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface dialog, int which) { uploadSelected(selected, false); }
                        }).show();
            } else uploadSelected(selected, false);
        }
    }

    private void uploadSelected(final Uri uri) {
        uploadSelected(uri, false);
    }

    private void uploadSelected(final Uri uri, final boolean originalQualityVideo) {
        uploadSelected(uri, originalQualityVideo, false);
    }

    private void uploadSelected(final Uri uri, final boolean originalQualityVideo, final boolean voiceDraft) {
        if (sending) return;
        sending = true;
        status.setText("Uploading media...");
        new AsyncTask<Void, Void, String>() {
            protected String doInBackground(Void... params) {
                try {
                    JSONObject response = new ApiClient(ChatActivity.this).uploadMedia(uri, peerJid, originalQualityVideo, voiceDraft);
                    return response.optString("statusDesc", "Media sent");
                } catch (Exception error) {
                    return "ERROR: " + error.getMessage();
                }
            }
            protected void onPostExecute(String result) {
                sending = false;
                status.setText(result);
                SafeToast.makeText(ChatActivity.this, result, Toast.LENGTH_LONG).show();
                if (!result.startsWith("ERROR:")) {
                    if (voiceDraft && recordingFile != null) {
                        recordingFile.delete();
                        recordingFile = null;
                        recordingTime.setVisibility(View.GONE);
                        cancelVoiceButton.setVisibility(View.GONE);
                    }
                    loadMessages(false);
                } else if (voiceDraft) {
                    voiceDraftReady = true;
                    setVoiceLabel("Send");
                    voiceButton.setContentDescription("Retry sending voice note");
                    recordingTime.setVisibility(View.VISIBLE);
                    cancelVoiceButton.setVisibility(View.VISIBLE);
                    status.setText("Voice note not sent · retry or cancel");
                }
            }
        }.executeOnExecutor(AppExecutors.NETWORK);
    }

    private void openMedia(final JSONObject message) {
        final String type = message.optString("chatType", "");
        final boolean video = "video".equals(type) || ("gif".equals(type) && message.optString("media_mimetype").startsWith("video/"));
        final String[] choices = video ? new String[] { "Play smooth Q20 copy (480p)", "View original", "Download original to Downloads" }
                : new String[] { "Download and view", "Save to Downloads" };
        new android.support.v7.app.AlertDialog.Builder(this).setTitle(video ? "Video" : "Media")
                .setItems(choices, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        downloadAndOpenMedia(message, video && which == 0, video ? which == 2 : which == 1);
                    }
                }).show();
    }

    private void downloadAndOpenMedia(final JSONObject message, final boolean smoothVideo, final boolean saveOnly) {
        final long id = message.optLong("_id", -1);
        final String type = message.optString("chatType", "");
        final String viewerType = "gif".equals(type)
                ? (message.optString("media_mimetype", "").startsWith("video/") ? "video" : "image") : type;
        if (id < 0) return;
        status.setText(smoothVideo ? "image".equals(viewerType) ? "Downloading preview…" : "Preparing Q20 video copy…" : "Downloading " + type + "…");

        new AsyncTask<Void, Void, File>() {
            private String errorText;
            protected File doInBackground(Void... params) {
                try {
                    ApiClient api = new ApiClient(ChatActivity.this);
                    String extension = ".bin";
                    String originalName = message.optString("media_original_filename", message.optString("media_filename", ""));
                    if (originalName.length() == 0) originalName = message.optString("media_filename", "");
                    int dot = originalName.lastIndexOf('.');
                    if (dot >= 0 && originalName.substring(dot).matches("\\.[A-Za-z0-9]{1,10}")) extension = originalName.substring(dot);
                    else if ("video".equals(viewerType)) extension = ".mp4";
                    else if ("image".equals(viewerType)) extension = ".jpg";
                    else if ("ptt".equals(type) || "audio".equals(type)) extension = ".ogg";
                    File media = "document".equals(type) ? api.downloadDocument(message)
                            : smoothVideo ? api.downloadVariant(id, "image".equals(viewerType) ? "thumbnail" : "q20-480", "image".equals(viewerType) ? ".jpg" : ".mp4")
                            : saveOnly ? api.downloadVariant(id, "original", extension) : api.downloadMedia(id, viewerType);
                    return saveOnly ? api.saveToDownloads(media) : media;
                } catch (Exception error) {
                    errorText = error.getMessage();
                    return null;
                }
            }
            protected void onPostExecute(File file) {
                if (file == null) {
                    status.setText("Media download failed");
                    SafeToast.makeText(ChatActivity.this, errorText, Toast.LENGTH_LONG).show();
                    return;
                }
                status.setText("Opened " + file.getName());
                if (saveOnly) {
                    status.setText("Saved to Downloads/Q20Messenger");
                    SafeToast.makeText(ChatActivity.this, "Saved " + file.getName(), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent intent = new Intent(ChatActivity.this, MediaViewerActivity.class);
                intent.putExtra("file_path", file.getAbsolutePath());
                intent.putExtra("media_type", viewerType);
                intent.putExtra("mime_type", message.optString("media_mimetype", ""));
                intent.putExtra("message_id", id);
                intent.putExtra("showing_copy", smoothVideo);
                intent.putExtra("original_type", type);
                intent.putExtra("original_name", MediaPresentation.filename(message));
                startActivity(intent);
            }
        }.executeOnExecutor(AppExecutors.NETWORK);
    }

    private class MessageAdapter extends BaseAdapter {
        public int getCount() { return messages.size(); }
        public Object getItem(int position) { return messages.get(position); }
        public long getItemId(int position) { return messages.get(position).optLong("_id", position); }

        public View getView(int position, View convertView, ViewGroup parent) {
            final JSONObject message = messages.get(position);
            String type = message.optString("chatType", "chat");
            String body = message.optString("message", "");
            String sender = message.optString("sender", "");
            boolean outgoing = message.optBoolean("from_me", sender.equalsIgnoreCase(ownJid));

            LinearLayout row = Ui.vertical(ChatActivity.this);
            row.setGravity(outgoing ? Gravity.RIGHT : Gravity.LEFT);
            row.setPadding(Ui.dp(ChatActivity.this, 7), Ui.dp(ChatActivity.this, 2),
                    Ui.dp(ChatActivity.this, 7), Ui.dp(ChatActivity.this, 2));

            long day=message.optLong("timestamp_ms",0);java.text.SimpleDateFormat dates=new java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault());
            if(day>0 && (position==0 || !dates.format(new java.util.Date(day)).equals(dates.format(new java.util.Date(messages.get(position-1).optLong("timestamp_ms",0)))))) {
                TextView date=Ui.text(ChatActivity.this,dates.format(new java.util.Date(day)),10,getResources().getColor(R.color.muted));date.setGravity(Gravity.CENTER);date.setPadding(0,Ui.dp(ChatActivity.this,5),0,Ui.dp(ChatActivity.this,5));row.addView(date,new LinearLayout.LayoutParams(-1,-2));
            }
            LinearLayout bubble = Ui.vertical(ChatActivity.this);
            bubble.setPadding(Ui.dp(ChatActivity.this, 7), Ui.dp(ChatActivity.this, 4),
                    Ui.dp(ChatActivity.this, 7), Ui.dp(ChatActivity.this, 4));
            bubble.setBackground(Ui.rounded(getResources().getColor(outgoing ? R.color.bubble_out : R.color.bubble_in), 8, ChatActivity.this));
            bubble.setMinimumWidth(Ui.dp(ChatActivity.this, 64));

            TextView who = Ui.text(ChatActivity.this,
                    outgoing ? "You" : friendlyParticipant(message.optString("sender_name",
                            message.optString("senderName", message.optString("participant_jid", peerName)))), 10,
                    getResources().getColor(R.color.green_dark));
            TextView content = Ui.text(ChatActivity.this, displayMessage(type, body), 15,
                    getResources().getColor(R.color.ink));
            content.setAutoLinkMask(Linkify.ALL);
            content.setMovementMethod(LinkMovementMethod.getInstance());
            content.setMaxWidth(getResources().getDisplayMetrics().widthPixels - Ui.dp(ChatActivity.this, 44));
            if (!outgoing && peerJid.endsWith("@g.us")) bubble.addView(who);
            if (!isMedia(type)) bubble.addView(content);
            addRichContent(bubble, message);

            if (isMedia(type)) {
                if ("image".equals(type) || "video".equals(type) || "sticker".equals(type) || "gif".equals(type)) {
                    final String thumbKey = message.optString("_id", "") + ":" + type;
                    final ImageView preview = new ImageView(ChatActivity.this);
                    preview.setTag(thumbKey);
                    preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    final android.widget.FrameLayout stage = new android.widget.FrameLayout(ChatActivity.this);
                    stage.setBackground(Ui.rounded(getResources().getColor(R.color.bubble_in), 5, ChatActivity.this));
                    stage.addView(preview, new android.widget.FrameLayout.LayoutParams(-1, -1));
                    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                    JSONObject rich = message.optJSONObject("rich");
                    JSONObject media = rich == null ? null : rich.optJSONObject("media");
                    int[] size = MediaPresentation.previewSize(media == null ? 0 : media.optInt("width"), media == null ? 0 : media.optInt("height"),
                            (int) (metrics.widthPixels / metrics.density) - 34, (int) (metrics.heightPixels / metrics.density), "sticker".equals(type));
                    bubble.addView(stage, new LinearLayout.LayoutParams(Ui.dp(ChatActivity.this, size[0]), Ui.dp(ChatActivity.this, size[1])));
                    android.widget.ImageView icon = new android.widget.ImageView(ChatActivity.this);
                    icon.setImageResource("video".equals(type) || "gif".equals(type) ? android.R.drawable.ic_media_play : android.R.drawable.ic_menu_gallery);
                    boolean moving="video".equals(type)||"gif".equals(type);
                    icon.setColorFilter(moving?0xffffffff:0xff52645b);
                    if(moving){icon.setBackground(Ui.rounded(0xb5000000,22,ChatActivity.this));icon.setPadding(Ui.dp(ChatActivity.this,8),Ui.dp(ChatActivity.this,8),Ui.dp(ChatActivity.this,8),Ui.dp(ChatActivity.this,8));}
                    stage.addView(icon, new android.widget.FrameLayout.LayoutParams(Ui.dp(ChatActivity.this,moving?42:28), Ui.dp(ChatActivity.this,moving?42:28), Gravity.CENTER));
                    Bitmap cached = mediaThumbs.get(thumbKey);
                    if (cached != null) fitPreview(preview, cached);
                    else loadMediaThumbnail(message, preview, thumbKey, type);
                }
                if ("document".equals(type)) {
                    LinearLayout card = Ui.horizontal(ChatActivity.this);
                    ImageView icon = new ImageView(ChatActivity.this);
                    icon.setImageResource(android.R.drawable.ic_menu_save);
                    icon.setColorFilter(getResources().getColor(R.color.green_dark));
                    card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(ChatActivity.this, 28), Ui.dp(ChatActivity.this, 34)));
                    LinearLayout info = Ui.vertical(ChatActivity.this);
                    TextView name = Ui.text(ChatActivity.this, MediaPresentation.documentTitle(message), 13, getResources().getColor(R.color.ink));
                    name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    info.addView(name);
                    info.addView(Ui.text(ChatActivity.this, MediaPresentation.fileDetails(message), 10, getResources().getColor(R.color.muted)));
                    card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                    bubble.addView(card, new LinearLayout.LayoutParams(Math.min(Ui.dp(ChatActivity.this, 216), getResources().getDisplayMetrics().widthPixels - Ui.dp(ChatActivity.this, 34)), ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if ("audio".equals(type) || "ptt".equals(type)) {
                    bubble.addView(new AudioMessageView(ChatActivity.this, message, audioPlayback), new LinearLayout.LayoutParams(
                            Math.min(Ui.dp(ChatActivity.this, 216), getResources().getDisplayMetrics().widthPixels - Ui.dp(ChatActivity.this, 34)), Ui.dp(ChatActivity.this, 52)));
                }
                String caption = MediaPresentation.caption(message);
                if (caption.length() > 0 && !"ptt".equals(type) && !"audio".equals(type)) {
                    content.setText(EmojiText.render(ChatActivity.this, caption, Ui.dp(ChatActivity.this, 17)));
                    content.setPadding(Ui.dp(ChatActivity.this, 3), Ui.dp(ChatActivity.this, 2), Ui.dp(ChatActivity.this, 3), 0);
                    bubble.addView(content);
                }
                bubble.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        String mediaType = message.optString("chatType");
                        if ("audio".equals(mediaType) || "ptt".equals(mediaType)) audioPlayback.toggle(message);
                        else if ("document".equals(mediaType)) downloadAndOpenMedia(message, false, false);
                        else downloadAndOpenMedia(message, "image".equals(mediaType) || "video".equals(mediaType) || ("gif".equals(mediaType) && message.optString("media_mimetype").startsWith("video/")), false);
                    }
                });
                bubble.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View view) { openMedia(message); return true; }
                });
            }

            long timestamp = message.optLong("timestamp_ms", 0);
            String timeText = timestamp > 0
                    ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(timestamp)) : "";
            TextView time = Ui.text(ChatActivity.this, timeText, 9,
                    getResources().getColor(R.color.muted));
            time.setGravity(Gravity.RIGHT);
            bubble.addView(time);
            row.addView(bubble, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Message selection is shown by the ListView without per-bubble borders.
            return row;
        }
    }

    private void addRichContent(LinearLayout bubble, final JSONObject message) {
        JSONObject rich = message.optJSONObject("rich");
        if (rich == null) return;
        final JSONObject poll = rich.optJSONObject("poll");
        if (poll != null) {
            final JSONArray options = poll.optJSONArray("options");
            bubble.addView(Ui.text(this, poll.optString("name", "Poll"), 16, getResources().getColor(R.color.ink)));
            if (options != null && options.length() > 0) {
                Button vote = Ui.button(this, "Vote in poll");
                bubble.addView(vote);
                vote.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        final String[] labels = new String[options.length()];
                        final boolean[] selected = new boolean[options.length()];
                        for (int i = 0; i < labels.length; i++) labels[i] = options.optString(i);
                        android.support.v7.app.AlertDialog.Builder dialog = new android.support.v7.app.AlertDialog.Builder(ChatActivity.this).setTitle(poll.optString("name", "Poll"));
                        if (poll.optBoolean("multiple", false)) dialog.setMultiChoiceItems(labels, selected, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(android.content.DialogInterface d, int which, boolean checked) { selected[which] = checked; }
                        });
                        else dialog.setSingleChoiceItems(labels, -1, new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) { java.util.Arrays.fill(selected, false); selected[which] = true; }
                        });
                        dialog.setPositiveButton("Submit vote", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                final JSONArray votes = new JSONArray();
                                for (int i = 0; i < selected.length; i++) if (selected[i]) votes.put(labels[i]);
                                new AsyncTask<Void, Void, String>() {
                                    protected String doInBackground(Void... ignored) {
                                        try { new ApiClient(ChatActivity.this).votePoll(message.optLong("_id"), votes); return "Vote submitted"; }
                                        catch (Exception error) { return "Vote failed: " + error.getMessage(); }
                                    }
                                    protected void onPostExecute(String result) { SafeToast.makeText(ChatActivity.this, result, Toast.LENGTH_LONG).show(); }
                                }.executeOnExecutor(AppExecutors.NETWORK);
                            }
                        }).setNegativeButton("Cancel", null).show();
                    }
                });
            }
        }
        JSONArray actions = rich.optJSONArray("actions");
        for (int i = 0; actions != null && i < actions.length(); i++) {
            final JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;
            Button button = Ui.button(this, action.optString("label", "Action"));
            bubble.addView(button);
            button.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    String kind = action.optString("kind");
                    String value = action.optString("value");
                    try {
                        if ("url".equals(kind) && (value.startsWith("https://") || value.startsWith("http://"))) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
                        else if ("phone".equals(kind)) startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(value))));
                        else if ("copy".equals(kind)) {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message code", value));
                            SafeToast.makeText(ChatActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                        } else SafeToast.makeText(ChatActivity.this, "This business action requires a reply format the current backend cannot send.", Toast.LENGTH_LONG).show();
                    } catch (Exception error) { SafeToast.makeText(ChatActivity.this, "Cannot open this action", Toast.LENGTH_LONG).show(); }
                }
            });
        }
    }

    private String friendlyParticipant(String jid) {
        if (jid == null || jid.length() == 0) return peerName;
        if (!jid.contains("@") && !looksLikePhone(jid)) return jid;
        if (jid.endsWith("@newsletter")) return "Channel";
        if (jid.endsWith("@lid")) return "Contact";
        if (jid.endsWith("@c.us")) return jid.substring(0, jid.length() - 5);
        return "Contact";
    }

    private static boolean looksLikePhone(String value) {
        return value != null && value.matches("\\+?[0-9][0-9 .()-]{5,}");
    }

    private String documentLabel(JSONObject message) {
        String name = message.optString("media_original_filename", "");
        if (name.length() == 0) name = "Document";
        long bytes = message.optLong("media_filesize", 0);
        String size = bytes > 0 ? String.format(java.util.Locale.US, " · %.1f MB", bytes / 1048576.0) : "";
        return "▣  " + name + size;
    }

    private void loadMediaThumbnail(final JSONObject message, final ImageView target,
                                    final String key, final String type) {
        ArrayList<java.lang.ref.WeakReference<ImageView>> targets = thumbnailTargets.get(key);
        if (targets == null) { targets = new ArrayList<java.lang.ref.WeakReference<ImageView>>(); thumbnailTargets.put(key, targets); }
        targets.add(new java.lang.ref.WeakReference<ImageView>(target));
        if (thumbnailsLoading.contains(key)) return;
        thumbnailsLoading.add(key);
        new AsyncTask<Void, Void, Bitmap>() {
            protected Bitmap doInBackground(Void... params) {
                try {
                    if (!"sticker".equals(type)) {
                        try{File thumbnail = new ApiClient(ChatActivity.this).downloadVariant(message.optLong("_id", -1), "thumbnail", ".jpg");Bitmap result=BitmapFactory.decodeFile(thumbnail.getAbsolutePath());if(result!=null)return result;}
                        catch(Exception error){if(!"image".equals(type))throw error;}
                        if(!"image".equals(type))return null;
                    }
                    File media = new ApiClient(ChatActivity.this).downloadMedia(message.optLong("_id", -1), type);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(media.getAbsolutePath(), bounds);
                    int sample = 1;
                    while (bounds.outWidth / sample > 440 || bounds.outHeight / sample > 300) sample *= 2;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeFile(media.getAbsolutePath(), options);
                } catch (Exception ignored) { return null; }
            }
            protected void onPostExecute(Bitmap bitmap) {
                thumbnailsLoading.remove(key);
                ArrayList<java.lang.ref.WeakReference<ImageView>> targets = thumbnailTargets.remove(key);
                if (bitmap != null) {
                    mediaThumbs.put(key, bitmap);
                    if (targets != null) for (java.lang.ref.WeakReference<ImageView> reference : targets) {
                        ImageView view = reference.get();
                        if (view != null && key.equals(view.getTag())) fitPreview(view, bitmap);
                    }
                }
            }
        }.executeOnExecutor(AppExecutors.THUMBNAILS);
    }

    private void fitPreview(ImageView target, Bitmap bitmap) {
        target.setImageBitmap(bitmap);
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int[] size = MediaPresentation.previewSize(bitmap.getWidth(), bitmap.getHeight(),
                (int) (metrics.widthPixels / metrics.density) - 34, (int) (metrics.heightPixels / metrics.density), String.valueOf(target.getTag()).endsWith(":sticker"));
        View stage = (View) target.getParent();
        ViewGroup.LayoutParams params = stage.getLayoutParams();
        params.width = Ui.dp(this, size[0]); params.height = Ui.dp(this, size[1]); stage.setLayoutParams(params);
        if (!String.valueOf(target.getTag()).endsWith(":video") && !String.valueOf(target.getTag()).endsWith(":gif")) {
            ViewGroup group = (ViewGroup) stage;
            if (group.getChildCount() > 1) group.getChildAt(1).setVisibility(View.GONE);
        }
    }

    private boolean isMedia(String type) {
        return "image".equals(type) || "video".equals(type) || "audio".equals(type)
                || "ptt".equals(type) || "document".equals(type) || "sticker".equals(type) || "gif".equals(type);
    }

    private String mediaAction(String type) {
        if ("image".equals(type)) return "View image";
        if ("video".equals(type) || "gif".equals(type)) return "View media";
        return "Play audio / voice note";
    }

    private String displayMessage(String type, String body) {
        if ("image".equals(type)) return "Photo";
        if ("video".equals(type)) return "Video";
        if ("ptt".equals(type)) return "Voice note";
        if ("audio".equals(type)) return "Audio";
        if ("document".equals(type)) return "Document";
        if ("sticker".equals(type)) return "Sticker";
        return body;
    }

    @Override
    protected void onDestroy() {
        if (audioPlayback != null) audioPlayback.close();
        if (recording) {
            try { if (recorder != null) recorder.stop(); } catch (Exception ignored) { }
        }
        handler.removeCallbacks(recordingClock);
        releaseRecorder();
        recording = false;
        super.onDestroy();
    }

    private static class MessageResult {
        ArrayList<JSONObject> items = new ArrayList<JSONObject>();
        String error;
    }
}
