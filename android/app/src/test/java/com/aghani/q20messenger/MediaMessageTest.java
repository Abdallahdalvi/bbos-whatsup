package com.aghani.q20messenger;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=19, qualifiers="w240dp-h240dp-mdpi")
public class MediaMessageTest {
    @Test public void portraitLandscapeAndMissingPreviewFitConversation() {
        int[] portrait = MediaPresentation.previewSize(1080, 1920, 206, 240, false);
        assertEquals(120, portrait[1]); assertEquals(68, portrait[0]);
        int[] landscape = MediaPresentation.previewSize(1920, 1080, 206, 240, false);
        assertEquals(206, landscape[0]); assertTrue(landscape[1] <= 120);
        int[] missing = MediaPresentation.previewSize(0, 0, 206, 240, false);
        assertTrue(missing[1] <= 88); assertTrue(missing[0] <= 160);
    }

    @Test public void voiceNoteHasCompactProgressAndDuration() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AudioPlaybackController controller = new AudioPlaybackController(context);
        JSONObject message = new JSONObject("{_id:72,chatType:'ptt',rich:{media:{duration:65}}}");
        AudioMessageView view = new AudioMessageView(context, message, controller);
        view.measure(View.MeasureSpec.makeMeasureSpec(206, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 206, 52);
        assertEquals(52, view.getHeight());
        LinearLayout info = (LinearLayout) view.getChildAt(1);
        assertTrue(info.getChildAt(0) instanceof SeekBar);
        assertTrue(((SeekBar) info.getChildAt(0)).getThumb().getIntrinsicHeight() <= 10);
        assertTrue(info.getTop() + info.getChildAt(1).getBottom() <= view.getHeight());
        assertEquals("0:00 / 1:05", ((TextView) info.getChildAt(1)).getText().toString());
        assertTrue(view.getChildAt(0).getWidth() > 0);
        controller.close();
    }

    @Test public void documentDownloadKeepsNameAndProvidesReadableContentUri() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("%PDF-fixture"));
        server.start();
        try {
            AppPrefs.save(context, server.url("/").toString(), "1000");
            AppPrefs.saveApiToken(context, "fixture-only");
            JSONObject message = new JSONObject("{_id:9281,media_original_filename:'invoice Q20.pdf'}");
            new File(context.getCacheDir(), "media/document-9281/invoice Q20.pdf").delete();
            File downloaded = new ApiClient(context).downloadDocument(message);
            assertEquals("invoice Q20.pdf", downloaded.getName());
            assertEquals(12, downloaded.length());
            assertEquals(downloaded, new ApiClient(context).downloadDocument(message));
            assertEquals(1, server.getRequestCount());
            assertEquals("/api/v2/media/9281?variant=original", server.takeRequest().getPath());
            Uri uri = android.support.v4.content.FileProvider.getUriForFile(context, context.getPackageName() + ".media", downloaded);
            assertEquals("content", uri.getScheme());
            java.io.InputStream input = context.getContentResolver().openInputStream(uri);
            assertNotNull(input); assertEquals('%', input.read()); input.close();
            downloaded.delete();
            server.enqueue(new MockResponse().setHeader("Content-Disposition", "attachment; filename=\"report.pdf\"; filename*=UTF-8''report%20Q20.pdf").setBody("%PDF-second"));
            JSONObject oldMessage = new JSONObject("{_id:9282}");
            new File(context.getCacheDir(), "media/document-9282/report Q20.pdf").delete();
            File restored = new ApiClient(context).downloadDocument(oldMessage);
            assertEquals("report Q20.pdf", restored.getName());
            assertEquals(restored, new ApiClient(context).downloadDocument(oldMessage));
            assertEquals(2, server.getRequestCount());
            restored.delete();
        } finally { server.shutdown(); }
    }

    @Test public void filenamesAndMediaCaptionsDoNotAddPlaceholderRows() throws Exception {
        assertEquals("invoice.pdf", MediaPresentation.safeFilename("../../invoice.pdf"));
        assertEquals("", MediaPresentation.caption(new JSONObject("{message:'Image received'}")));
        assertEquals("holiday", MediaPresentation.caption(new JSONObject("{rich:{caption:'holiday'}}")));
    }

    @Test public void documentUploadUsesOriginalNameAndExplicitDocumentFlag() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"statusDesc\":\"Media sent\"}"));
        server.start();
        File file = new File(context.getCacheDir(), "notes Q20.md");
        FileOutputStream output = new FileOutputStream(file);
        output.write("fixture document".getBytes("UTF-8")); output.close();
        try {
            AppPrefs.save(context, server.url("/").toString(), "1000");
            AppPrefs.saveApiToken(context, "fixture-only");
            new ApiClient(context).uploadMedia(Uri.fromFile(file), "2000@c.us", true, false);
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest();
            assertEquals("/api/v2/media", request.getPath());
            assertEquals("Bearer fixture-only", request.getHeader("Authorization"));
            String body = request.getBody().readUtf8();
            assertTrue(body, body.contains("filename=\"notes Q20.md\""));
            assertTrue(body.contains("name=\"send_as_document\"\r\n\r\ntrue"));
            assertTrue(body.contains("name=\"voice_note\"\r\n\r\nfalse"));
            assertTrue(body.contains("fixture document"));
        } finally { file.delete(); server.shutdown(); }
    }

    @Test public void savingDoesNotOverwriteOrLeavePartialFilesOnFailure() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(context.getCacheDir(), "save-fixture.bin");
        FileOutputStream stream = new FileOutputStream(source); stream.write(new byte[]{1,2,3}); stream.close();
        ApiClient api = new ApiClient(context);
        File first = api.saveToDownloads(source, "save-fixture.bin");
        File second = api.saveToDownloads(source, "save-fixture.bin");
        assertNotEquals(first.getName(), second.getName());
        assertEquals(3, first.length()); assertEquals(3, second.length());
        source.delete();
        try { api.saveToDownloads(source); fail("Missing source must report a save failure"); } catch (java.io.IOException expected) { }
        for (File file : first.getParentFile().listFiles()) assertFalse(file.getName().startsWith(".q20-save-"));
        first.delete(); second.delete();
    }
}
