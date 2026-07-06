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
import java.util.Collection;
import java.util.LinkedHashMap;

final class ContactStore {
    private ContactStore() {
    }

    static LinkedHashMap<Integer, Contact> load(Context context, String ownerPhone) {
        LinkedHashMap<Integer, Contact> contacts = new LinkedHashMap<>();
        File file = fileFor(context, ownerPhone);
        if (!file.exists()) {
            return contacts;
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
            JSONArray items = root.optJSONArray("contacts");
            if (items == null) {
                return contacts;
            }

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int id = item.optInt("id", -1);
                if (id <= 0) {
                    continue;
                }
                String name = item.optString("name", "Utente " + id);
                String phone = PhoneUtils.normalize(item.optString("phone", ""));
                contacts.put(id, new Contact(id, name, phone, false));
            }

            if (!wasEncrypted) {
                save(context, ownerPhone, contacts.values());
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return contacts;
    }

    static void save(Context context, String ownerPhone, Collection<Contact> contacts) {
        try {
            JSONObject root = new JSONObject();
            JSONArray items = new JSONArray();
            for (Contact contact : contacts) {
                if (contact.id <= 0) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("id", contact.id);
                item.put("name", contact.name == null || contact.name.trim().isEmpty() ? "Utente " + contact.id : contact.name);
                item.put("phone", PhoneUtils.normalize(contact.phone));
                items.put(item);
            }
            root.put("contacts", items);

            FileOutputStream output = new FileOutputStream(fileFor(context, ownerPhone));
            OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            try {
                writer.write(AppSecureStore.encrypt(context, root.toString()));
                writer.flush();
                output.getFD().sync();
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
        return new File(context.getFilesDir(), "contacts_" + safe + ".json");
    }
}
