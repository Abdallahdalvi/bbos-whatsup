package com.aghani.q20messenger;

import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class SettingsActivity extends BaseActivity {
    private EditText serverField;
    private EditText phoneField;
    private EditText tokenField;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.vertical(this);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        root.setBackgroundColor(getResources().getColor(R.color.paper));

        root.addView(Ui.heading(this, "Settings"));
        android.widget.Switch dark = new android.widget.Switch(this);
        dark.setText("Dark theme"); dark.setChecked(AppPrefs.isDark(this));
        root.addView(dark, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        android.widget.Switch background=new android.widget.Switch(this);background.setText("Background calls and messages");background.setChecked(AppPrefs.backgroundEnabled(this));root.addView(background,new LinearLayout.LayoutParams(-1,Ui.dp(this,42)));
        background.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(android.widget.CompoundButton button,boolean enabled){AppPrefs.setBackgroundEnabled(SettingsActivity.this,enabled);android.content.Intent service=new android.content.Intent(SettingsActivity.this,IncomingCallService.class);if(enabled)startService(service);else stopService(service);}});
        dark.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) { AppPrefs.setDark(SettingsActivity.this,checked); recreate(); }
        });
        TextView note = Ui.text(this,
                "Enter the LAN address of your Nokia4Ever server and the phone number already linked to WhatsApp.",
                13, getResources().getColor(R.color.muted));
        note.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14));
        root.addView(note);
        root.addView(Ui.text(this, "Emoji artwork: Twemoji 17.0.3, Twitter and contributors (CC BY 4.0). github.com/jdecked/twemoji", 10, getResources().getColor(R.color.muted)));

        root.addView(Ui.text(this, "Server URL", 13, getResources().getColor(R.color.ink)));
        serverField = new EditText(this);
        serverField.setSingleLine(true);
        serverField.setText(AppPrefs.getServerUrl(this));
        serverField.setHint("http://192.168.0.107:8034");
        root.addView(serverField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        root.addView(Ui.text(this, "Linked WhatsApp number (country code included)", 13,
                getResources().getColor(R.color.ink)));
        phoneField = new EditText(this);
        phoneField.setSingleLine(true);
        phoneField.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        phoneField.setText(AppPrefs.getPhoneNumber(this));
        phoneField.setHint("e.g. 919876543210");
        root.addView(phoneField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        root.addView(Ui.text(this, "Server API token", 13, getResources().getColor(R.color.ink)));
        tokenField = new EditText(this);
        tokenField.setSingleLine(true);
        tokenField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenField.setText(AppPrefs.getApiToken(this));
        tokenField.setHint("Token configured on the v2 server");
        root.addView(tokenField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        android.widget.Button save = Ui.button(this, "Save and connect");
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                String url = AppPrefs.cleanServer(serverField.getText().toString());
                String phone = AppPrefs.digits(phoneField.getText().toString());
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    SafeToast.makeText(SettingsActivity.this, "Server URL must start with http:// or https://", Toast.LENGTH_LONG).show();
                    return;
                }
                if (phone.length() < 8) {
                    SafeToast.makeText(SettingsActivity.this, "Enter the full linked phone number including country code.", Toast.LENGTH_LONG).show();
                    return;
                }
                AppPrefs.save(SettingsActivity.this, url, phone);
                AppPrefs.saveApiToken(SettingsActivity.this, tokenField.getText().toString());
                SafeToast.makeText(SettingsActivity.this, "Settings saved", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        final TextView connectionStatus = Ui.text(this, "Connection not checked", 12,
                getResources().getColor(R.color.muted));
        connectionStatus.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(connectionStatus);
        android.widget.Button diagnose = Ui.button(this, "Test connection");
        root.addView(diagnose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        diagnose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                String url = AppPrefs.cleanServer(serverField.getText().toString());
                String phone = AppPrefs.digits(phoneField.getText().toString());
                if ((!url.startsWith("http://") && !url.startsWith("https://")) || phone.length() < 8
                        || tokenField.getText().toString().trim().length() == 0) {
                    connectionStatus.setText("Enter a server URL, linked number, and v2 API token first.");
                    return;
                }
                AppPrefs.save(SettingsActivity.this, url, phone);
                AppPrefs.saveApiToken(SettingsActivity.this, tokenField.getText().toString());
                connectionStatus.setText("Checking server and linked account...");
                new AsyncTask<Void, Void, String>() {
                    protected String doInBackground(Void... params) {
                        try {
                            ApiClient api = new ApiClient(SettingsActivity.this);
                            org.json.JSONObject status = api.getLoginStatus();
                            if (!status.optBoolean("isAuthenticated", false)) {
                                return "Server reachable; no linked session for this number.";
                            }
                            org.json.JSONObject account = api.login();
                            return "Connected as " + account.optString("pushname", "WhatsApp account");
                        } catch (Exception error) {
                            return "Connection failed: " + error.getMessage();
                        }
                    }
                    protected void onPostExecute(String result) {
                        if (!isFinishing()) connectionStatus.setText(result);
                    }
                }.execute();
            }
        });

        final TextView historyStatus = Ui.text(this, "WhatsApp Web history import has not been checked.", 11,
                getResources().getColor(R.color.muted));
        historyStatus.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(historyStatus);
        android.widget.Button importHistory = Ui.button(this, "Import available WhatsApp Web history");
        root.addView(importHistory, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        importHistory.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                historyStatus.setText("Starting history import on the existing server session...");
                new AsyncTask<Void, Void, org.json.JSONObject>() {
                    protected org.json.JSONObject doInBackground(Void... params) {
                        try { return new ApiClient(SettingsActivity.this).startHistoryImport(); }
                        catch (Exception error) { org.json.JSONObject response = new org.json.JSONObject(); try { response.put("error", error.getMessage()); } catch (Exception ignored) { } return response; }
                    }
                    protected void onPostExecute(org.json.JSONObject response) {
                        org.json.JSONObject progress = response.optJSONObject("status");
                        if (progress != null) historyStatus.setText(historyProgress(progress));
                        else historyStatus.setText(response.optString("error", "History import request sent."));
                    }
                }.execute();
            }
        });
        android.widget.Button checkHistory = Ui.button(this, "Check history import progress");
        root.addView(checkHistory, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));
        checkHistory.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                new AsyncTask<Void, Void, org.json.JSONObject>() {
                    protected org.json.JSONObject doInBackground(Void... params) {
                        try { return new ApiClient(SettingsActivity.this).getHistoryImportStatus(); }
                        catch (Exception error) { org.json.JSONObject response = new org.json.JSONObject(); try { response.put("error", error.getMessage()); } catch (Exception ignored) { } return response; }
                    }
                    protected void onPostExecute(org.json.JSONObject response) { historyStatus.setText(historyProgress(response)); }
                }.execute();
            }
        });

        android.widget.Button clearCache = Ui.button(this, "Clear downloaded media cache");
        root.addView(clearCache, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        clearCache.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Clear downloaded media?")
                        .setMessage("This removes cached images, audio and videos. They can be downloaded again.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                File media = new File(getCacheDir(), "media");
                                File[] files = media.listFiles();
                                int removed = 0;
                                if (files != null) {
                                    for (int i = 0; i < files.length; i++) {
                                        if (files[i].isFile() && files[i].delete()) removed++;
                                    }
                                }
                                SafeToast.makeText(SettingsActivity.this,
                                        removed + " cached file(s) removed", Toast.LENGTH_SHORT).show();
                            }
                        }).show();
            }
        });
        root.addView(Ui.text(this, "Q20 Messenger " + BuildConfig.VERSION_NAME,
                11, getResources().getColor(R.color.muted)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private String historyProgress(org.json.JSONObject progress) {
        if (progress.has("error")) return "History import unavailable: " + progress.optString("error");
        return progress.optString("phase", "unknown") + " · "
                + progress.optInt("conversations_done", 0) + "/" + progress.optInt("conversations_total", 0)
                + " conversations · " + progress.optInt("messages_imported", 0) + " new messages · "
                + progress.optInt("errors", 0) + " errors";
    }
}
