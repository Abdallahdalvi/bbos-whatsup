package com.aghani.q20messenger;

import android.content.Context;
import android.graphics.Color;
import android.widget.TextView;
import android.widget.Toast;

/** BB10's system toast XML cannot be inflated through AppCompat's text inflater. */
final class SafeToast {
    private SafeToast() { }
    static Toast makeText(Context context, CharSequence text, int duration) {
        Context app = context.getApplicationContext();
        TextView label = new TextView(app);
        label.setText(text); label.setTextColor(Color.WHITE); label.setTextSize(14);
        label.setPadding(Ui.dp(app,16),Ui.dp(app,10),Ui.dp(app,16),Ui.dp(app,10));
        label.setBackgroundDrawable(Ui.rounded(0xf025343b,8,app));
        Toast toast = new Toast(app); toast.setView(label); toast.setDuration(duration);
        return toast;
    }
}
