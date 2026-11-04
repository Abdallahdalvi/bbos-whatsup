package com.aghani.q20messenger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;

public class MediaViewerActivity extends Activity {
    private MediaPlayer player;
    private android.widget.Button playButton;
    private VideoView videoPlayer;
    private ImageView imagePlayer;
    private DocumentPreview documentPreview;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try { showMedia(); }
        catch (RuntimeException error) { showMediaFailure(error); }
        catch (LinkageError error) { showMediaFailure(error); }
        catch (OutOfMemoryError error) { showMediaFailure(error); }
    }

    private void showMediaFailure(Throwable error) {
        CrashReporter.report(this,"media_viewer",error);
        LinearLayout fallback=Ui.vertical(this);fallback.setPadding(12,12,12,12);
        TextView info=Ui.text(this,"Unable to open this file on the device. The error was recorded.",14,android.graphics.Color.WHITE);fallback.addView(info);
        android.widget.Button back=Ui.button(this,"Back to chat");fallback.addView(back);back.setOnClickListener(new View.OnClickListener(){public void onClick(View view){finish();}});
        setContentView(fallback);
    }

    private void showMedia() {
        String path = getIntent().getStringExtra("file_path");
        String type = getIntent().getStringExtra("media_type");
        if (path == null || type == null) {
            finish();
            return;
        }
        final File file = new File(path);
        if (!file.exists()) {
            SafeToast.makeText(this, "Media file is no longer cached.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        LinearLayout header = Ui.horizontal(this);
        header.setBackgroundColor(getResources().getColor(R.color.ink));
        header.setPadding(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        android.widget.ImageButton back = new android.widget.ImageButton(this);
        back.setImageResource(R.drawable.ic_back); back.setColorFilter(android.graphics.Color.WHITE);
        back.setBackgroundColor(android.graphics.Color.TRANSPARENT); back.setContentDescription("Back to conversation");
        back.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { finish(); }
        });
        TextView title = Ui.text(this, file.getName(), 14, android.graphics.Color.WHITE);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        header.addView(title);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (getIntent().getBooleanExtra("showing_copy", false)) {
            TextView original = Ui.text(this, "Original", 12, android.graphics.Color.WHITE);
            original.setGravity(android.view.Gravity.CENTER); original.setFocusable(true);
            original.setContentDescription("Download and view original quality");
            header.addView(original, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 36)));
            original.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { viewOriginal(); } });
        }
        final android.widget.ImageButton save = new android.widget.ImageButton(this);
        save.setImageResource(android.R.drawable.ic_menu_save); save.setColorFilter(android.graphics.Color.WHITE);
        save.setBackgroundColor(android.graphics.Color.TRANSPARENT); save.setContentDescription("Save to Downloads");
        save.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        header.addView(save, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                save.setEnabled(false);
                new android.os.AsyncTask<Void, Void, String>() {
                    protected String doInBackground(Void... ignored) {
                        try {
                            ApiClient api = new ApiClient(MediaViewerActivity.this);
                            File source = getIntent().getBooleanExtra("showing_copy", false) ? originalFile(api) : file;
                            String name = getIntent().getStringExtra("original_name");
                            api.saveToDownloads(source, name == null || name.equals("Document") ? source.getName() : name);
                            return "Saved original to Downloads/Q20Messenger";
                        }
                        catch (Exception error) { return "Save failed: " + error.getMessage(); }
                    }
                    protected void onPostExecute(String result) { save.setEnabled(true); SafeToast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show(); }
                }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
            }
        });
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        if ("image".equals(type) || "gif".equals(type)) {
            ImageView image;
            try { image = new com.github.chrisbanes.photoview.PhotoView(this); }
            catch (LinkageError unsupported) { CrashReporter.report(this,"image_zoom_compatibility",unsupported); image=new ImageView(this); }
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setFocusable(true); imagePlayer=image;
            Bitmap bitmap = decodeSampled(file.getAbsolutePath(), 1200, 1200);
            if (bitmap != null) image.setImageBitmap(bitmap);
            root.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else if ("video".equals(type)) {
            final FrameLayout stage = new FrameLayout(this);
            stage.setBackgroundColor(android.graphics.Color.BLACK);
            final VideoView video = new VideoView(this);
            videoPlayer = video;
            MediaController controller = new MediaController(this);
            video.setMediaController(controller);
            controller.setAnchorView(video);
            video.setVideoURI(Uri.fromFile(file));
            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER);
            stage.addView(video, videoParams);
            root.addView(stage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mediaPlayer) {
                    mediaPlayer.setLooping(false);
                    int sourceWidth = mediaPlayer.getVideoWidth();
                    int sourceHeight = mediaPlayer.getVideoHeight();
                    int availableWidth = stage.getWidth();
                    int availableHeight = stage.getHeight();
                    if (sourceWidth <= 0 || sourceHeight <= 0) return;
                    if (availableWidth <= 0 || availableHeight <= 0) {
                        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                        availableWidth = metrics.widthPixels;
                        availableHeight = metrics.heightPixels - Ui.dp(MediaViewerActivity.this, 58);
                    }
                    float scale = Math.min(availableWidth / (float) sourceWidth,
                            availableHeight / (float) sourceHeight);
                    int width = Math.max(1, Math.round(sourceWidth * scale));
                    int height = Math.max(1, Math.round(sourceHeight * scale));
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height,
                            android.view.Gravity.CENTER);
                    video.setLayoutParams(params);
                }
            });
            video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                    SafeToast.makeText(MediaViewerActivity.this, "This video format cannot be played by the device.", Toast.LENGTH_LONG).show();
                    return true;
                }
            });
            video.setFocusable(true);
            video.start();
        } else if ("document".equals(type)) {
            String mime = getIntent().getStringExtra("mime_type");
            if (mime == null || mime.length() == 0 || mime.equals("application/octet-stream")) mime = mimeFor(file.getName());
            final String documentMime = mime;
            if(DocumentPreview.supports(file,documentMime)) {
                documentPreview=new DocumentPreview(this,file,documentMime);imagePlayer=documentPreview.image();
                root.addView(documentPreview,new LinearLayout.LayoutParams(-1,0,1));
            } else {
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            TextView details = Ui.text(this, file.getName() + "\n" + String.format(java.util.Locale.US, "%.1f KB", file.length() / 1024.0), 14, android.graphics.Color.WHITE);
            details.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            if (documentMime.startsWith("text/") || documentMime.equals("application/json")) {
                try {
                    java.io.InputStream input = new java.io.FileInputStream(file);
                    byte[] bytes = new byte[(int) Math.min(file.length(), 262144)];
                    int count = 0, read;
                    try { while (count < bytes.length && (read = input.read(bytes, count, bytes.length - count)) > 0) count += read; } finally { input.close(); }
                    details.setText(new String(bytes, 0, count, "UTF-8") + (file.length() > bytes.length ? "\n…Preview limited to 256 KB. Open the full file below." : ""));
                    details.setTextIsSelectable(true);
                } catch (Exception error) { details.append("\nPreview unavailable"); }
            }
            scroll.addView(details); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            android.widget.Button open = Ui.button(this, "Open in document app");
            root.addView(open, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
            open.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { openDocument(file, documentMime); } });
            }
            // Supported PDF and workbook types open here without another app.
        } else {
            TextView info = Ui.text(this, "Voice note / audio\n" + file.getName(), 16, android.graphics.Color.WHITE);
            info.setGravity(android.view.Gravity.CENTER);
            root.addView(info, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            playButton = Ui.button(this, "Play audio");
            root.addView(playButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
            playButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { toggleAudio(file.getAbsolutePath()); }
            });
        }
        setContentView(root);
        root.post(new Runnable(){public void run(){HardwareNavigation.decorate(root);}});
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event){return HardwareNavigation.scroll(this,event)||super.dispatchGenericMotionEvent(event);}
    @Override public boolean dispatchTrackballEvent(android.view.MotionEvent event){return HardwareNavigation.trackball(this,event)||super.dispatchTrackballEvent(event);}
    @Override public boolean onKeyDown(int key,android.view.KeyEvent event){
        if(imagePlayer instanceof com.github.chrisbanes.photoview.PhotoView && imagePlayer.hasFocus() && (key==android.view.KeyEvent.KEYCODE_DPAD_CENTER || key==android.view.KeyEvent.KEYCODE_ENTER)){
            com.github.chrisbanes.photoview.PhotoView photo=(com.github.chrisbanes.photoview.PhotoView)imagePlayer;photo.setScale(photo.getScale()>1.1f?1f:2f);return true;
        }
        if(imagePlayer instanceof com.github.chrisbanes.photoview.PhotoView && imagePlayer.hasFocus()){
            com.github.chrisbanes.photoview.PhotoView photo=(com.github.chrisbanes.photoview.PhotoView)imagePlayer;
            if(photo.getScale()>1.1f){float step=Ui.dp(this,32);if(key==android.view.KeyEvent.KEYCODE_DPAD_LEFT){photo.getAttacher().onDrag(step,0);return true;}if(key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT){photo.getAttacher().onDrag(-step,0);return true;}if(key==android.view.KeyEvent.KEYCODE_DPAD_UP){photo.getAttacher().onDrag(0,step);return true;}if(key==android.view.KeyEvent.KEYCODE_DPAD_DOWN){photo.getAttacher().onDrag(0,-step);return true;}}
        }
        return super.onKeyDown(key,event);
    }

    private String mimeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        int dot = lower.lastIndexOf('.');
        String detected = dot >= 0 ? android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot + 1)) : null;
        if (detected != null) return detected;
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private File originalFile(ApiClient api) throws Exception {
        String name = getIntent().getStringExtra("original_name");
        String type = getIntent().getStringExtra("media_type");
        String extension = "video".equals(type) ? ".mp4" : "image".equals(type) ? ".jpg" : ".bin";
        if (name != null) { int dot = name.lastIndexOf('.'); if (dot >= 0 && name.substring(dot).matches("\\.[A-Za-z0-9]{1,10}")) extension = name.substring(dot); }
        return api.downloadVariant(getIntent().getLongExtra("message_id", -1), "original", extension);
    }

    private void viewOriginal() {
        if (videoPlayer != null) videoPlayer.pause();
        new android.os.AsyncTask<Void, Void, File>() {
            private String error;
            protected File doInBackground(Void... ignored) {
                try { return originalFile(new ApiClient(MediaViewerActivity.this)); }
                catch (Exception failure) { error = failure.getMessage(); return null; }
            }
            protected void onPostExecute(File original) {
                if (isFinishing()) return;
                if (original == null) { SafeToast.makeText(MediaViewerActivity.this, "Original unavailable: " + error, Toast.LENGTH_LONG).show(); return; }
                Intent intent = new Intent(getIntent());
                intent.setClass(MediaViewerActivity.this, MediaViewerActivity.class);
                intent.putExtra("file_path", original.getAbsolutePath()); intent.putExtra("showing_copy", false);
                startActivity(intent); finish();
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void openDocument(File file, String mime) {
        try {
            Uri uri = android.support.v4.content.FileProvider.getUriForFile(this, getPackageName() + ".media", file);
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.setClipData(android.content.ClipData.newRawUri(file.getName(), uri));
            startActivity(open);
        } catch (android.content.ActivityNotFoundException missing) {
            SafeToast.makeText(this, "Install a viewer for this document type. You can still save the original with Save.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            SafeToast.makeText(this, "Could not open document: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleAudio(String path) {
        try {
            if (player != null && player.isPlaying()) {
                player.pause();
                playButton.setText("Resume audio");
                return;
            }
            if (player != null) {
                player.start();
                playButton.setText("Pause audio");
                return;
            }
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mediaPlayer) {
                    mediaPlayer.start();
                    playButton.setText("Pause audio");
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mediaPlayer) { playButton.setText("Play again"); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                    SafeToast.makeText(MediaViewerActivity.this, "Audio playback failed.", Toast.LENGTH_LONG).show();
                    return true;
                }
            });
            player.prepareAsync();
            playButton.setText("Loading audio...");
        } catch (Exception error) {
            SafeToast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap decodeSampled(String path, int requiredWidth, int requiredHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > requiredWidth * 2
                || bounds.outHeight / sample > requiredHeight * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, options);
    }

    @Override
    protected void onPause() {
        if (videoPlayer != null && videoPlayer.isPlaying()) videoPlayer.pause();
        if (player != null && player.isPlaying()) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if(documentPreview!=null)documentPreview.close();
        if (videoPlayer != null) videoPlayer.stopPlayback();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        super.onDestroy();
    }
}
