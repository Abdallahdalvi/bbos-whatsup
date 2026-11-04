package com.aghani.q20messenger;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.widget.ListView;
import android.widget.ScrollView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
/** Uses standard Android focus/scroll events exposed by Q20 trackpad runtimes. */
final class HardwareNavigation {
    static void decorate(View view) {
        // AdapterView focus is a selection, never a full-container background.
        if(view instanceof android.widget.AdapterView)return;
        if(!(view instanceof ViewGroup) && view.isClickable() && !(view instanceof android.widget.EditText)){
            view.setFocusable(true);
            if(!(view.getBackground() instanceof StateListDrawable)){
                Drawable original=view.getBackground();
                int left=view.getPaddingLeft(),top=view.getPaddingTop(),right=view.getPaddingRight(),bottom=view.getPaddingBottom();
                StateListDrawable states=new StateListDrawable();
                android.graphics.drawable.GradientDrawable focus=Ui.rounded(0x4438b978,5,view.getContext());
                Drawable base=original==null?new android.graphics.drawable.ColorDrawable(0):original.getConstantState()!=null?original.getConstantState().newDrawable().mutate():original;
                Drawable focused=new android.graphics.drawable.LayerDrawable(new Drawable[]{base,focus});
                states.addState(new int[]{android.R.attr.state_focused},focused);
                states.addState(new int[]{android.R.attr.state_pressed},focused);
                states.addState(new int[]{},original==null?new android.graphics.drawable.ColorDrawable(0):original);
                view.setBackgroundDrawable(states);
                view.setPadding(left,top,right,bottom);
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)decorate(group.getChildAt(i));}
    }
    static boolean scroll(android.app.Activity activity,MotionEvent event){
        if(event.getAction()!=MotionEvent.ACTION_SCROLL)return false;
        float delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL);if(delta==0)return false;
        View target=activity.getCurrentFocus();
        while(target!=null){
            if(target instanceof ListView){((ListView)target).smoothScrollBy((int)(-delta*Ui.dp(activity,36)),100);return true;}
            if(target instanceof ScrollView){((ScrollView)target).smoothScrollBy(0,(int)(-delta*Ui.dp(activity,36)));return true;}
            target=target.getParent() instanceof View?(View)target.getParent():null;
        }
        return false;
    }
    private static final java.util.WeakHashMap<android.app.Activity,MotionState> motions=new java.util.WeakHashMap<android.app.Activity,MotionState>();
    private static final class MotionState {float x,y;int axis;long last,lastStep;}
    static boolean trackball(android.app.Activity activity,MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_DOWN){send(activity,KeyEvent.KEYCODE_DPAD_CENTER,event.getEventTime());return true;}
        if(event.getAction()==MotionEvent.ACTION_UP)return true;
        if(event.getAction()!=MotionEvent.ACTION_MOVE)return false;
        MotionState state=motions.get(activity);if(state==null){state=new MotionState();motions.put(activity,state);}
        long now=event.getEventTime();if(now-state.last>180){state.axis=0;state.x=0;state.y=0;}state.last=now;
        state.x+=Math.max(-1f,Math.min(1f,event.getX()));state.y+=Math.max(-1f,Math.min(1f,event.getY()));
        if(state.axis==0){if(Math.abs(state.y)>Math.abs(state.x)*1.35f)state.axis=2;else if(Math.abs(state.x)>Math.abs(state.y)*1.6f)state.axis=1;}
        float amount=state.axis==2?state.y:state.axis==1?state.x:0;
        if(Math.abs(amount)<0.65f||now-state.lastStep<(state.axis==1?160:65))return true;
        int key=state.axis==2?(amount>0?KeyEvent.KEYCODE_DPAD_DOWN:KeyEvent.KEYCODE_DPAD_UP):(amount>0?KeyEvent.KEYCODE_DPAD_RIGHT:KeyEvent.KEYCODE_DPAD_LEFT);
        state.x=0;state.y=0;state.lastStep=now;send(activity,key,now);return true;
    }
    private static void send(android.app.Activity activity,int key,long time){
        activity.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,key,0,0,0,0,0,android.view.InputDevice.SOURCE_TRACKBALL));
        activity.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_UP,key,0,0,0,0,0,android.view.InputDevice.SOURCE_TRACKBALL));
    }
}
