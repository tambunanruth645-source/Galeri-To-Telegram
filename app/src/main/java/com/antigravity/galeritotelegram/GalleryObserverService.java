package com.antigravity.galeritotelegram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class GalleryObserverService extends Service {

    public static final String ACTION_STATUS_BROADCAST = "com.antigravity.galeritotelegram.STATUS_UPDATE";
    public static final String EXTRA_LOG_MESSAGE = "log_message";

    private static final String CHANNEL_ID = "GaleriWatcherChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS_NAME = "GaleriTelegramPrefs";
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_CHAT_ID = "chat_id";
    public static final String KEY_AUTO_ENABLED = "auto_enabled";

    private ContentObserver imageObserver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingCheckRunnable = null;
    private boolean isUploading = false;
    private DatabaseHelper dbHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = DatabaseHelper.getInstance(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Memantau galeri foto..."));
        registerImageObserver();
        broadcastLog("Layanan auto-upload aktif. Menunggu jepretan kamera baru...");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pemantau Galeri Telegram",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Menjaga pemantau galeri tetap berjalan efisien di background.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_running_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void registerImageObserver() {
        imageObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);

                if (pendingCheckRunnable != null) {
                    handler.removeCallbacks(pendingCheckRunnable);
                }

                pendingCheckRunnable = () -> checkAndUploadLatestImage();
                handler.postDelayed(pendingCheckRunnable, 2000);
            }
        };

        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                imageObserver
        );
    }

    private void checkAndUploadLatestImage() {
        if (isUploading) return;

        String botToken = AppConfig.BOT_TOKEN;
        String chatId = AppConfig.CHAT_ID;

        if (botToken.isEmpty() || chatId.isEmpty()) {
            broadcastLog("[Peringatan] Token atau Chat ID belum disetel!");
            return;
        }

        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC";

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

                long imageId = cursor.getLong(idColumn);
                String imageName = cursor.getString(nameColumn);

                // Cek database apakah foto ini sudah pernah dikirim
                if (dbHelper.isUploaded(imageId)) {
                    return;
                }

                Uri contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId
                );

                isUploading = true;
                broadcastLog("Foto baru terdeteksi: " + imageName + ". Mengirim...");
                updateNotification("Mengirim: " + imageName);

                TelegramUploader.sendPhoto(
                        this,
                        botToken,
                        chatId,
                        contentUri,
                        "📸 Foto baru dari galeri: " + imageName,
                        new TelegramUploader.UploadCallback() {
                            @Override
                            public void onSuccess(String message) {
                                isUploading = false;
                                dbHelper.markUploaded(imageId, imageName);
                                broadcastLog("✅ Berhasil kirim foto baru: " + imageName);
                                updateNotification("Memantau galeri (Foto terakhir terkirim)");
                            }

                            @Override
                            public void onError(String errorMessage) {
                                isUploading = false;
                                broadcastLog("❌ Gagal kirim foto baru: " + errorMessage);
                                updateNotification("Gagal kirim, memantau...");
                            }
                        }
                );
            }
        } catch (Exception e) {
            Log.e("GaleriObserver", "Error query media: " + e.getMessage());
            broadcastLog("Error baca galeri: " + e.getMessage());
        }
    }

    private void broadcastLog(String log) {
        Intent intent = new Intent(ACTION_STATUS_BROADCAST);
        intent.putExtra(EXTRA_LOG_MESSAGE, log);
        sendBroadcast(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (imageObserver != null) {
            getContentResolver().unregisterContentObserver(imageObserver);
        }
        if (pendingCheckRunnable != null) {
            handler.removeCallbacks(pendingCheckRunnable);
        }
        broadcastLog("Layanan pemantau otomatis dimatikan.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
