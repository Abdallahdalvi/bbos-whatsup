package com.aghani.q20messenger;
import android.content.*;import android.graphics.Bitmap;import android.view.*;import android.widget.*;import java.io.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import org.robolectric.android.controller.ActivityController;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=19,qualifiers="w240dp-h240dp-mdpi")
public class MediaViewerLifecycleTest {
 @Before public void offline(){AppPrefs.save(RuntimeEnvironment.getApplication(),"","1000");}
 private File file(String name,boolean image)throws Exception{File folder=new File(RuntimeEnvironment.getApplication().getCacheDir(),"media");folder.mkdirs();File file=new File(folder,name);FileOutputStream stream=new FileOutputStream(file);if(image)Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG,90,stream);else stream.write("fixture document".getBytes("UTF-8"));stream.close();return file;}
 private Intent intent(File file,String type){return new Intent(RuntimeEnvironment.getApplication(),MediaViewerActivity.class).putExtra("file_path",file.getAbsolutePath()).putExtra("media_type",type).putExtra("showing_copy",true).putExtra("message_id",99L).putExtra("original_name",file.getName());}
 @Test public void imageViewerFullLifecycleAndHeaderFitSquare()throws Exception{
  File file=file("preview.jpg",true);ActivityController<MediaViewerActivity> c=Robolectric.buildActivity(MediaViewerActivity.class,intent(file,"image")).create().start().resume().visible();
  try{MediaViewerActivity activity=c.get();ViewGroup root=(ViewGroup)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);root.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));root.layout(0,0,240,240);ViewGroup header=(ViewGroup)root.getChildAt(0);assertEquals(44,header.getHeight());for(int i=0;i<header.getChildCount();i++)assertTrue(header.getChildAt(i).getRight()<=240);assertTrue(root.getChildAt(1) instanceof ImageView);assertFalse(activity.isFinishing());}finally{c.pause().stop().destroy();file.delete();}
 }
 @Test public void documentViewerDoesNotLaunchAnExternalAppDuringCreation()throws Exception{
  File file=file("invoice.pdf",false);ActivityController<MediaViewerActivity> c=Robolectric.buildActivity(MediaViewerActivity.class,intent(file,"document").putExtra("mime_type","application/pdf")).create().start().resume().visible();
  try{assertFalse(c.get().isFinishing());assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());}finally{c.pause().stop().destroy();file.delete();}
 }
}
