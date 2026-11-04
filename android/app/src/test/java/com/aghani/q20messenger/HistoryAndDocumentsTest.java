package com.aghani.q20messenger;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import org.robolectric.util.ReflectionHelpers;import org.json.*;import static org.junit.Assert.*;
import android.view.*;import android.widget.*;import java.io.*;import java.util.zip.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=19,qualifiers="w400dp-h400dp-mdpi")
public class HistoryAndDocumentsTest {
 private LocalStore store;
 @Before public void reset(){ReflectionHelpers.setStaticField(LocalStore.class,"instance",null);store=LocalStore.get(RuntimeEnvironment.getApplication());for(String table:new String[]{"messages","chats","sync_state","contact_names","call_history"})store.getWritableDatabase().delete(table,null,null);}
 @Test public void oldPagesKeepMessagesSharingTheSameTimestamp()throws Exception {
  JSONArray rows=new JSONArray();for(int id=1;id<=3;id++)rows.put(new JSONObject().put("_id",id).put("chat_jid","a@c.us").put("chat_type","chat").put("timestamp_ms",1000).put("message","m"+id));store.upsertMessages(rows);
  JSONObject newest=store.getMessages("a@c.us",1,Long.MAX_VALUE).get(0);assertEquals(3,newest.getInt("_id"));
  java.util.ArrayList<JSONObject> older=store.getMessages("a@c.us",10,newest.getLong("timestamp_ms"),newest.getLong("_id"));assertEquals(2,older.size());assertEquals(1,older.get(0).getInt("_id"));
 }
 @Test public void firstSyncAutomaticallyStartsLinkedHistoryImportAndKeepsProgress()throws Exception {
  final int[] starts={0};ApiClient api=new ApiClient(RuntimeEnvironment.getApplication()) {
   public JSONObject getHealth()throws Exception{return new JSONObject("{connected:true}");}
   public JSONObject getAccount()throws Exception{return new JSONObject("{account:{pushname:'Test'}}");}
   public JSONObject getBootstrap(long id,int limit,long snapshot)throws Exception{return new JSONObject("{messages:[],snapshot_max_id:0,next_after_id:0,has_more:false}");}
   public JSONObject getUpdates(long id,int limit)throws Exception{return new JSONObject("{messages:[],next_after_id:0,has_more:false}");}
   public JSONObject getContacts(String search,int page)throws Exception{return new JSONObject("{contacts:[],has_more:false}");}
   public JSONObject getChats()throws Exception{return new JSONObject("{chats:[]}");}
   public JSONObject getHistoryImportStatus()throws Exception{return new JSONObject("{running:false,phase:'idle'}");}
   public JSONObject startHistoryImport()throws Exception{starts[0]++;return new JSONObject("{started:true,status:{running:true,conversations_done:1,conversations_total:4,messages_imported:10}}");}
  };
  SyncCoordinator.Result first=SyncCoordinator.sync(RuntimeEnvironment.getApplication(),api,null);assertNull(first.error);assertEquals(1,starts[0]);assertTrue(first.historyProgress.contains("1/4"));
  SyncCoordinator.Result again=SyncCoordinator.sync(RuntimeEnvironment.getApplication(),api,null);assertEquals(1,starts[0]);assertEquals(first.historyProgress,again.historyProgress);
 }
 @Test public void tabKeysCycleWithoutStealingSearchCaret()throws Exception {
  MainActivity activity=Robolectric.buildActivity(MainActivity.class).create().start().visible().get();activity.findViewById(R.id.conversation_list).requestFocus();
  activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertEquals(0,(int)ReflectionHelpers.<Integer>getField(activity,"currentTab"));
  activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertEquals(1,(int)ReflectionHelpers.<Integer>getField(activity,"currentTab"));
  activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));assertEquals(0,(int)ReflectionHelpers.<Integer>getField(activity,"currentTab"));
  EditText search=(EditText)activity.findViewById(R.id.search_field);search.setVisibility(View.VISIBLE);search.requestFocus();search.setText("abc");search.setSelection(1);assertTrue("Search must have focus for caret test",search.hasFocus());
  activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertEquals(0,(int)ReflectionHelpers.<Integer>getField(activity,"currentTab"));assertEquals("abc",search.getText().toString());
 }
 @Test public void directionalSelectionRemainsVisibleInMouseMode()throws Exception {
  MainActivity activity=Robolectric.buildActivity(MainActivity.class).create().get();java.util.ArrayList<JSONObject> rows=ReflectionHelpers.getField(activity,"rows");
  rows.clear();rows.add(new JSONObject("{sender:'a@c.us',senderName:'Alice'}"));rows.add(new JSONObject("{sender:'b@c.us',senderName:'Bob'}"));
  ListView list=(ListView)activity.findViewById(R.id.conversation_list);list.requestFocus();activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
  BaseAdapter adapter=ReflectionHelpers.getField(activity,"adapter");assertFalse(adapter.getView(0,null,list).isActivated());assertTrue(adapter.getView(1,null,list).isActivated());
 }
 @Test public void darkAndLightThemesUseTheirMatchingPalette(){
  android.content.Context app=RuntimeEnvironment.getApplication();try{
   AppPrefs.setDark(app,true);MainActivity dark=Robolectric.buildActivity(MainActivity.class).create().get();assertEquals(0xff10181d,dark.getResources().getColor(R.color.paper));assertEquals(0xffe9edef,dark.getResources().getColor(R.color.ink));
   AppPrefs.setDark(app,false);MainActivity light=Robolectric.buildActivity(MainActivity.class).create().get();assertEquals(0xffffffff,light.getResources().getColor(R.color.paper));assertEquals(0xff17212b,light.getResources().getColor(R.color.ink));
  }finally{AppPrefs.setDark(app,true);}
 }
 @Test public void toastUsesProgrammaticNativeViewInsteadOfSystemXml(){android.widget.Toast toast=SafeToast.makeText(RuntimeEnvironment.getApplication(),"Saved",0);assertTrue(toast.getView() instanceof TextView);assertEquals("Saved",((TextView)toast.getView()).getText().toString());}
 @Test public void legacyXlsHasPagedWorksheetsAndCachedCellValues()throws Exception {
  File file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"fixture.xls");org.apache.poi.hssf.usermodel.HSSFWorkbook book=new org.apache.poi.hssf.usermodel.HSSFWorkbook();
  org.apache.poi.ss.usermodel.Sheet sheet=book.createSheet("Orders");for(int r=0;r<90;r++){org.apache.poi.ss.usermodel.Row row=sheet.createRow(r);row.createCell(0).setCellValue("Order "+r);row.createCell(22).setCellValue(r+1);}
  book.createSheet("Second").createRow(0).createCell(0).setCellValue("Other sheet");FileOutputStream output=new FileOutputStream(file);book.write(output);output.close();book.close();
  SpreadsheetReader.Page first=SpreadsheetReader.read(file,0,0,0);assertEquals(2,first.sheets.size());assertEquals(80,first.rows.size());assertTrue(first.moreRows);assertTrue(first.moreColumns);assertEquals("Order 0",first.rows.get(0)[0]);
  SpreadsheetReader.Page next=SpreadsheetReader.read(file,0,80,20);assertEquals(10,next.rows.size());assertFalse(next.moreRows);assertEquals("81",next.rows.get(0)[2]);
  assertEquals("Other sheet",SpreadsheetReader.read(file,1,0,0).rows.get(0)[0]);
 }
 private void zip(ZipOutputStream zip,String name,String text)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes("UTF-8"));zip.closeEntry();}
 @Test public void xlsxShowsSharedStringsInlineStringsAndEscapesHtml()throws Exception {
  File file=new File(RuntimeEnvironment.getApplication().getCacheDir(),"fixture.xlsx");ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file));
  zip(zip,"xl/workbook.xml","<workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Sales' r:id='rId1'/></sheets></workbook>");
  zip(zip,"xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='rId1' Target='worksheets/sheet1.xml'/></Relationships>");
  zip(zip,"xl/sharedStrings.xml","<sst><si><t>&lt;script&gt;test&lt;/script&gt;</t></si></sst>");
  zip(zip,"xl/worksheets/sheet1.xml","<worksheet><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c><c r='C1' t='inlineStr'><is><t>Inline text</t></is></c><c r='D1'><f>2+2</f><v>4</v></c></row><row r='82'><c r='B82' t='b'><v>1</v></c></row></sheetData></worksheet>");zip.close();
  SpreadsheetReader.Page first=SpreadsheetReader.read(file,0,0,0);assertTrue(first.moreRows);assertEquals("Inline text",first.rows.get(0)[2]);assertEquals("4",first.rows.get(0)[3]);assertFalse(SpreadsheetReader.html(first).contains("<script>"));assertTrue(SpreadsheetReader.html(first).contains("&lt;script&gt;"));
  SpreadsheetReader.Page later=SpreadsheetReader.read(file,0,80,0);assertEquals("TRUE",later.rows.get(1)[1]);
 }
}
