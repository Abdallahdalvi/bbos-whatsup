package com.aghani.q20messenger;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Handler;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.util.HashSet;

/** One local-file player per conversation, with a bounded asynchronous download. */
final class AudioPlaybackController {
    interface Listener { void changed(); }
    private final Context context;
    private final Handler handler = new Handler();
    private final HashSet<Listener> listeners = new HashSet<Listener>();
    private MediaPlayer player;
    private long id = -1;
    private int generation;
    private boolean loading;
    private boolean closed;
    private final Runnable clock = new Runnable() {
        public void run() { notifyListeners(); if (isPlaying()) handler.postDelayed(this, 300); }
    };
    AudioPlaybackController(Context context) { this.context = context; }
    void add(Listener listener) { listeners.add(listener); listener.changed(); }
    void remove(Listener listener) { listeners.remove(listener); }
    boolean isSelected(long messageId) { return id == messageId; }
    boolean isLoading() { return loading; }
    boolean isPlaying() { try { return player != null && player.isPlaying(); } catch (IllegalStateException error) { return false; } }
    int position() { try { return player == null ? 0 : player.getCurrentPosition(); } catch (IllegalStateException error) { return 0; } }
    int duration() { try { return player == null || loading ? 0 : player.getDuration(); } catch (IllegalStateException error) { return 0; } }
    void seek(int position) { if (player != null && !loading) { player.seekTo(position); notifyListeners(); } }
    void pause() { if (loading) { generation++; release(); id = -1; } else if (isPlaying()) player.pause(); handler.removeCallbacks(clock); notifyListeners(); }
    void close() { closed = true; generation++; listeners.clear(); release(); }
    private void release() { handler.removeCallbacks(clock); final MediaPlayer retired=player;player=null;loading=false;if(retired!=null)AppExecutors.MEDIA_CLEANUP.execute(new Runnable(){public void run(){try{retired.release();}catch(RuntimeException ignored){}}}); }
    private void notifyListeners() { for (Listener listener : new HashSet<Listener>(listeners)) listener.changed(); }

    void toggle(final JSONObject message) {
        if (closed) return;
        final long messageId = message.optLong("_id", -1);
        if (isSelected(messageId)) {
            if (loading) return;
            if (player != null) {
                if (isPlaying()) pause(); else { player.start(); handler.post(clock); }
                notifyListeners(); return;
            }
        }
        release(); id = messageId; loading = true;
        final int request = ++generation;
        notifyListeners();
        new AsyncTask<Void, Void, File>() {
            private String error;
            protected File doInBackground(Void... ignored) {
                try { return new ApiClient(context).downloadMedia(messageId, message.optString("chatType", "ptt")); }
                catch (Exception failure) { error = failure.getMessage(); return null; }
            }
            protected void onPostExecute(File file) {
                if (closed || generation != request) return;
                if (file == null) { failed(error); return; }
                try {
                    player = new MediaPlayer();
                    player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    player.setDataSource(file.getAbsolutePath());
                    player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        public void onPrepared(MediaPlayer ready) { if(closed||generation!=request||player!=ready)return;loading = false; ready.start(); handler.post(clock); }
                    });
                    player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        public void onCompletion(MediaPlayer complete) { if(closed||player!=complete)return;handler.removeCallbacks(clock); notifyListeners(); }
                    });
                    player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        public boolean onError(MediaPlayer broken, int what, int extra) { if(!closed&&player==broken)failed("This audio format could not be played. You can save the original from the message menu."); return true; }
                    });
                    player.prepareAsync();
                } catch (Exception failure) { failed(failure.getMessage()); }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void failed(String error) { release(); id = -1; notifyListeners(); SafeToast.makeText(context, error == null ? "Audio unavailable" : error, Toast.LENGTH_LONG).show(); }
}
