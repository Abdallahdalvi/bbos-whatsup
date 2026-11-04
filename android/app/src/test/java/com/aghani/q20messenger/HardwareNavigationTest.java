package com.aghani.q20messenger;
import android.view.*;import android.widget.*;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import org.robolectric.util.ReflectionHelpers;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=19,qualifiers="w240dp-h240dp-mdpi")
public class HardwareNavigationTest {
 @Before public void reset(){ReflectionHelpers.setStaticField(LocalStore.class,"instance",null);AppPrefs.save(RuntimeEnvironment.getApplication(),"","1000");}
 @Test public void selectedChatMessageCanBeActivatedWithoutTouch()throws Exception{
  ChatActivity activity=Robolectric.buildActivity(ChatActivity.class).create().get();
  java.util.ArrayList<JSONObject> messages=ReflectionHelpers.getField(activity,"messages");messages.add(new JSONObject("{_id:1,chatType:'chat',message:'Trackpad selected message'}"));
  ((BaseAdapter)ReflectionHelpers.getField(activity,"adapter")).notifyDataSetChanged();
  ListView list=(ListView)activity.findViewById(R.id.message_list);list.performItemClick(null,0,1);
  android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(dialog instanceof android.support.v7.app.AlertDialog);
  ((android.support.v7.app.AlertDialog)dialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
  org.robolectric.shadows.ShadowLooper.idleMainLooper();
  assertEquals("Trackpad selected message",((android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());
 }
}
