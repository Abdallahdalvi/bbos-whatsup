package com.aghani.q20messenger;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w240dp-h240dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SquareRenderTest {
    private void save(Activity activity, String name) throws Exception {
        View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 720, 720);
        Bitmap bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File folder = new File("build/reports/square-ui");
        folder.mkdirs();
        FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"));
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); } finally { stream.close(); }
    }
    @Test public void renderSquareScreens() throws Exception {
        org.junit.Assume.assumeFalse("Robolectric native renderer does not ship a Windows DLL", System.getProperty("os.name").toLowerCase().contains("win"));
        LocalStore store = LocalStore.get(RuntimeEnvironment.getApplication());
        JSONArray chats = new JSONArray();
        String[] names = {"Aisha", "Weekend plans", "Omar", "Family", "Noor"};
        String[] previews = {"See you soon!", "You: Sounds good 👍", "Voice message", "Photo", "Thank you ❤️"};
        for (int i = 0; i < names.length; i++) {
            JSONObject chat = new JSONObject();
            chat.put("chat_jid", (i + 2000) + (i == 1 || i == 3 ? "@g.us" : "@c.us"));
            chat.put("name", names[i]); chat.put("last_message", previews[i]); chat.put("timestamp_ms", System.currentTimeMillis() - i * 60000);
            chats.put(chat);
        }
        store.upsertChatSummaries(chats);
        MainActivity main = Robolectric.buildActivity(MainActivity.class).create().get();
        main.findViewById(R.id.status).setVisibility(View.GONE);
        save(main, "inbox-240dp-square");

        ChatActivity chat = Robolectric.buildActivity(ChatActivity.class).create().get();
        ((TextView) chat.findViewById(R.id.chat_title)).setText("Aisha");
        chat.findViewById(R.id.chat_status).setVisibility(View.GONE);
        Field messagesField = ChatActivity.class.getDeclaredField("messages"); messagesField.setAccessible(true);
        ArrayList<JSONObject> messages = (ArrayList<JSONObject>) messagesField.get(chat);
        String[] body = {"Hey! Are you free this evening?", "Yes! What time works for you?", "Around seven 😊", "Perfect. I'll call you then."};
        for (int i = 0; i < body.length; i++) {
            JSONObject message = new JSONObject(); message.put("_id", i + 1); message.put("chatType", "chat");
            message.put("message", body[i]); message.put("from_me", i % 2 == 1); message.put("timestamp_ms", System.currentTimeMillis());
            messages.add(message);
        }
        Field adapterField = ChatActivity.class.getDeclaredField("adapter"); adapterField.setAccessible(true);
        ((BaseAdapter) adapterField.get(chat)).notifyDataSetChanged();
        save(chat, "chat-240dp-square");
    }
}
