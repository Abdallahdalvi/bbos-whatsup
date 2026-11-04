package com.aghani.q20messenger;

import android.support.v7.app.AppCompatActivity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class ContactsActivity extends BaseActivity {
    private final ArrayList<JSONObject> all = new ArrayList<JSONObject>();
    private final ArrayList<JSONObject> filtered = new ArrayList<JSONObject>();
    private ContactAdapter adapter;
    private EditText search;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(getResources().getColor(R.color.paper));
        LinearLayout header = Ui.horizontal(this);
        header.setBackgroundColor(getResources().getColor(R.color.paper));
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        android.widget.Button back = Ui.button(this, "Back");
        header.addView(back);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { finish(); }
        });
        TextView title = Ui.text(this, "Contacts", 18, getResources().getColor(R.color.ink));
        header.addView(title);
        root.addView(header);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search contacts");
        search.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        status = Ui.text(this, "Loading contacts...", 11, getResources().getColor(R.color.muted));
        status.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        root.addView(status);
        ListView list = new ListView(this);
        adapter = new ContactAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                JSONObject item = filtered.get(position);
                Intent intent = new Intent(ContactsActivity.this, ChatActivity.class);
                intent.putExtra("peer_jid", item.optString("id", ""));
                intent.putExtra("peer_name", item.optString("name", "Contact"));
                startActivity(intent);
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) { applyFilter(value.toString()); }
            public void afterTextChanged(Editable value) { }
        });
        setContentView(root);
        loadContacts();
    }

    private void loadContacts() {
        new AsyncTask<Void, Void, String>() {
            protected String doInBackground(Void... params) {
                try {
                    ApiClient api = new ApiClient(ContactsActivity.this);
                    for (int page = 0; page < 100; page++) {
                        JSONObject response = api.getContacts("", page);
                        JSONArray array = response.optJSONArray("contacts");
                        if (array == null || array.length() == 0) break;
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject item = array.optJSONObject(i);
                            if (item != null) all.add(item);
                        }
                        if (!response.optBoolean("has_more", false)) break;
                    }
                    return null;
                } catch (Exception error) {
                    return error.getMessage();
                }
            }
            protected void onPostExecute(String error) {
                if (error != null) {
                    status.setText("Contacts unavailable: " + error);
                    SafeToast.makeText(ContactsActivity.this, error, Toast.LENGTH_LONG).show();
                    return;
                }
                applyFilter("");
                status.setText(all.size() + " contacts loaded. Existing groups are listed in Chats.");
            }
        }.execute();
    }

    private void applyFilter(String term) {
        filtered.clear();
        String lower = term == null ? "" : term.toLowerCase();
        for (int i = 0; i < all.size(); i++) {
            JSONObject item = all.get(i);
            if (item.optString("name", "").toLowerCase().contains(lower)
                    || item.optString("id", "").toLowerCase().contains(lower)) {
                filtered.add(item);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private class ContactAdapter extends BaseAdapter {
        public int getCount() { return filtered.size(); }
        public Object getItem(int position) { return filtered.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convertView, ViewGroup parent) {
            JSONObject item = filtered.get(position);
            LinearLayout row = Ui.vertical(ContactsActivity.this);
            row.setPadding(Ui.dp(ContactsActivity.this, 12), Ui.dp(ContactsActivity.this, 9),
                    Ui.dp(ContactsActivity.this, 12), Ui.dp(ContactsActivity.this, 9));
            TextView name = Ui.text(ContactsActivity.this, item.optString("name", "Contact"), 15,
                    getResources().getColor(R.color.ink));
            TextView jid = Ui.text(ContactsActivity.this, item.optString("id", ""), 10,
                    getResources().getColor(R.color.muted));
            row.addView(name);
            row.addView(jid);
            return row;
        }
    }
}
