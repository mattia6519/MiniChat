package com.example.minichat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class ContactsActivity extends Activity {
    private static final int REQ_CONTACTS = 100;

    private final ArrayList<Contact> contacts = new ArrayList<>();
    private ArrayAdapter<Contact> adapter;
    private TextView emptyView;

    private final ChatClient.Listener listener = new ChatClient.Listener() {
        @Override
        public void onConnectionState(String state) {
        }

        @Override
        public void onContactsChanged(List<Contact> newContacts) {
            contacts.clear();
            contacts.addAll(newContacts);
            adapter.notifyDataSetChanged();
            updateEmptyText();
        }

        @Override
        public void onMessageReceived(ChatMessage message, Contact contact) {
        }

        @Override
        public void onError(String message) {
            Toast.makeText(ContactsActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ChatClient.getInstance().isConnected() && !Prefs.hasSession(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (!ChatClient.getInstance().isConnected()) {
            ChatClient.getInstance().prepareOffline(this);
        }
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatClient.getInstance().addListener(listener);
        contacts.clear();
        contacts.addAll(ChatClient.getInstance().getContactsSnapshot());
        adapter.notifyDataSetChanged();
        if (ChatClient.getInstance().isConnected()) {
            syncContactsFromDevice();
        } else {
            updateEmptyText();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ChatClient.getInstance().removeListener(listener);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                syncContactsFromDevice();
            } else {
                ChatClient.getInstance().syncContacts(new ArrayList<String>());
                updateEmptyText();
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.bg);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        toolbar.setBackgroundResource(R.drawable.bg_toolbar);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 64)
        ));

        TextView title = Ui.text(this, "Rubrica", 22, 0xFF17211D);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ImageButton refresh = new ImageButton(this);
        refresh.setImageResource(android.R.drawable.ic_menu_rotate);
        refresh.setContentDescription("Aggiorna rubrica");
        refresh.setBackgroundColor(0x00000000);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncContactsFromDevice();
            }
        });
        toolbar.addView(refresh, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        ImageButton settings = new ImageButton(this);
        settings.setImageResource(android.R.drawable.ic_menu_manage);
        settings.setContentDescription("Impostazioni");
        settings.setBackgroundColor(0x00000000);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ContactsActivity.this, SettingsActivity.class));
            }
        });
        toolbar.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        ImageButton logout = new ImageButton(this);
        logout.setImageResource(android.R.drawable.ic_lock_power_off);
        logout.setContentDescription("Esci");
        logout.setBackgroundColor(0x00000000);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatClient.getInstance().disconnect();
                Prefs.clearSession(ContactsActivity.this);
                ChatService.stop(ContactsActivity.this);
                startActivity(new Intent(ContactsActivity.this, LoginActivity.class));
                finish();
            }
        });
        toolbar.addView(logout, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, contacts);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Contact contact = contacts.get(position);
                Intent intent = new Intent(ContactsActivity.this, ChatActivity.class);
                intent.putExtra(ChatActivity.EXTRA_CONTACT_ID, contact.id);
                intent.putExtra(ChatActivity.EXTRA_CONTACT_NAME, contact.name);
                intent.putExtra(ChatActivity.EXTRA_CONTACT_PHONE, contact.phone);
                startActivity(intent);
            }
        });
        frame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        emptyView = Ui.subtitle(this, "");
        emptyView.setGravity(Gravity.CENTER);
        frame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        listView.setEmptyView(emptyView);

        setContentView(root);
        updateEmptyText();
    }

    private void syncContactsFromDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            emptyView.setText("Consenti l'accesso alla rubrica per trovare i contatti registrati");
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
            return;
        }

        emptyView.setText("Aggiornamento rubrica...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.LinkedHashMap<String, String> phoneBook = DeviceContacts.readPhoneBook(ContactsActivity.this);
                ChatClient.getInstance().syncContacts(phoneBook);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateEmptyText();
                    }
                });
            }
        }, "MiniChat-device-contacts").start();
    }

    private void updateEmptyText() {
        if (emptyView == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            if (ChatClient.getInstance().isConnected()) {
                emptyView.setText("Rubrica non autorizzata");
            } else {
                emptyView.setText("Offline: nessuna chat salvata su questo dispositivo");
            }
        } else if (!ChatClient.getInstance().isConnected()) {
            emptyView.setText("Offline: nessuna chat salvata su questo dispositivo");
        } else {
            emptyView.setText("Nessun contatto registrato nella tua rubrica");
        }
    }
}
