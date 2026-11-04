package com.aghani.q20messenger;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONObject;

/** Compact voice-note row: play/pause, real playback progress, elapsed/total time. */
final class AudioMessageView extends LinearLayout implements AudioPlaybackController.Listener {
    private final AudioPlaybackController controller;
    private final long id;
    private final ImageButton play;
    private final SeekBar progress;
    private final TextView time;
    private final int knownDuration;
    private boolean seeking;
    AudioMessageView(Context context, final JSONObject message, final AudioPlaybackController controller) {
        super(context); this.controller = controller; this.id = message.optLong("_id", -1);
        knownDuration = MediaPresentation.duration(message) * 1000;
        setOrientation(HORIZONTAL); setGravity(android.view.Gravity.CENTER_VERTICAL);
        play = new ImageButton(context);
        play.setBackgroundDrawable(Ui.rounded(context.getResources().getColor(R.color.green_dark), 22, context));
        play.setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8));
        play.setColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
        addView(play, new LinearLayout.LayoutParams(Ui.dp(context, 34), Ui.dp(context, 34)));
        LinearLayout info = Ui.vertical(context);
        progress = new SeekBar(context);
        android.graphics.drawable.GradientDrawable thumb = Ui.rounded(context.getResources().getColor(R.color.green_dark), 5, context);
        thumb.setSize(Ui.dp(context, 10), Ui.dp(context, 10));
        progress.setThumb(thumb);
        progress.setThumbOffset(Ui.dp(context, 5));
        progress.setPadding(Ui.dp(context, 5), 0, Ui.dp(context, 5), 0);
        progress.getProgressDrawable().setColorFilter(context.getResources().getColor(R.color.green_dark), PorterDuff.Mode.SRC_IN);
        if (progress.getThumb() != null) progress.getThumb().setColorFilter(context.getResources().getColor(R.color.green_dark), PorterDuff.Mode.SRC_IN);
        info.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 24)));
        time = Ui.text(context, "", 10, context.getResources().getColor(R.color.muted));
        time.setPadding(Ui.dp(context, 6), 0, 0, 0); time.setSingleLine(true); time.setIncludeFontPadding(false);
        info.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 22)));
        addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        play.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { controller.toggle(message); } });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; controller.seek(bar.getProgress()); }
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) time.setText(MediaPresentation.time(value)); }
        });
        changed();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); controller.add(this); }
    @Override protected void onDetachedFromWindow() { controller.remove(this); super.onDetachedFromWindow(); }
    public void changed() {
        boolean selected = controller.isSelected(id);
        boolean loading = selected && controller.isLoading();
        boolean playing = selected && controller.isPlaying();
        int duration = selected && controller.duration() > 0 ? controller.duration() : knownDuration;
        int position = selected ? controller.position() : 0;
        play.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        play.setContentDescription(loading ? "Downloading audio" : playing ? "Pause voice note" : "Play voice note");
        play.setEnabled(!loading);
        progress.setEnabled(selected && !loading && duration > 0);
        if (!seeking) { progress.setMax(Math.max(1, duration)); progress.setProgress(position); }
        time.setText(loading ? "Downloading…" : duration > 0 ? MediaPresentation.time(position) + " / " + MediaPresentation.time(duration) : "Voice message");
    }
}
