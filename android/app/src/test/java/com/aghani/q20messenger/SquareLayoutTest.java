package com.aghani.q20messenger;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, qualifiers = "w240dp-h240dp-mdpi")
public class SquareLayoutTest {
    @org.junit.Before public void resetDatabaseHelper(){org.robolectric.util.ReflectionHelpers.setStaticField(LocalStore.class,"instance",null);}
    private void measure(View root, int width) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, width);
    }
    private void visibleInside(View root, int id, int width) {
        View child = root.findViewById(id);
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        assertEquals(View.VISIBLE, child.getVisibility());
        assertTrue("Zero size for " + id, rect.width() > 0 && rect.height() > 0);
        assertTrue("Outside square for " + id + ": " + rect, rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= width);
    }
    @Test public void mainButtonsFitSmallSquare() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        for (int width : new int[] {240, 320, 360}) {
            measure(root, width);
            visibleInside(root, R.id.search_button, width);
            visibleInside(root, R.id.menu_button, width);
            visibleInside(root, R.id.main_navigation, width);
            assertEquals(44, root.findViewById(R.id.main_header).getHeight());
            assertEquals(34, root.findViewById(R.id.main_navigation).getHeight());
            android.graphics.Rect tabRect=new android.graphics.Rect(),listRect=new android.graphics.Rect();View tabs=root.findViewById(R.id.main_navigation),list=root.findViewById(R.id.conversation_list);tabs.getDrawingRect(tabRect);list.getDrawingRect(listRect);((ViewGroup)root).offsetDescendantRectToMyCoords(tabs,tabRect);((ViewGroup)root).offsetDescendantRectToMyCoords(list,listRect);assertTrue("Tabs must be above chats",tabRect.bottom<=listRect.top);
        }
    }
    @Test public void chatComposerAndCallFitSmallSquare() {
        // Empty peer prevents any API request during Activity creation.
        Activity activity = Robolectric.buildActivity(ChatActivity.class).create().get();
        ((android.widget.EditText) activity.findViewById(R.id.chat_composer)).setText("fixture message");
        View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        for (int width : new int[] {240, 320, 360}) {
            measure(root, width);
            visibleInside(root, R.id.chat_call, width);
            visibleInside(root, R.id.chat_send, width);
            assertTrue("Composer too narrow", root.findViewById(R.id.chat_composer).getWidth() >= 90);
        }
    }
    @Test public void hangupRemainsVisibleOnSquare() {
        View root = LayoutInflater.from(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)).inflate(R.layout.activity_call, null);
        measure(root, 240);
        visibleInside(root, R.id.call_hangup, 240);
        visibleInside(root, R.id.call_mute, 240);
        visibleInside(root, R.id.call_speaker, 240);
    }
    @Test public void photoViewWorksOnLegacyAndroid() {
        com.github.chrisbanes.photoview.PhotoView photo = new com.github.chrisbanes.photoview.PhotoView(RuntimeEnvironment.getApplication());
        photo.setImageBitmap(android.graphics.Bitmap.createBitmap(100, 150, android.graphics.Bitmap.Config.ARGB_8888));
        measure(photo, 240);
        assertTrue(photo.isZoomEnabled());
    }
}
