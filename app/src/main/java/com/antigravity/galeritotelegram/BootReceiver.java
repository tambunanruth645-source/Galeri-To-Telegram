package com.antigravity.galeritotelegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("GaleriTelegramPrefs", Context.MODE_PRIVATE);
            boolean isAutoEnabled = prefs.getBoolean(GalleryObserverService.KEY_AUTO_ENABLED, false);

            if (isAutoEnabled) {
                Intent serviceIntent = new Intent(context, GalleryObserverService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
