package com.aghani.q20messenger;

import org.json.JSONObject;
import java.util.Locale;

/** Presentation bounds and metadata shared by media rows and their viewer. */
final class MediaPresentation {
    private MediaPresentation() { }

    static int[] previewSize(int width, int height, int availableWidth, int screenHeight, boolean sticker) {
        int maxWidth = Math.max(48, Math.min(sticker ? 112 : 216, availableWidth));
        int maxHeight = Math.max(48, Math.min(sticker ? 112 : 176, screenHeight / 2));
        // An unavailable thumbnail must not reserve most of the conversation.
        if (width <= 0 || height <= 0) return new int[] { Math.min(maxWidth, 160), Math.min(maxHeight, 88) };
        float scale = Math.min(maxWidth / (float) width, maxHeight / (float) height);
        return new int[] { Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)) };
    }

    static String filename(JSONObject message) {
        String name = message.optString("media_original_filename", "");
        JSONObject rich = message.optJSONObject("rich");
        JSONObject media = rich == null ? null : rich.optJSONObject("media");
        if (name.length() == 0 && media != null) name = media.optString("filename", "");
        if (name.length() == 0) name = message.optString("media_filename", "");
        return safeFilename(name.length() == 0 ? "Document" : name);
    }

    static String documentTitle(JSONObject message) {
        String name = filename(message);
        if (!name.equals("Document") && !name.matches("[0-9]+(?:\\.[A-Za-z0-9]+)?")) return name;
        String mime = message.optString("media_mimetype", "");
        String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext == null) { int dot = name.lastIndexOf('.'); ext = dot >= 0 ? name.substring(dot + 1) : ""; }
        return ext.length() > 0 ? ext.toUpperCase(Locale.US) + " document" : "Document · filename unavailable";
    }

    static String safeFilename(String name) {
        String value = name == null ? "" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f\\x7f:*?\"<>|]", "_").trim();
        if (value.length() == 0 || value.equals(".") || value.equals("..")) value = "document.bin";
        if (value.length() > 150) value = value.substring(value.length() - 150);
        return value;
    }

    static String fileDetails(JSONObject message) {
        String name = filename(message);
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.US) : "FILE";
        long bytes = message.optLong("media_filesize", 0);
        if (bytes <= 0) return extension;
        String size = bytes >= 1048576 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
                : bytes >= 1024 ? String.format(Locale.US, "%.0f KB", bytes / 1024.0) : bytes + " B";
        return extension + " · " + size;
    }

    static String caption(JSONObject message) {
        JSONObject rich = message.optJSONObject("rich");
        String text = rich == null ? message.optString("message", "") : rich.optString("caption", "");
        if (text.matches("(?i)^(image|photo|video|audio|ptt|voice note|document|sticker|gif)( (received|sent))?$")) return "";
        return text;
    }

    static int duration(JSONObject message) {
        JSONObject rich = message.optJSONObject("rich");
        JSONObject media = rich == null ? null : rich.optJSONObject("media");
        return media == null ? 0 : Math.max(0, media.optInt("duration", 0));
    }

    static String time(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
