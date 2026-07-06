package com.example.minichat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

final class ConversationStore {
    private ConversationStore() {
    }

    static HashMap<Integer, ArrayList<ChatMessage>> load(Context context, String ownerPhone) {
        HashMap<Integer, ArrayList<ChatMessage>> conversations = new HashMap<>();
        File file = fileFor(context, ownerPhone);
        if (!file.exists()) {
            return conversations;
        }

        try {
            StringBuilder raw = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line);
                }
            } finally {
                reader.close();
            }

            String content = raw.toString();
            boolean wasEncrypted = AppSecureStore.isEncrypted(content);
            if (wasEncrypted) {
                content = AppSecureStore.decrypt(context, content);
            }

            JSONObject root = new JSONObject(content);
            JSONObject object = root.optJSONObject("conversations");
            if (object == null) {
                return conversations;
            }

            JSONArray names = object.names();
            if (names == null) {
                return conversations;
            }

            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                int contactId = Integer.parseInt(key);
                JSONArray items = object.optJSONArray(key);
                ArrayList<ChatMessage> messages = new ArrayList<>();
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        messages.add(new ChatMessage(
                                item.optInt("fromId"),
                                item.optInt("toId"),
                                item.optString("text"),
                                item.optBoolean("outgoing"),
                                item.optLong("timestamp")
                        ));
                    }
                }
                conversations.put(contactId, messages);
            }
            if (!wasEncrypted) {
                save(context, ownerPhone, conversations);
            }
        } catch (Exception ignored) {
            return new HashMap<>();
        }
        return conversations;
    }

    static void save(Context context, String ownerPhone, Map<Integer, ArrayList<ChatMessage>> conversations) {
        try {
            JSONObject root = new JSONObject();
            JSONObject object = new JSONObject();
            for (Map.Entry<Integer, ArrayList<ChatMessage>> entry : conversations.entrySet()) {
                JSONArray items = new JSONArray();
                for (ChatMessage message : entry.getValue()) {
                    JSONObject item = new JSONObject();
                    item.put("fromId", message.fromId);
                    item.put("toId", message.toId);
                    item.put("text", message.text);
                    item.put("outgoing", message.outgoing);
                    item.put("timestamp", message.timestamp);
                    items.put(item);
                }
                object.put(String.valueOf(entry.getKey()), items);
            }
            root.put("conversations", object);

            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(fileFor(context, ownerPhone)), StandardCharsets.UTF_8);
            try {
                writer.write(AppSecureStore.encrypt(context, root.toString()));
            } finally {
                writer.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static File fileFor(Context context, String ownerPhone) {
        String safe = PhoneUtils.normalize(ownerPhone);
        if (safe.isEmpty()) {
            safe = "default";
        }
        return new File(context.getFilesDir(), "conversations_" + safe + ".json");
    }
}
