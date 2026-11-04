package com.aghani.q20messenger;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class ApiClient {
    private final String baseUrl;
    private final Context appContext;
    private final String ownNumber;
    private final String ownJid;
    private final String apiToken;

    public ApiClient(Context context) {
        appContext = context.getApplicationContext();
        baseUrl = AppPrefs.cleanServer(AppPrefs.getServerUrl(context));
        ownNumber = AppPrefs.getPhoneNumber(context);
        ownJid = ownNumber + "@c.us";
        apiToken = AppPrefs.getApiToken(context);
    }

    public String getOwnJid() {
        return ownJid;
    }

    public JSONObject getLoginStatus() throws Exception {
        return getJson("/login-status/" + pathSegment(ownNumber));
    }

    public JSONObject getHealth() throws Exception { return getJson("/api/v2/health"); }

    public JSONObject getAccount() throws Exception { return getJson("/api/v2/account"); }

    public JSONObject getBootstrap(long afterId, int limit, long snapshotMaxId) throws Exception {
        String path = "/api/v2/sync/bootstrap?after_id=" + afterId + "&limit=" + limit;
        if (snapshotMaxId >= 0) path += "&snapshot_max_id=" + snapshotMaxId;
        return getJson(path);
    }

    public JSONObject getUpdates(long afterId, int limit) throws Exception {
        return getJson("/api/v2/sync/updates?after_id=" + afterId + "&limit=" + limit);
    }

    public JSONObject getChatMessages(String chatJid, long beforeId, int limit) throws Exception {
        return getJson("/api/v2/chats/" + pathSegment(chatJid) + "/messages?before_id=" + beforeId + "&limit=" + limit);
    }

    public JSONObject getChatPage(String chatJid, long beforeTime, long beforeId, int limit) throws Exception {
        return getJson("/api/v2/chats/"+pathSegment(chatJid)+"/messages?before_timestamp_ms="+beforeTime+"&before_id="+beforeId+"&limit="+limit);
    }

    public JSONObject startHistoryImport() throws Exception {
        return postJson("/api/v2/history/import", new JSONObject());
    }

    public JSONObject getHistoryImportStatus() throws Exception {
        return getJson("/api/v2/history/import/status");
    }

    public JSONObject login() throws Exception {
        return getJson("/api/login/" + pathSegment(ownNumber));
    }

    public JSONObject getChats() throws Exception {
        return getJson("/api/v2/chats");
    }

    public JSONObject getContacts(String search) throws Exception {
        return getContacts(search, 0);
    }

    public JSONObject getContacts(String search, int page) throws Exception {
        String query = "?limit=200&page=" + page + "&search=" + queryValue(search == null ? "" : search);
        try {
            return getJson("/api/v2/contacts" + query);
        } catch (IOException error) {
            // Older Nokia4Ever deployments do not have the v2 router yet. Keep
            // the contacts screen usable through the server's existing route.
            if (error.getMessage() == null || !error.getMessage().contains("HTTP 404")) throw error;
            String legacyQuery = "?page=" + page + "&page_size=200&search_term="
                    + queryValue(search == null ? "" : search);
            JSONObject legacy = getJson("/api/contacts/" + pathSegment(ownNumber) + legacyQuery);
            int total = legacy.optInt("count", 0);
            legacy.put("page", page);
            legacy.put("has_more", (page + 1) * 200 < total);
            return legacy;
        }
    }

    public JSONObject getMessages(String peerJid) throws Exception {
        return getChatMessages(peerJid, Long.MAX_VALUE, 100);
    }

    public JSONObject sendText(String peerJid, String text) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("chat_jid", peerJid);
        payload.put("message", text);
        return postJson("/api/v2/messages", payload);
    }

    public JSONObject createCallLink(String peerJid, String type) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("chat_jid", peerJid);
        payload.put("type", type);
        return postJson("/api/v2/calls/link", payload);
    }

    public JSONObject startVoiceCall(String peerJid) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("chat_jid", peerJid);
        return postJson("/api/v2/calls/start", payload);
    }

    public JSONObject confirmVoiceCall(String requestId) throws Exception {
        JSONObject payload = new JSONObject(); payload.put("request_id", requestId);
        return postJson("/api/v2/calls/confirm", payload);
    }

    public JSONObject votePoll(long id, JSONArray options) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("options", options);
        return postJson("/api/v2/polls/" + id + "/vote", payload);
    }

    public JSONObject endVoiceCall(String callId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("call_id", callId);
        return postJson("/api/v2/calls/end", payload);
    }

    public void reportCallFailure(String message) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("message", message);
        postJson("/api/v2/calls/report", payload);
    }
    public void reportClientDiagnostic(JSONObject report) throws Exception { postJson("/api/v2/client/diagnostics",report); }

    public JSONObject rejectVoiceCall(String callId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("call_id", callId);
        return postJson("/api/v2/calls/reject", payload);
    }

    public JSONObject getVoiceCallStatus() throws Exception {
        return getJson("/api/v2/calls/status");
    }

    public String getVoiceCallWebSocketUrl(String callId) throws Exception {
        String encoded = URLEncoder.encode(callId, "UTF-8");
        String url = baseUrl + "/api/v2/calls/stream?call_id=" + encoded;
        if (url.startsWith("https://")) return "wss://" + url.substring(8);
        if (url.startsWith("http://")) return "ws://" + url.substring(7);
        throw new IOException("Server URL must start with http:// or https://");
    }

    public String getApiToken() { return apiToken; }

    public JSONObject uploadMedia(Uri uri, String peerJid) throws Exception {
        return uploadMedia(uri, peerJid, false);
    }

    public JSONObject uploadMedia(Uri uri, String peerJid, boolean originalQualityVideo) throws Exception {
        return uploadMedia(uri, peerJid, originalQualityVideo, false);
    }

    public JSONObject uploadMedia(Uri uri, String peerJid, boolean sendAsDocument, boolean voiceNote) throws Exception {
        ContentResolver resolver = appContext.getContentResolver();
        String mime = resolver.getType(uri);
        String fileName = MediaPresentation.safeFilename(displayName(resolver, uri));
        if (mime == null || "application/octet-stream".equalsIgnoreCase(mime)) {
            mime = mimeFromName(fileName);
        }
        if (mime == null || mime.indexOf('\r') >= 0 || mime.indexOf('\n') >= 0) mime = "application/octet-stream";

        String boundary = "----Q20MessengerBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = openConnection("/api/v2/media", "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        connection.setChunkedStreamingMode(64 * 1024);

        OutputStream out = new BufferedOutputStream(connection.getOutputStream());
        writeField(out, boundary, "chat_jid", peerJid);
        writeField(out, boundary, "send_as_document", sendAsDocument ? "true" : "false");
        writeField(out, boundary, "voice_note", voiceNote ? "true" : "false");

        String safeName = fileName.replace("\"", "_").replace("\r", "_").replace("\n", "_");
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        out.write(header.getBytes("UTF-8"));

        InputStream in = new BufferedInputStream(resolver.openInputStream(uri));
        if (in == null) throw new IOException("Unable to open selected file");
        try {
            copy(in, out, 64 * 1024);
        } finally {
            in.close();
        }
        out.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        out.flush();
        out.close();

        return readJsonResponse(connection);
    }

    public File downloadMedia(long messageId, String chatType) throws Exception {
        String[] extensions = extensionsFor(chatType);
        File folder = new File(appContext.getCacheDir(), "media");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Cannot create media cache folder");
        }

        IOException lastError = null;
        for (int i = 0; i < extensions.length; i++) {
            String extension = extensions[i];
            File destination = new File(folder, messageId + extension);
            if (destination.exists() && destination.length() > 0) return destination;

            HttpURLConnection connection = null;
            InputStream in = null;
            FileOutputStream fileOut = null;
            try {
                String variant = extension.length() == 0 ? "" : "?variant=" + extension.substring(1);
                connection = openConnection("/api/v2/media/" + messageId + variant, "GET");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_NOT_FOUND) continue;
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Media download returned HTTP " + code);
                }

                in = new BufferedInputStream(connection.getInputStream());
                File temporary = File.createTempFile("media-" + messageId + "-", extension + ".part", folder);
                fileOut = new FileOutputStream(temporary);
                copy(in, fileOut, 16 * 1024);
                fileOut.flush();
                fileOut.close();
                fileOut = null;
                in.close();
                in = null;

                if (temporary.length() == 0) {
                    temporary.delete();
                    throw new IOException("Downloaded media was empty");
                }
                if (destination.exists()) destination.delete();
                if (!temporary.renameTo(destination)) {
                    copyFile(temporary, destination);
                    temporary.delete();
                }
                return destination;

            } catch (IOException error) {
                lastError = error;
                if (error.getMessage() != null && error.getMessage().contains("HTTP 404")) continue;
                throw error;
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) { }
                if (fileOut != null) try { fileOut.close(); } catch (Exception ignored) { }
                if (connection != null) connection.disconnect();
            }
        }

        if (lastError != null) throw lastError;
        throw new IOException("No media file found for message " + messageId);
    }

    public synchronized File downloadVariant(long id, String variant, String extension) throws Exception {
        File folder = new File(appContext.getCacheDir(), "media");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create media cache");
        File destination = new File(folder, id + "-" + variant + extension);
        if (destination.isFile() && destination.length() > 0) return destination;
        HttpURLConnection connection = openConnection("/api/v2/media/" + id + "?variant=" + variant, "GET");
        connection.setReadTimeout(240000);
        File temporary = File.createTempFile("variant-", ".part", folder);
        try {
            if (connection.getResponseCode() != 200) {
                InputStream error = connection.getErrorStream();
                String detail = error == null ? "Media preparation failed" : readText(error);
                if (error != null) error.close();
                throw new IOException("HTTP " + connection.getResponseCode() + ": " + detail);
            }
            InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(temporary);
            try { copy(input, output, 64 * 1024); } finally { input.close(); output.close(); }
            if (!temporary.renameTo(destination) && !(destination.isFile() && destination.length() > 0)) throw new IOException("Could not store media");
            return destination;
        } finally { connection.disconnect(); if (temporary.exists()) temporary.delete(); }
    }

    public File downloadDocument(JSONObject message) throws Exception {
        long id = message.optLong("_id", -1);
        if (id < 1) throw new IOException("Document message has no server ID");
        File folder = new File(appContext.getCacheDir(), "media/document-" + id);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create document cache");
        String name = MediaPresentation.filename(message);
        File destination = new File(folder, name);
        if (destination.isFile() && destination.length() > 0) return destination;
        if (message.optString("media_original_filename", "").length() == 0) {
            File[] cached = folder.listFiles();
            if (cached != null) for (File file : cached) if (file.isFile() && file.length() > 0 && !file.getName().endsWith(".part")) return file;
        }
        HttpURLConnection connection = openConnection("/api/v2/media/" + id + "?variant=original", "GET");
        connection.setReadTimeout(240000);
        File temporary = File.createTempFile("download-", ".part", folder);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Document download returned HTTP " + connection.getResponseCode());
            if (message.optString("media_original_filename", "").length() == 0) {
                String disposition = connection.getHeaderField("Content-Disposition");
                String headerName = null;
                if (disposition != null) {
                    java.util.regex.Matcher encoded = java.util.regex.Pattern.compile("filename\\*=UTF-8''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
                    java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("filename=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
                    if (encoded.find()) headerName = java.net.URLDecoder.decode(encoded.group(1).trim().replace("+", "%2B"), "UTF-8");
                    else if (quoted.find()) headerName = quoted.group(1);
                }
                if (headerName != null) destination = new File(folder, MediaPresentation.safeFilename(headerName));
            }
            InputStream input = connection.getInputStream();
            OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary));
            try { copy(input, output, 64 * 1024); } finally { input.close(); output.close(); }
            if (temporary.length() == 0) throw new IOException("Document download was empty");
            if (!temporary.renameTo(destination)) {
                if (!(destination.isFile() && destination.length() > 0)) throw new IOException("Cannot save downloaded document");
            }
            return destination;
        } finally { connection.disconnect(); if (temporary.exists()) temporary.delete(); }
    }

    public File saveToDownloads(File source) throws Exception {
        return saveToDownloads(source, source.getName());
    }

    public File saveToDownloads(File source, String displayName) throws Exception {
        File folder = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Q20Messenger");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot write Downloads. Check the app's shared storage permission.");
        File temporary = File.createTempFile(".q20-save-", ".part", folder);
        File destination;
        try {
            copyFile(source, temporary);
            if (temporary.length() != source.length()) throw new IOException("File save was incomplete. Check free storage space.");
            synchronized (ApiClient.class) {
                String name = MediaPresentation.safeFilename(displayName);
                destination = new File(folder, name);
                int suffix = 1;
                while (destination.exists()) {
                    int dot = name.lastIndexOf('.');
                    destination = new File(folder, (dot > 0 ? name.substring(0, dot) : name) + " (" + suffix++ + ")" + (dot > 0 ? name.substring(dot) : ""));
                }
                if (!temporary.renameTo(destination)) throw new IOException("Could not complete file save. Check shared storage access.");
            }
        } finally { if (temporary.exists()) temporary.delete(); }
        try { android.media.MediaScannerConnection.scanFile(appContext, new String[] { destination.getAbsolutePath() }, null, null); }
        catch (RuntimeException ignored) { /* File is saved even if the media index is unavailable. */ }
        return destination;
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = openConnection(path, "GET");
        return readJsonResponse(connection);
    }

    private JSONObject postJson(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = openConnection(path, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = payload.toString().getBytes("UTF-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        OutputStream out = connection.getOutputStream();
        out.write(bytes);
        out.flush();
        out.close();
        return readJsonResponse(connection);
    }

    private HttpURLConnection openConnection(String path, String method) throws Exception {
        if (baseUrl.length() == 0) throw new IOException("Server URL is not configured");
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        if (path.equals("/api/v2/calls/start") || path.equals("/api/v2/calls/confirm")) connection.setReadTimeout(45000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, */*");
        if (apiToken.length() > 0) connection.setRequestProperty("Authorization", "Bearer " + apiToken);
        return connection;
    }

    private JSONObject readJsonResponse(HttpURLConnection connection) throws Exception {
        InputStream stream = null;
        try {
            int code = connection.getResponseCode();
            stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "{}" : readText(stream);
            if (code < 200 || code >= 300) {
                String detail = text;
                String stage = "";
                try {
                    JSONObject error = new JSONObject(text);
                    detail = error.optString("error", error.optString("message", text));
                    stage = error.optString("stage", "");
                } catch (Exception ignored) { }
                throw new IOException("HTTP " + code + (stage.length() > 0 ? " (" + stage + ")" : "") + ": " + detail);
            }
            return new JSONObject(text);
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            connection.disconnect();
        }
    }

    private static String readText(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, 8192);
        return new String(output.toByteArray(), "UTF-8");
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        String field = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(field.getBytes("UTF-8"));
    }

    private static void copy(InputStream in, OutputStream out, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static void copyFile(File source, File destination) throws IOException {
        InputStream in = new BufferedInputStream(new java.io.FileInputStream(source));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(destination));
        try { copy(in, out, 8192); } finally { in.close(); out.close(); }
    }

    private static String[] extensionsFor(String type) {
        // Prefer WhatsApp's original MP4. The server's legacy 3GP rendition is
        // intentionally low resolution for old Nokia handsets and can be 1:1.
        if ("video".equalsIgnoreCase(type)) return new String[] { ".mp4", ".3gp" };
        if ("image".equalsIgnoreCase(type)) return new String[] { ".jpg", ".webp", ".png", ".gif" };
        if ("gif".equalsIgnoreCase(type)) return new String[] { ".mp4", ".gif" };
        if ("audio".equalsIgnoreCase(type) || "ptt".equalsIgnoreCase(type)) {
            return new String[] { ".3gp", ".mp3", ".ogg" };
        }
        if ("document".equalsIgnoreCase(type)) {
            return new String[] { ".pdf", ".txt", ".csv", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".zip", ".mp4" };
        }
        return new String[] { ".jpg", ".mp3", ".3gp", ".mp4" };
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "upload.bin" : last;
    }

    private static String mimeFromName(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String detected = dot >= 0 ? android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot + 1)) : null;
        if (detected != null) return detected;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".3gp")) return "audio/3gpp";
        if (lower.endsWith(".amr")) return "audio/amr";
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

    private static boolean isSupportedMime(String mime) {
        if (mime == null) return false;
        return "image/jpeg".equalsIgnoreCase(mime)
                || "image/png".equalsIgnoreCase(mime)
                || "image/webp".equalsIgnoreCase(mime)
                || "image/gif".equalsIgnoreCase(mime)
                || "video/mp4".equalsIgnoreCase(mime)
                || "audio/mpeg".equalsIgnoreCase(mime)
                || "audio/3gpp".equalsIgnoreCase(mime)
                || "audio/amr".equalsIgnoreCase(mime)
                || "application/pdf".equalsIgnoreCase(mime)
                || "text/plain".equalsIgnoreCase(mime)
                || "text/csv".equalsIgnoreCase(mime)
                || "application/msword".equalsIgnoreCase(mime)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mime)
                || "application/vnd.ms-excel".equalsIgnoreCase(mime)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equalsIgnoreCase(mime)
                || "application/vnd.ms-powerpoint".equalsIgnoreCase(mime)
                || "application/vnd.openxmlformats-officedocument.presentationml.presentation".equalsIgnoreCase(mime)
                || "application/zip".equalsIgnoreCase(mime);
    }

    private static String pathSegment(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String queryValue(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
