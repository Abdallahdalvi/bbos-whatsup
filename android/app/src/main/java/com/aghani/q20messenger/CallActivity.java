package com.aghani.q20messenger;

import android.support.v7.app.AppCompatActivity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Full-duplex voice call UI. PCM is relayed to/from the staged WhatsApp Web call bridge. */
public class CallActivity extends BaseActivity {
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_BYTES = 640; // 20 ms, mono PCM16 at 16 kHz.
    private final Handler handler = new Handler();
    private TextView stateText;
    private TextView timerText;
    private Button muteButton;
    private Button speakerButton;
    private String peerJid = "";
    private String peerName = "Contact";
    private String callId = "";
    private WebSocket webSocket;
    private OkHttpClient webSocketClient;
    private AudioRecord recorder;
    private AudioTrack player;
    private AudioManager audioManager;
    private volatile boolean active;
    private volatile boolean muted;
    private volatile boolean finished;
    private boolean callConnected;
    private boolean failed;
    private android.support.v7.app.AlertDialog confirmationDialog;
    private long historyStartedAt = System.currentTimeMillis();
    private final String localHistoryId = "local:" + java.util.UUID.randomUUID().toString();
    private boolean historyIncoming;
    private boolean speakerOn;
    private long connectedAt;
    private android.os.PowerManager.WakeLock callWakeLock;
    private volatile PcmResampler playbackResampler;

    private final Runnable clock = new Runnable() {
        public void run() {
            if (!callConnected || finished) return;
            long seconds = Math.max(0, (System.currentTimeMillis() - connectedAt) / 1000);
            timerText.setText(String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_call);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        callWakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Q20Messenger:voicecall");
        callWakeLock.acquire(2 * 60 * 60 * 1000L);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        peerJid = getIntent().getStringExtra("peer_jid");
        peerName = getIntent().getStringExtra("peer_name");
        callId = getIntent().getStringExtra("call_id");
        boolean incoming = getIntent().getBooleanExtra("incoming", false);
        historyIncoming = incoming;
        if (incoming) { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(201);startService(new android.content.Intent(this,IncomingCallService.class).setAction("silence")); }
        if (peerJid == null) peerJid = "";
        if (peerName == null || peerName.length() == 0) peerName = "WhatsApp contact";
        String savedName=LocalStore.get(this).getChatName(peerJid);
        if(!"WhatsApp contact".equals(savedName))peerName=savedName;
        if (callId == null) callId = "";

        ((TextView) findViewById(R.id.call_peer)).setText(peerName);
        stateText = (TextView) findViewById(R.id.call_state);
        timerText = (TextView) findViewById(R.id.call_timer);
        muteButton = (Button) findViewById(R.id.call_mute);
        speakerButton = (Button) findViewById(R.id.call_speaker);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        muteButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                muted = !muted;
                muteButton.setText(muted ? "Unmute" : "Mute");
            }
        });
        speakerButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { toggleSpeaker(); }
        });
        findViewById(R.id.call_hangup).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { hangUp("Call ended"); }
        });

        stateText.setText(incoming ? "Answering call…" : "Starting WhatsApp call…");
        if (incoming && callId.length() > 0) connectAudio();
        else startOutgoingCall();
    }

    private void startOutgoingCall() {
        requestOutgoingCall(null);
    }

    private void requestOutgoingCall(final String requestId) {
        stateText.setText("Starting WhatsApp call…");
        new AsyncTask<Void, Void, JSONObject>() {
            private String error;
            protected JSONObject doInBackground(Void... ignored) {
                try { return requestId == null ? new ApiClient(CallActivity.this).startVoiceCall(peerJid) : new ApiClient(CallActivity.this).confirmVoiceCall(requestId); }
                catch (Exception failure) { error = failure.getMessage(); return null; }
            }
            protected void onPostExecute(JSONObject result) {
                if (finished) {
                    if (result != null) {
                        final String abandonedId = result.optString("call_id", "");
                        new Thread(new Runnable() { public void run() { try { new ApiClient(CallActivity.this).endVoiceCall(abandonedId); } catch (Exception ignored) { } } }).start();
                    }
                    return;
                }
                if (result == null) { showFailure(error == null ? "Could not start call" : error); return; }
                if ("confirmation_required".equals(result.optString("state"))) {
                    final String id = result.optString("request_id", "");
                    JSONObject confirmation = result.optJSONObject("confirmation");
                    if (id.length() == 0 || confirmation == null) { showFailure("The call confirmation was incomplete. Please retry."); return; }
                    stateText.setText("Call confirmation needed");
                    confirmationDialog = new android.support.v7.app.AlertDialog.Builder(CallActivity.this)
                            .setTitle(confirmation.optString("title", "About this call"))
                            .setMessage(confirmation.optString("message", ""))
                            .setPositiveButton("Continue", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface dialog, int which) { requestOutgoingCall(id); }
                            }).setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface dialog, int which) { hangUp("Call cancelled"); }
                            }).setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                                public void onCancel(android.content.DialogInterface dialog) { hangUp("Call cancelled"); }
                            }).show();
                    return;
                }
                callId = result.optString("call_id", "");
                if (callId.length() == 0) { showFailure("Server did not create a WhatsApp call session"); return; }
                connectAudio();
            }
        }.executeOnExecutor(AppExecutors.CALLS);
    }

    private void connectAudio() {
        try {
            ApiClient api = new ApiClient(this);
            Request request = new Request.Builder().url(api.getVoiceCallWebSocketUrl(callId))
                    .header("Authorization", "Bearer " + api.getApiToken()).build();
            webSocketClient = new OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build();
            webSocket = webSocketClient.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    runOnUiThread(new Runnable() { public void run() { stateText.setText("Ringing…"); } });
                }
                @Override public void onMessage(WebSocket socket, String text) {
                    JSONObject message;
                    try { message = new JSONObject(text); }
                    catch (Exception ignored) { return; }
                    final String type = message.optString("type", "");
                    if ("connected".equals(type)) {
                        runOnUiThread(new Runnable() { public void run() { onCallConnected(); } });
                    } else if ("ringing".equals(type)) {
                        runOnUiThread(new Runnable() { public void run() { stateText.setText("Ringing…"); } });
                    } else if ("failed".equals(type)) {
                        final String detail = message.optString("message", "Voice call failed");
                        runOnUiThread(new Runnable() { public void run() { showFailure(detail); } });
                    } else if ("ended".equals(type)) {
                        final String reason = message.optString("reason", "Call ended");
                        runOnUiThread(new Runnable() { public void run() { if (!failed) hangUp(reason); } });
                    }
                }
                @Override public void onMessage(WebSocket socket, ByteString audio) {
                    AudioTrack track = player;
                    try {
                        if (track != null && active) {
                            byte[] pcm = audio.toByteArray();
                            PcmResampler resampler = playbackResampler;
                            if (resampler != null) pcm = resampler.convert(pcm, pcm.length);
                            track.write(pcm, 0, pcm.length);
                        }
                    }
                    catch (Exception error) { runOnUiThread(new Runnable() { public void run() { showFailure("Device audio playback stopped"); } }); }
                }
                @Override public void onFailure(WebSocket socket, Throwable failure, Response response) {
                    final String detail = response == null ? failure.getMessage()
                            : "Call audio connection failed (HTTP " + response.code() + ")";
                    runOnUiThread(new Runnable() { public void run() { showFailure(detail == null ? "Call audio connection failed" : detail); } });
                }
                @Override public void onClosed(WebSocket socket, int code, String reason) {
                    if (!finished && !failed) {
                        final String detail = reason == null || reason.length() == 0 ? "Call audio connection closed (" + code + ")" : reason;
                        runOnUiThread(new Runnable() { public void run() { showFailure(detail); } });
                    }
                }
            });
        } catch (Exception failure) { showFailure(failure.getMessage()); }
    }

    private void onCallConnected() {
        if (finished || failed || callConnected) return;
        callConnected = true;
        active = true;
        connectedAt = System.currentTimeMillis();
        recordCall("connected");
        stateText.setText("Connected");
        handler.post(clock);
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(speakerOn);
            for (int rate : new int[] { SAMPLE_RATE, 44100, 48000 }) {
                int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                try {
                    AudioTrack candidate = new AudioTrack(AudioManager.STREAM_VOICE_CALL, rate, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(rate / 25, minimum), AudioTrack.MODE_STREAM);
                    if (candidate.getState() == AudioTrack.STATE_INITIALIZED) {
                        player = candidate;
                        playbackResampler = rate == SAMPLE_RATE ? null : new PcmResampler(SAMPLE_RATE, rate);
                        break;
                    }
                    candidate.release();
                } catch (Exception ignored) { }
            }
            if (player == null) throw new IllegalStateException("Device speaker does not support voice audio");
            player.play();
            int captureRate = SAMPLE_RATE;
            for (int rate : new int[] { SAMPLE_RATE, 44100, 8000 }) {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                for (int source : new int[] { MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC }) {
                    try {
                        AudioRecord candidate = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(rate / 25, minimum));
                        if (candidate.getState() == AudioRecord.STATE_INITIALIZED) { recorder = candidate; captureRate = rate; break; }
                        candidate.release();
                    } catch (Exception ignored) { }
                }
                if (recorder != null) break;
            }
            if (recorder == null) throw new IllegalStateException("Microphone unavailable. Check the app's microphone permission.");
            recorder.startRecording();
            final AudioRecord activeRecorder = recorder;
            final int frameSize = captureRate / 50 * 2;
            final PcmResampler captureResampler = captureRate == SAMPLE_RATE ? null : new PcmResampler(captureRate, SAMPLE_RATE);
            Thread capture = new Thread(new Runnable() {
                public void run() {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                    byte[] frame = new byte[frameSize];
                    while (active && !finished) {
                        int count;
                        try { count = activeRecorder.read(frame, 0, frame.length); }
                        catch (Exception stopped) {
                            if (active) runOnUiThread(new Runnable() { public void run() { showFailure("Microphone connection stopped"); } });
                            break;
                        }
                        if (count <= 0) {
                            if (active) runOnUiThread(new Runnable() { public void run() { showFailure("Microphone stopped providing audio"); } });
                            break;
                        }
                        byte[] sending = captureResampler == null ? Arrays.copyOf(frame, count) : captureResampler.convert(frame, count);
                        if (muted) Arrays.fill(sending, (byte) 0);
                        WebSocket socket = webSocket;
                        if (socket != null && socket.queueSize() > 6400) continue;
                        if (socket == null || !socket.send(ByteString.of(sending))) break;
                    }
                }
            }, "Q20-call-microphone");
            capture.start();
        } catch (Exception failure) { showFailure("Audio device error: " + failure.getMessage()); }
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speakerOn);
        speakerButton.setText(speakerOn ? "Earpiece" : "Speaker");
    }

    private void showFailure(final String message) {
        if (finished || failed) return;
        failed = true;
        recordCall("failed");
        if (webSocket != null) webSocket.cancel();
        stateText.setText("Call failed: " + (message == null ? "Unknown server error" : message));
        stopAudio();
        new AsyncTask<Void, Void, Void>() {
            protected Void doInBackground(Void... ignored) {
                ApiClient api = new ApiClient(CallActivity.this);
                try { api.reportCallFailure(message); } catch (Exception ignoredError) { }
                try { api.endVoiceCall(callId); } catch (Exception ignoredError) { }
                return null;
            }
        }.executeOnExecutor(AppExecutors.CALLS);
    }

    private void hangUp(String reason) {
        if (finished) return;
        finished = true;
        recordCall(failed ? "failed" : callConnected ? "completed" : historyIncoming ? "declined" : "cancelled");
        stateText.setText(reason == null ? "Call ended" : reason);
        stopAudio();
        if (webSocket != null) {
            try { webSocket.send("{\"type\":\"hangup\"}"); webSocket.close(1000, "Call ended"); } catch (Exception ignored) { }
        }
        final String id = callId;
        new AsyncTask<Void, Void, Void>() {
            protected Void doInBackground(Void... ignored) {
                try { new ApiClient(CallActivity.this).endVoiceCall(id); } catch (Exception ignoredError) { }
                return null;
            }
        }.executeOnExecutor(AppExecutors.CALLS);
        handler.postDelayed(new Runnable() { public void run() { finish(); } }, 500);
    }

    private void stopAudio() {
        active = false;
        handler.removeCallbacks(clock);
        AudioRecord input = recorder;
        recorder = null;
        if (input != null) { try { input.stop(); } catch (Exception ignored) { } try { input.release(); } catch (Exception ignored) { } }
        AudioTrack output = player;
        player = null;
        if (output != null) { try { output.pause(); } catch (Exception ignored) { } try { output.flush(); } catch (Exception ignored) { } try { output.stop(); } catch (Exception ignored) { } try { output.release(); } catch (Exception ignored) { } }
        if (audioManager != null) { audioManager.setSpeakerphoneOn(false); audioManager.setMode(AudioManager.MODE_NORMAL); }
    }

    private void recordCall(String outcome) {
        LocalStore.get(this).saveCall(callId.length()>0?callId:localHistoryId,peerJid,peerName,historyIncoming?"Incoming":"Outgoing",historyStartedAt,connectedAt,
                "connected".equals(outcome)?0:System.currentTimeMillis(),outcome);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { hangUp("Call ended"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        if (confirmationDialog != null) confirmationDialog.dismiss();
        if (!finished) hangUp("Call ended");
        stopAudio();
        if (webSocketClient != null) webSocketClient.dispatcher().executorService().shutdown();
        if (callWakeLock != null && callWakeLock.isHeld()) callWakeLock.release();
        super.onDestroy();
    }
}


