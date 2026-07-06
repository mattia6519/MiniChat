package com.example.minichat;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

final class DeviceContacts {
    private DeviceContacts() {
    }

    static ArrayList<String> readPhoneNumbers(Context context) {
        return new ArrayList<>(readPhoneBook(context).keySet());
    }

    static LinkedHashMap<String, String> readPhoneBook(Context context) {
        return readPhoneBook(context, Prefs.getCountryCode(context));
    }

    static LinkedHashMap<String, String> readPhoneBook(Context context, String defaultCountryCode) {
        LinkedHashMap<String, String> phoneBook = new LinkedHashMap<>();
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    },
                    null,
                    null,
                    null
            );
            if (cursor == null) {
                return phoneBook;
            }

            int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            int nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String number = PhoneUtils.normalizeInternational(cursor.getString(numberIndex), defaultCountryCode);
                if (number.length() >= 5) {
                    numbers.add(number);
                    if (!phoneBook.containsKey(number)) {
                        String name = nameIndex >= 0 ? cursor.getString(nameIndex) : "";
                        if (name == null || name.trim().isEmpty()) {
                            name = number;
                        }
                        phoneBook.put(number, name.trim());
                    }
                }
                if (numbers.size() >= 500) {
                    break;
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        for (String number : numbers) {
            if (!phoneBook.containsKey(number)) {
                phoneBook.put(number, number);
            }
        }
        return phoneBook;
    }
}
