package com.example.minichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends Activity {
    static final String EXTRA_CONTACT_ID = "contact_id";
    static final String EXTRA_CONTACT_NAME = "contact_name";
    static final String EXTRA_CONTACT_PHONE = "contact_phone";

    private int contactId;
    private String contactName;
    private String contactPhone;
    private MessageAdapter adapter;
    private EditText messageInput;
    private Button sendButton;
    private ListView listView;
    private String encryptionPassword = "";
    private String chatKeyId = "";

    private final ChatClient.Listener listener = new ChatClient.Listener() {
        @Override
        public void onConnectionState(String state) {
        }

        @Override
        public void onContactsChanged(List<Contact> contacts) {
        }

        @Override
        public void onMessageReceived(ChatMessage message, Contact contact) {
            int otherId = message.outgoing ? message.toId : message.fromId;
            if (otherId == contactId) {
                refreshMessages();
            }
        }

        @Override
        public void onError(String message) {
            Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        contactId = getIntent().getIntExtra(EXTRA_CONTACT_ID, -1);
        contactName = getIntent().getStringExtra(EXTRA_CONTACT_NAME);
        contactPhone = getIntent().getStringExtra(EXTRA_CONTACT_PHONE);
        if (contactName == null || contactName.trim().isEmpty()) {
            contactName = "Utente " + contactId;
        }
        if (contactPhone == null) {
            contactPhone = "";
        }
        chatKeyId = ChatClient.getInstance().getMyPhone() + ":" + contactId;
        encryptionPassword = ChatPasswordStore.getPassword(this, ChatClient.getInstance().getMyPhone(), contactId);
        if (encryptionPassword.isEmpty()) {
            encryptionPassword = ChatPasswordStore.getSessionPassword(ChatClient.getInstance().getMyPhone(), contactId);
        }
        unlockChatKey();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatClient.getInstance().setActiveConversationId(contactId);
        ChatClient.getInstance().addListener(listener);
        refreshMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatClient.getInstance().setActiveConversationId(-1);
        ChatClient.getInstance().removeListener(listener);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.bg);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        toolbar.setBackgroundResource(R.drawable.bg_toolbar);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 64)
        ));

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_media_previous);
        back.setContentDescription("Indietro");
        back.setBackgroundColor(0x00000000);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        TextView title = Ui.text(this, contactName, 20, 0xFF17211D);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ImageButton lock = new ImageButton(this);
        lock.setImageResource(android.R.drawable.ic_lock_lock);
        lock.setContentDescription("Password cifratura");
        lock.setBackgroundColor(0x00000000);
        lock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEncryptionDialog();
            }
        });
        toolbar.addView(lock, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        listView.setStackFromBottom(true);
        adapter = new MessageAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        composer.setBackgroundResource(R.drawable.bg_toolbar);
        root.addView(composer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 68)
        ));

        messageInput = Ui.input(this, "Messaggio");
        messageInput.setSingleLine(false);
        messageInput.setMinLines(1);
        messageInput.setMaxLines(3);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.addView(messageInput, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        ));

        sendButton = Ui.primaryButton(this, "Invia");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 84),
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        sendParams.setMargins(Ui.dp(this, 8), 0, 0, 0);
        composer.addView(sendButton, sendParams);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCurrentMessage();
            }
        });

        setContentView(root);
    }

    private void sendCurrentMessage() {
        final String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        final String payload;
        try {
            payload = CryptoUtils.encryptWithCachedKey(text, chatKeyId);
        } catch (Exception e) {
            Toast.makeText(this, "Cifratura fallita", Toast.LENGTH_LONG).show();
            return;
        }
        sendButton.setEnabled(false);
        ChatClient.getInstance().sendMessage(contactId, payload, new ChatClient.SendCallback() {
            @Override
            public void onSent() {
                messageInput.setText("");
                sendButton.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                sendButton.setEnabled(true);
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showEncryptionDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);

        final EditText input = Ui.input(this, "Password cifratura");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(encryptionPassword);
        input.setSelection(input.getText().length());
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        ));

        final CheckBox persistent = new CheckBox(this);
        persistent.setText("Salva password su questo dispositivo");
        persistent.setTextSize(15);
        persistent.setTextColor(0xFF17211D);
        persistent.setChecked(true);
        LinearLayout.LayoutParams persistentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        persistentParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        panel.addView(persistent, persistentParams);

        new AlertDialog.Builder(this)
                .setTitle("Cifratura chat")
                .setView(panel)
                .setPositiveButton("Salva", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        encryptionPassword = input.getText().toString();
                        if (encryptionPassword.isEmpty()) {
                            ChatPasswordStore.saveSessionPassword(ChatClient.getInstance().getMyPhone(), contactId, "");
                            ChatPasswordStore.clearPersistentPassword(ChatActivity.this, ChatClient.getInstance().getMyPhone(), contactId);
                        } else if (persistent.isChecked()) {
                            if (!ChatPasswordStore.savePassword(ChatActivity.this, ChatClient.getInstance().getMyPhone(), contactId, encryptionPassword)) {
                                Toast.makeText(ChatActivity.this, "Password usata solo per questa sessione", Toast.LENGTH_LONG).show();
                                ChatPasswordStore.saveSessionPassword(ChatClient.getInstance().getMyPhone(), contactId, encryptionPassword);
                            }
                        } else {
                            ChatPasswordStore.clearPersistentPassword(ChatActivity.this, ChatClient.getInstance().getMyPhone(), contactId);
                            ChatPasswordStore.saveSessionPassword(ChatClient.getInstance().getMyPhone(), contactId, encryptionPassword);
                        }
                        unlockChatKey();
                        refreshMessages();
                    }
                })
                .setNegativeButton("Rimuovi", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        encryptionPassword = "";
                        CryptoUtils.clearChatKey(chatKeyId);
                        ChatPasswordStore.saveSessionPassword(ChatClient.getInstance().getMyPhone(), contactId, "");
                        ChatPasswordStore.clearPersistentPassword(ChatActivity.this, ChatClient.getInstance().getMyPhone(), contactId);
                        refreshMessages();
                    }
                })
                .setNeutralButton("Annulla", null)
                .show();
    }

    private void unlockChatKey() {
        if (encryptionPassword == null || encryptionPassword.isEmpty()) {
            CryptoUtils.clearChatKey(chatKeyId);
            return;
        }
        CryptoUtils.unlockChatKey(chatKeyId, encryptionPassword, ChatClient.getInstance().getMyId(), contactId);
    }

    private void refreshMessages() {
        adapter.setMessages(ChatClient.getInstance().getConversation(contactId));
        if (adapter.getCount() > 0) {
            listView.setSelection(adapter.getCount() - 1);
        }
    }

    private final class MessageAdapter extends BaseAdapter {
        private final ArrayList<ChatMessage> messages = new ArrayList<>();

        void setMessages(List<ChatMessage> newMessages) {
            messages.clear();
            messages.addAll(newMessages);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return messages.size();
        }

        @Override
        public Object getItem(int position) {
            return messages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout row;
            TextView bubble;
            if (convertView instanceof FrameLayout && ((FrameLayout) convertView).getChildCount() > 0) {
                row = (FrameLayout) convertView;
                bubble = (TextView) row.getChildAt(0);
            } else {
                row = new FrameLayout(ChatActivity.this);
                row.setPadding(Ui.dp(ChatActivity.this, 12), Ui.dp(ChatActivity.this, 4), Ui.dp(ChatActivity.this, 12), Ui.dp(ChatActivity.this, 4));
                bubble = new TextView(ChatActivity.this);
                bubble.setTextSize(16);
                bubble.setLineSpacing(0, 1.08f);
                row.addView(bubble);
            }

            ChatMessage message = messages.get(position);
            bubble.setText(CryptoUtils.decryptForDisplay(message.text, chatKeyId));
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.76f));
            bubble.setTextColor(0xFF17211D);
            bubble.setBackgroundResource(message.outgoing ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = message.outgoing ? Gravity.RIGHT : Gravity.LEFT;
            bubble.setLayoutParams(params);
            return row;
        }
    }
}
