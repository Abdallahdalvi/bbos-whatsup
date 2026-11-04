package com.aghani.q20messenger;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import android.util.LruCache;

/** Bundled Twemoji renders modern emoji independently of the API 18 system font. */
public final class EmojiText {
    private static final class Node {
        final HashMap<Integer, Node> next = new HashMap<Integer, Node>();
        String asset;
    }
    private static Node root;
    private static final LruCache<String, android.graphics.Bitmap> cache = new LruCache<String, android.graphics.Bitmap>(150);
    private static synchronized void initialize(Context context) {
        if (root != null) return;
        root = new Node();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("emoji/index.txt"), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                Node node = root;
                for (String point : line.trim().split("-")) {
                    int cp = Integer.parseInt(point, 16);
                    Node child = node.next.get(cp);
                    if (child == null) { child = new Node(); node.next.put(cp, child); }
                    node = child;
                }
                node.asset = line.trim();
            }
            reader.close();
        } catch (Exception ignored) { }
    }

    public static CharSequence render(Context context, CharSequence value, int size) {
        String text = value == null ? "" : value.toString();
        boolean possible=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c>=0x2000||c==0x00a9||c==0x00ae){possible=true;break;}}
        if(!possible)return value==null?"":value;
        initialize(context);
        SpannableStringBuilder output = new SpannableStringBuilder(value == null ? "" : value);
        for (int start = 0; start < text.length();) {
            Node node = root;
            int at = start, end = -1;
            String asset = null;
            while (at < text.length()) {
                int cp = Character.codePointAt(text, at);
                // Twemoji omits VS16 in simple pictographs; retain the original text.
                if (cp == 0xfe0f && !node.next.containsKey(cp)) { at++; if (asset != null) end = at; continue; }
                node = node.next.get(cp);
                if (node == null) break;
                at += Character.charCount(cp);
                if (node.asset != null) { asset = node.asset; end = at; }
            }
            if (asset != null) {
                try {
                    android.graphics.Bitmap bitmap = cache.get(asset);
                    if (bitmap == null) {
                        java.io.InputStream stream = context.getAssets().open("emoji/" + asset + ".png");
                        try { bitmap = android.graphics.BitmapFactory.decodeStream(stream); } finally { stream.close(); }
                        if (bitmap != null) cache.put(asset, bitmap);
                    }
                    if (bitmap != null) {
                        Drawable drawable = new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
                        drawable.setBounds(0, 0, size, size);
                        output.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } catch (Exception ignored) { }
                start = end;
            } else start += Character.charCount(Character.codePointAt(text, start));
        }
        return output;
    }
}
