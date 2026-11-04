package com.aghani.q20messenger;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    private Ui() { }

    public static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    public static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(EmojiText.render(context, value, dp(context, size + 3)));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public static Button button(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        return button;
    }

    public static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout horizontal(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static GradientDrawable rounded(int color, int radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static android.graphics.drawable.StateListDrawable tabBackground(Context context,boolean active) {
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        for(int mode=0;mode<3;mode++){
            android.graphics.drawable.GradientDrawable fill=rounded(mode<2?context.getResources().getColor(R.color.selection):android.graphics.Color.TRANSPARENT,4,context);
            android.graphics.drawable.GradientDrawable line=rounded(active?context.getResources().getColor(R.color.green_dark):android.graphics.Color.TRANSPARENT,0,context);
            android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fill,line});
            layers.setLayerInset(1,dp(context,10),dp(context,32),dp(context,10),0);
            states.addState(mode==0?new int[]{android.R.attr.state_focused}:mode==1?new int[]{android.R.attr.state_pressed}:new int[]{},layers);
        }return states;
    }
    public static TextView heading(Context context, String value) {
        TextView view = text(context, value, 20, context.getResources().getColor(R.color.ink));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
}
