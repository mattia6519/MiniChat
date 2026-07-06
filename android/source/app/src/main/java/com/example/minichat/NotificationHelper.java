package com.example.minichat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class NotificationHelper {
    private static final String CHANNEL_MESSAGES = "messages";
    private static final String CHANNEL_SERVICE = "connection";

    private NotificationHelper() {
    }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_MESSAGES,
                "Messaggi",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Notifiche per nuovi messaggi MiniChat");
        manager.createNotificationChannel(channel);

        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_SERVICE,
                "Connessione",
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("Servizio connessione MiniChat");
        manager.createNotificationChannel(serviceChannel);
    }

    static Notification buildServiceNotification(Context context) {
        Intent intent = new Intent(context, ContactsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 7, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_SERVICE)
                : new Notification.Builder(context);

        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("MiniChat attiva")
                .setContentText("Connessione in background")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    static void showMessage(Context context, Contact contact, ChatMessage message) {
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CONTACT_ID, contact.id);
        intent.putExtra(ChatActivity.EXTRA_CONTACT_NAME, contact.name);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, contact.id, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_MESSAGES)
                : new Notification.Builder(context);

        String preview = CryptoUtils.isEncrypted(message.text) ? "Messaggio criptato" : message.text;

        builder.setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(contact.name)
                .setContentText(preview)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setShowWhen(true)
                .setWhen(message.timestamp);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1000 + contact.id, builder.build());
        }
    }
}
