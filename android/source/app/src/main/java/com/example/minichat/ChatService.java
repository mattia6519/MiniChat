package com.example.minichat;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

public class ChatService extends Service {
    private static final int NOTIFICATION_ID = 42;

    static void start(Context context) {
        Intent intent = new Intent(context, ChatService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ChatService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppSecureStore.warmUp(this);
        ChatClient.getInstance().init(this);
        NotificationHelper.createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, NotificationHelper.buildServiceNotification(this));
        reconnectIfNeeded();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void reconnectIfNeeded() {
        if (ChatClient.getInstance().isConnected() || !Prefs.hasSession(this)) {
            syncContactsIfAllowed();
            return;
        }

        ChatClient.getInstance().login(
                Prefs.getHost(this),
                Prefs.getPort(this),
                Prefs.useTls(this),
                Prefs.getPhone(this),
                Prefs.getPassword(this),
                new ChatClient.ConnectCallback() {
                    @Override
                    public void onSuccess() {
                        syncContactsIfAllowed();
                    }

                    @Override
                    public void onError(String message) {
                        ChatClient.getInstance().prepareOffline(ChatService.this);
                    }
                }
        );
    }

    private void syncContactsIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ChatClient.getInstance().syncContacts(DeviceContacts.readPhoneBook(this));
    }
}
