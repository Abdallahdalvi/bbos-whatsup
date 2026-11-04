package com.aghani.q20messenger;

import android.content.Context;
import android.graphics.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Draw only visible cells; no browser engine or per-cell view tree. */
final class SpreadsheetPreview extends ScrollView {
    private final HorizontalScrollView horizontal;
    private final Grid grid;
    SpreadsheetPreview(Context context){
        super(context);setFillViewport(true);setFocusable(true);setFocusableInTouchMode(true);
        horizontal=new HorizontalScrollView(context);grid=new Grid(context);
        horizontal.addView(grid,new HorizontalScrollView.LayoutParams(-2,-2));
        addView(horizontal,new ScrollView.LayoutParams(-1,-2));
    }
    void show(SpreadsheetReader.Page page){grid.page=page;grid.requestLayout();grid.invalidate();scrollTo(0,0);horizontal.scrollTo(0,0);}
    void close(){grid.page=null;grid.invalidate();}
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){horizontal.scrollBy(Ui.dp(getContext(),key==KeyEvent.KEYCODE_DPAD_RIGHT?100:-100),0);return true;}
        return super.onKeyDown(key,event);
    }
    private static final class Grid extends View {
        SpreadsheetReader.Page page;final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);final Paint line=new Paint();
        final int width,height,padding;
        Grid(Context context){super(context);width=Ui.dp(context,110);height=Ui.dp(context,30);padding=Ui.dp(context,5);text.setTextSize(Ui.dp(context,12));text.setColor(0xffe9edef);line.setColor(0xff34434b);setBackgroundColor(0xff10181d);}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension((SpreadsheetReader.COLS+1)*width,((page==null?0:page.rows.size())+1)*height);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);if(page==null)return;Rect clip=canvas.getClipBounds();
            int first=Math.max(0,clip.top/height),last=Math.min(page.rows.size(),clip.bottom/height);
            int left=Math.max(0,clip.left/width),right=Math.min(SpreadsheetReader.COLS,clip.right/width);
            for(int r=first;r<=last;r++)for(int c=left;c<=right;c++){
                String value=r==0?(c==0?"Row":"Column "+(page.firstColumn+c)):(c==0?String.valueOf(page.firstRow+r):page.rows.get(r-1)[c-1]);
                if(value==null)value="";if(value.length()>240)value=value.substring(0,240);
                CharSequence label=TextUtils.ellipsize(value,text,width-2*padding,TextUtils.TruncateAt.END);
                canvas.drawText(label.toString(),c*width+padding,(r+1)*height-padding,text);
                canvas.drawLine(c*width,(r+1)*height,(c+1)*width,(r+1)*height,line);
                canvas.drawLine((c+1)*width,r*height,(c+1)*width,(r+1)*height,line);
            }
        }
    }
}

