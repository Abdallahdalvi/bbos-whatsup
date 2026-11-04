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
 @Test public void firstTouchTapClicksAnUnfocusedTrackpadButton(){
  org.robolectric.android.controller.ActivityController<android.app.Activity> controller=Robolectric.buildActivity(android.app.Activity.class).create();android.app.Activity activity=controller.get();
  LinearLayout root=new LinearLayout(activity);Button other=new Button(activity),back=new Button(activity);root.addView(other);root.addView(back);final int[] clicks={0};back.setOnClickListener(new View.OnClickListener(){public void onClick(View view){clicks[0]++;}});HardwareNavigation.decorate(root);activity.setContentView(root);controller.start().resume().visible();back.layout(0,0,100,48);other.requestFocus();assertFalse(back.hasFocus());
  MotionEvent down=MotionEvent.obtain(0,10,MotionEvent.ACTION_DOWN,10,10,0),up=MotionEvent.obtain(0,50,MotionEvent.ACTION_UP,10,10,0);
  try{back.dispatchTouchEvent(down);back.dispatchTouchEvent(up);org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(1,clicks[0]);assertTrue(back.hasFocus());}finally{down.recycle();up.recycle();controller.pause().stop().destroy();}
 }
 @Test public void audioCloseReleasesPlayerAwayFromUiThread()throws Exception {
  final java.util.concurrent.CountDownLatch released=new java.util.concurrent.CountDownLatch(1);final java.util.concurrent.atomic.AtomicBoolean onUi=new java.util.concurrent.atomic.AtomicBoolean();
  android.media.MediaPlayer player=new android.media.MediaPlayer(){@Override public void release(){onUi.set(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());released.countDown();super.release();}};
  AudioPlaybackController controller=new AudioPlaybackController(RuntimeEnvironment.getApplication());ReflectionHelpers.setField(controller,"player",player);controller.close();assertNull(ReflectionHelpers.getField(controller,"player"));assertTrue(released.await(2,java.util.concurrent.TimeUnit.SECONDS));assertFalse(onUi.get());
 }
 @Test public void spreadsheetUsesNativeGridAndReleasesPage(){
  SpreadsheetPreview view=new SpreadsheetPreview(RuntimeEnvironment.getApplication());SpreadsheetReader.Page page=new SpreadsheetReader.Page();String[] row=new String[SpreadsheetReader.COLS];java.util.Arrays.fill(row,"Cell");page.rows.add(row);view.show(page);assertTrue(view.getChildAt(0) instanceof HorizontalScrollView);View grid=((ViewGroup)view.getChildAt(0)).getChildAt(0);assertSame(page,ReflectionHelpers.getField(grid,"page"));view.close();assertNull(ReflectionHelpers.getField(grid,"page"));
 }}

