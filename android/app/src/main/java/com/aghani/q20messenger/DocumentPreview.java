package com.aghani.q20messenger;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;
import com.shockwave.pdfium.*;

/** Offline PDF pages and paged XLS/XLSX cell viewing; originals remain available to Save. */
final class DocumentPreview extends LinearLayout {
    private final Activity activity; private final File file; private final boolean pdf;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler();
    private final TextView status; private final Button previous,next,sheetButton,left,right;
    private final ImageView image; private final android.webkit.WebView table;
    private int page,sheet,column; private boolean busy,closed; private String[] sheetNames=new String[0];
    static boolean supports(File file,String mime){String name=file.getName().toLowerCase(Locale.US);return mime.equals("application/pdf")||name.endsWith(".pdf")||name.endsWith(".xls")||name.endsWith(".xlsx")||mime.equals("application/vnd.ms-excel")||mime.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");}
    DocumentPreview(Activity activity,File file,String mime) {
        super(activity);this.activity=activity;this.file=file;pdf=mime.equals("application/pdf")||file.getName().toLowerCase(Locale.US).endsWith(".pdf");
        setOrientation(VERTICAL);setBackgroundColor(0xff10181d);
        LinearLayout controls=Ui.horizontal(activity);
        previous=control("‹", "Previous page");next=control("›", "Next page");
        status=Ui.text(activity,"Opening document…",11,Color.WHITE);status.setGravity(Gravity.CENTER);
        controls.addView(previous,new LinearLayout.LayoutParams(Ui.dp(activity,40),Ui.dp(activity,34)));
        controls.addView(status,new LinearLayout.LayoutParams(0,Ui.dp(activity,34),1));
        controls.addView(next,new LinearLayout.LayoutParams(Ui.dp(activity,40),Ui.dp(activity,34)));addView(controls);
        if(pdf){
            ImageView candidate;try{candidate=new com.github.chrisbanes.photoview.PhotoView(activity);}catch(LinkageError error){candidate=new ImageView(activity);}
            image=candidate;image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setFocusable(true);
            addView(image,new LinearLayout.LayoutParams(-1,0,1));table=null;sheetButton=null;left=null;right=null;
        }else {
            image=null;LinearLayout sheets=Ui.horizontal(activity);
            left=control("←","Previous columns");right=control("→","Next columns");sheetButton=control("Sheet","Choose worksheet");
            sheets.addView(left,new LinearLayout.LayoutParams(Ui.dp(activity,40),Ui.dp(activity,34)));
            sheets.addView(sheetButton,new LinearLayout.LayoutParams(0,Ui.dp(activity,34),1));
            sheets.addView(right,new LinearLayout.LayoutParams(Ui.dp(activity,40),Ui.dp(activity,34)));addView(sheets);
            table=new android.webkit.WebView(activity);table.setBackgroundColor(0xff10181d);
            table.getSettings().setJavaScriptEnabled(false);table.getSettings().setAllowFileAccess(false);table.getSettings().setAllowContentAccess(false);
            table.getSettings().setBuiltInZoomControls(true);table.getSettings().setDisplayZoomControls(false);
            table.setWebViewClient(new android.webkit.WebViewClient(){@Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v,String url){return true;}});
            addView(table,new LinearLayout.LayoutParams(-1,0,1));
            left.setOnClickListener(new OnClickListener(){public void onClick(View v){if(!busy){column=Math.max(0,column-SpreadsheetReader.COLS);load();}}});
            right.setOnClickListener(new OnClickListener(){public void onClick(View v){if(!busy){column+=SpreadsheetReader.COLS;load();}}});
            sheetButton.setOnClickListener(new OnClickListener(){public void onClick(View v){new android.app.AlertDialog.Builder(DocumentPreview.this.activity).setTitle("Worksheet").setItems(sheetNames,new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface dialog,int which){sheet=which;page=0;column=0;load();}}).show();}});
        }
        previous.setOnClickListener(new OnClickListener(){public void onClick(View v){if(!busy){page=Math.max(0,page-1);load();}}});
        next.setOnClickListener(new OnClickListener(){public void onClick(View v){if(!busy){page++;load();}}});
        load();
    }
    private Button control(String label,String description){Button button=Ui.button(activity,label);button.setTextColor(Color.WHITE);button.setBackgroundDrawable(Ui.rounded(0xff26343d,4,activity));button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(2,0,2,0);button.setContentDescription(description);return button;}
    ImageView image(){return image;}
    private void load(){
        if(closed||busy)return;busy=true;previous.setEnabled(false);next.setEnabled(false);status.setText("Opening…");
        if(left!=null){left.setEnabled(false);right.setEnabled(false);sheetButton.setEnabled(false);}
        final int selectedPage=page,selectedSheet=sheet,selectedColumn=column;
        worker.execute(new Runnable(){public void run(){
            Bitmap rendered=null;int pageCount=0;SpreadsheetReader.Page cells=null;String failure=null;
            try {
                if(pdf){
                    if(file.length()>64L*1024*1024)throw new IOException("PDF exceeds the mobile viewer's 64 MB limit");
                    PdfiumCore core=new PdfiumCore(activity.getApplicationContext());PdfDocument document=null;ParcelFileDescriptor descriptor=null;
                    try{
                        descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);document=core.newDocument(descriptor);pageCount=core.getPageCount(document);
                        if(pageCount==0)throw new IOException("PDF has no pages");int index=Math.min(selectedPage,pageCount-1);core.openPage(document,index);
                        int width=core.getPageWidthPoint(document,index),height=core.getPageHeightPoint(document,index);
                        if(width<=0||height<=0)throw new IOException("Invalid PDF page dimensions");
                        double scale=Math.min(1400.0/width,2000.0/height);int w=Math.max(1,(int)(width*scale)),h=Math.max(1,(int)(height*scale));
                        rendered=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565);rendered.eraseColor(Color.WHITE);core.renderPageBitmap(document,rendered,index,0,0,w,h,true);
                    }finally{if(document!=null)core.closeDocument(document);else if(descriptor!=null)descriptor.close();}
                }else cells=SpreadsheetReader.read(file,selectedSheet,selectedPage*SpreadsheetReader.ROWS,selectedColumn);
            }catch(Exception error){failure=error.getMessage();CrashReporter.report(activity,"document_preview",error);}catch(LinkageError error){failure="PDF engine could not load on this runtime";CrashReporter.report(activity,"document_pdf_engine",error);}catch(OutOfMemoryError error){failure="Document needs more memory than available";CrashReporter.report(activity,"document_preview_memory",error);}
            final Bitmap bitmap=rendered;final int count=pageCount;final SpreadsheetReader.Page result=cells;final String error=failure;
            handler.post(new Runnable(){public void run(){
                busy=false;if(closed||activity.isFinishing()){if(bitmap!=null)bitmap.recycle();return;}
                if(error!=null){status.setText(error);status.setMaxLines(3);return;}
                previous.setEnabled(page>0);
                if(pdf){image.setImageBitmap(bitmap);status.setText((page+1)+" / "+count);next.setEnabled(page+1<count);}
                else {
                    sheetNames=result.sheets.toArray(new String[result.sheets.size()]);sheetButton.setText(sheetNames.length>sheet?sheetNames[sheet]:"Sheet");sheetButton.setEnabled(true);
                    status.setText("Rows "+(page*SpreadsheetReader.ROWS+1)+"–"+(page*SpreadsheetReader.ROWS+result.rows.size()));
                    next.setEnabled(result.moreRows);left.setEnabled(column>0);right.setEnabled(result.moreColumns);
                    table.loadDataWithBaseURL(null,SpreadsheetReader.html(result),"text/html","UTF-8",null);
                }
            }});
        }});
    }
    void close(){closed=true;worker.shutdown();if(table!=null){table.stopLoading();table.destroy();}if(image!=null)image.setImageDrawable(null);}
}
