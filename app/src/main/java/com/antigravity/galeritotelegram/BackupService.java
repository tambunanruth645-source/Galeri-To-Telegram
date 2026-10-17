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
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class BackupService extends Service {

    public static final String ACTION_START_BACKUP = "com.antigravity.galeritotelegram.START_BACKUP";
    public static final String ACTION_STOP_BACKUP = "com.antigravity.galeritotelegram.STOP_BACKUP";

    public static final String ACTION_BACKUP_PROGRESS = "com.antigravity.galeritotelegram.BACKUP_PROGRESS";
    public static final String EXTRA_CURRENT = "extra_current";
    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_STATUS = "extra_status";
    public static final String EXTRA_IS_RUNNING = "extra_is_running";

    private static final String CHANNEL_ID = "BackupGaleriChannel";
    private static final int NOTIFICATION_ID = 2002;
    private static final String PREFS_NAME = "GaleriTelegramPrefs";

    private volatile boolean isRunning = false;
    private Thread workerThread;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Backup Seluruh Galeri ke Telegram",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Menampilkan kemajuan upload foto massal ke Telegram.");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(int current, int total, String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Backup Galeri ke Telegram")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (total > 0) {
            builder.setProgress(total, current, false);
        } else {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_BACKUP.equals(action)) {
                stopBackup();
                return START_NOT_STICKY;
            } else if (ACTION_START_BACKUP.equals(action)) {
                if (!isRunning) {
                    startForeground(NOTIFICATION_ID, buildNotification(0, 0, "Menyiapkan backup foto..."));
                    startBackupProcess();
                }
            }
        }
        return START_NOT_STICKY;
    }

    private void startBackupProcess() {
        isRunning = true;
        DatabaseHelper db = DatabaseHelper.getInstance(this);

        workerThread = new Thread(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String botToken = prefs.getString("bot_token", "");
            String chatId = prefs.getString("chat_id", "");

            if (botToken.isEmpty() || chatId.isEmpty()) {
                broadcastProgress(0, 0, "Gagal: Token Bot atau Chat ID belum disimpan!", false);
                stopForeground(true);
                stopSelf();
                return;
            }

            String[] projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
            };

            // Urutkan dari foto paling lama ke paling baru (kronologis)
            String sortOrder = MediaStore.Images.Media.DATE_ADDED + " ASC";

            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
            )) {
                if (cursor == null || cursor.getCount() == 0) {
                    broadcastProgress(0, 0, "Galeri HP kosong, tidak ada foto.", false);
                    stopForeground(true);
                    stopSelf();
                    return;
                }

                int totalPhotos = cursor.getCount();
                int currentProcessed = 0;
                int uploadedThisSession = 0;

                broadcastProgress(0, totalPhotos, "Memulai scanning " + totalPhotos + " foto...", true);

                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

                while (cursor.moveToNext() && isRunning) {
                    currentProcessed++;
                    long photoId = cursor.getLong(idColumn);
                    String photoName = cursor.getString(nameColumn);

                    // Lewati foto yang sudah pernah berhasil di-backup
                    if (db.isUploaded(photoId)) {
                        continue;
                    }

                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            photoId
                    );

                    String statusMsg = "Mengunggah (" + currentProcessed + "/" + totalPhotos + "): " + photoName;
                    broadcastProgress(currentProcessed, totalPhotos, statusMsg, true);
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(currentProcessed, totalPhotos, statusMsg));

                    // Upload foto dengan retry jika terkena limit 429
                    boolean uploadSuccess = false;
                    int maxRetry = 3;

                    while (!uploadSuccess && maxRetry > 0 && isRunning) {
                        TelegramUploader.UploadResult result = TelegramUploader.sendPhotoSync(
                                this,
                                botToken,
                                chatId,
                                contentUri,
                                "📁 Backup Galeri [" + currentProcessed + "/" + totalPhotos + "]: " + photoName
                        );

                        if (result.success) {
                            uploadSuccess = true;
                            uploadedThisSession++;
                            db.markUploaded(photoId, photoName);
                        } else if (result.retryAfterSeconds > 0) {
                            // Tunggu waktu cooldown dari Telegram
                            broadcastProgress(currentProcessed, totalPhotos, "Limit Telegram tercapai, jeda " + result.retryAfterSeconds + " detik...", true);
                            try {
                                Thread.sleep((result.retryAfterSeconds + 1) * 1000L);
                            } catch (InterruptedException e) {
                                isRunning = false;
                                break;
                            }
                        } else {
                            maxRetry--;
                            broadcastProgress(currentProcessed, totalPhotos, "Gagal upload: " + result.message + ", coba lagi...", true);
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                isRunning = false;
                                break;
                            }
                        }
                    }

                    // Jeda 1.5 detik antar foto agar aman dari rate limit Telegram
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        isRunning = false;
                        break;
                    }
                }

                if (isRunning) {
                    String finishMsg = "✅ Backup Selesai! (" + uploadedThisSession + " foto baru terkirim, total " + db.getUploadedCount() + " tersimpan di Telegram).";
                    broadcastProgress(totalPhotos, totalPhotos, finishMsg, false);
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(totalPhotos, totalPhotos, "Backup selesai!"));
                } else {
                    broadcastProgress(currentProcessed, totalPhotos, "⏹️ Backup dihentikan oleh pengguna.", false);
                }

            } catch (Exception e) {
                Log.e("BackupService", "Error backup: " + e.getMessage());
                broadcastProgress(0, 0, "Error saat backup: " + e.getMessage(), false);
            } finally {
                isRunning = false;
                stopForeground(false);
                stopSelf();
            }
        });

        workerThread.start();
    }

    private void stopBackup() {
        isRunning = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        broadcastProgress(0, 0, "Menghentikan proses backup...", false);
        stopForeground(true);
        stopSelf();
    }

    private void broadcastProgress(int current, int total, String status, boolean isRunning) {
        Intent intent = new Intent(ACTION_BACKUP_PROGRESS);
        intent.putExtra(EXTRA_CURRENT, current);
        intent.putExtra(EXTRA_TOTAL, total);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
