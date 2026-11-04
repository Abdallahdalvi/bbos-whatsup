package com.aghani.q20messenger;
public class BaseActivity extends android.support.v7.app.AppCompatActivity {
    private boolean appliedDark;
    @Override protected void onResume(){super.onResume();if(appliedDark!=AppPrefs.isDark(this))recreate();}
    @Override protected void onCreate(android.os.Bundle state) { appliedDark=AppPrefs.isDark(this); getDelegate().setLocalNightMode(AppPrefs.isDark(this) ? android.support.v7.app.AppCompatDelegate.MODE_NIGHT_YES : android.support.v7.app.AppCompatDelegate.MODE_NIGHT_NO); super.onCreate(state); }
    @Override public void onContentChanged(){super.onContentChanged();final android.view.View root=findViewById(android.R.id.content);if(root!=null)root.post(new Runnable(){public void run(){HardwareNavigation.decorate(root);}});}
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event){return HardwareNavigation.scroll(this,event)||super.dispatchGenericMotionEvent(event);}
    @Override public boolean dispatchTrackballEvent(android.view.MotionEvent event){return HardwareNavigation.trackball(this,event)||super.dispatchTrackballEvent(event);}
}
