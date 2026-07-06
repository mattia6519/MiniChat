package com.example.minichat;

import android.app.Application;

public class MiniChatApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppSecureStore.warmUp(this);
        NotificationHelper.createChannels(this);
        ChatClient.getInstance().init(this);
    }
}
