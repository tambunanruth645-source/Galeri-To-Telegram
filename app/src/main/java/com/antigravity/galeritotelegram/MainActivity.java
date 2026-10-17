package com.antigravity.galeritotelegram;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 100;
    private static final String PREFS_NAME = "GaleriTelegramPrefs";
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_CHAT_ID = "chat_id";

    private TextInputEditText etBotToken;
    private TextInputEditText etChatId;
    private SwitchMaterial switchAutoUpload;
    private TextView tvLogs;
    private TextView tvBackupStats;
    private ProgressBar pbBackup;
    private MaterialButton btnToggleBackup;

    private boolean isBackupRunning = false;
    private SharedPreferences prefs;
    private DatabaseHelper dbHelper;

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (GalleryObserverService.ACTION_STATUS_BROADCAST.equals(action)) {
                String logMsg = intent.getStringExtra(GalleryObserverService.EXTRA_LOG_MESSAGE);
                if (logMsg != null) {
                    appendLog(logMsg);
                }
            } else if (BackupService.ACTION_BACKUP_PROGRESS.equals(action)) {
                int current = intent.getIntExtra(BackupService.EXTRA_CURRENT, 0);
                int total = intent.getIntExtra(BackupService.EXTRA_TOTAL, 0);
                String status = intent.getStringExtra(BackupService.EXTRA_STATUS);
                boolean running = intent.getBooleanExtra(BackupService.EXTRA_IS_RUNNING, false);

                isBackupRunning = running;
                btnToggleBackup.setText(running ? R.string.btn_stop_backup : R.string.btn_start_backup);

                if (total > 0) {
                    int percent = (int) (((float) current / total) * 100);
                    pbBackup.setProgress(percent);
                    tvBackupStats.setText(status + " (" + percent + "%)");
                } else if (status != null) {
                    tvBackupStats.setText(status);
                }

                if (status != null) {
                    appendLog(status);
                }

                if (!running) {
                    refreshGalleryStats();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        dbHelper = DatabaseHelper.getInstance(this);

        etBotToken = findViewById(R.id.etBotToken);
        etChatId = findViewById(R.id.etChatId);
        switchAutoUpload = findViewById(R.id.switchAutoUpload);
        tvLogs = findViewById(R.id.tvLogs);
        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        tvBackupStats = findViewById(R.id.tvBackupStats);
        pbBackup = findViewById(R.id.pbBackup);
        btnToggleBackup = findViewById(R.id.btnToggleBackup);

        MaterialButton btnSave = findViewById(R.id.btnSave);
        MaterialButton btnTestUpload = findViewById(R.id.btnTestUpload);

        // Load saved settings
        etBotToken.setText(prefs.getString(KEY_BOT_TOKEN, ""));
        etChatId.setText(prefs.getString(KEY_CHAT_ID, ""));
        switchAutoUpload.setChecked(prefs.getBoolean(GalleryObserverService.KEY_AUTO_ENABLED, false));

        btnSave.setOnClickListener(v -> saveSettings());

        btnToggleBackup.setOnClickListener(v -> {
            if (checkAndRequestPermissions()) {
                toggleBackupProcess();
            }
        });

        switchAutoUpload.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (checkAndRequestPermissions()) {
                    startObserverService();
                } else {
                    switchAutoUpload.setChecked(false);
                }
            } else {
                stopObserverService();
            }
        });

        btnTestUpload.setOnClickListener(v -> {
            if (checkAndRequestPermissions()) {
                testUploadLatestPhoto();
            }
        });

        if (checkAndRequestPermissions()) {
            refreshGalleryStats();
        }
    }

    private void saveSettings() {
        String token = etBotToken.getText() != null ? etBotToken.getText().toString().trim() : "";
        String chatId = etChatId.getText() != null ? etChatId.getText().toString().trim() : "";

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Mohon lengkapi Token Bot dan Chat ID!", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit()
                .putString(KEY_BOT_TOKEN, token)
                .putString(KEY_CHAT_ID, chatId)
                .apply();

        Toast.makeText(this, "Pengaturan berhasil disimpan!", Toast.LENGTH_SHORT).show();
        appendLog("[Info] Pengaturan Bot Token & Chat ID disimpan.");
    }

    private void toggleBackupProcess() {
        String token = prefs.getString(KEY_BOT_TOKEN, "");
        String chatId = prefs.getString(KEY_CHAT_ID, "");

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Simpan Token Bot & Chat ID terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, BackupService.class);
        if (!isBackupRunning) {
            isBackupRunning = true;
            btnToggleBackup.setText(R.string.btn_stop_backup);
            serviceIntent.setAction(BackupService.ACTION_START_BACKUP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            appendLog("[Backup] Memulai proses upload semua foto...");
        } else {
            isBackupRunning = false;
            btnToggleBackup.setText(R.string.btn_start_backup);
            serviceIntent.setAction(BackupService.ACTION_STOP_BACKUP);
            startService(serviceIntent);
            appendLog("[Backup] Menghentikan backup...");
        }
    }

    private void refreshGalleryStats() {
        try {
            int totalPhotos = 0;
            String[] projection = new String[]{MediaStore.Images.Media._ID};
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
            )) {
                if (cursor != null) {
                    totalPhotos = cursor.getCount();
                }
            }

            int uploadedCount = dbHelper.getUploadedCount();
            tvBackupStats.setText("Foto di Galeri: " + totalPhotos + " | Sudah di-backup: " + uploadedCount);
            if (totalPhotos > 0) {
                pbBackup.setProgress((int) (((float) uploadedCount / totalPhotos) * 100));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQ_CODE);
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                appendLog("[Izin] Akses galeri & notifikasi diberikan.");
                refreshGalleryStats();
            } else {
                Toast.makeText(this, "Izin galeri diperlukan agar aplikasi dapat membaca foto!", Toast.LENGTH_LONG).show();
                appendLog("[Izin] Akses ditolak oleh pengguna.");
                switchAutoUpload.setChecked(false);
            }
        }
    }

    private void startObserverService() {
        String token = prefs.getString(KEY_BOT_TOKEN, "");
        String chatId = prefs.getString(KEY_CHAT_ID, "");

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Isi dan Simpan Token Bot & Chat ID terlebih dahulu!", Toast.LENGTH_SHORT).show();
            switchAutoUpload.setChecked(false);
            return;
        }

        prefs.edit().putBoolean(GalleryObserverService.KEY_AUTO_ENABLED, true).apply();

        Intent serviceIntent = new Intent(this, GalleryObserverService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        appendLog("[Auto-Upload] Pemantau foto baru aktif di background.");
    }

    private void stopObserverService() {
        prefs.edit().putBoolean(GalleryObserverService.KEY_AUTO_ENABLED, false).apply();
        Intent serviceIntent = new Intent(this, GalleryObserverService.class);
        stopService(serviceIntent);
        appendLog("[Auto-Upload] Pemantau foto baru dinonaktifkan.");
    }

    private void testUploadLatestPhoto() {
        String token = prefs.getString(KEY_BOT_TOKEN, "");
        String chatId = prefs.getString(KEY_CHAT_ID, "");

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Simpan Token & Chat ID terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
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
                long imageId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                String imageName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME));
                Uri imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId);

                appendLog("[Test] Mengirim foto terakhir: " + imageName + "...");
                Toast.makeText(this, "Sedang mencoba kirim ke Telegram...", Toast.LENGTH_SHORT).show();

                TelegramUploader.sendPhoto(
                        this,
                        token,
                        chatId,
                        imageUri,
                        "🧪 Test kirim foto dari Galeri: " + imageName,
                        new TelegramUploader.UploadCallback() {
                            @Override
                            public void onSuccess(String message) {
                                dbHelper.markUploaded(imageId, imageName);
                                appendLog("✅ [Test Berhasil] " + message);
                                Toast.makeText(MainActivity.this, "Test Kirim Berhasil!", Toast.LENGTH_SHORT).show();
                                refreshGalleryStats();
                            }

                            @Override
                            public void onError(String errorMessage) {
                                appendLog("❌ [Test Gagal] " + errorMessage);
                                Toast.makeText(MainActivity.this, "Test Gagal: " + errorMessage, Toast.LENGTH_LONG).show();
                            }
                        }
                );
            } else {
                appendLog("[Test] Tidak ditemukan foto di galeri HP.");
                Toast.makeText(this, "Galeri kosong!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            appendLog("[Error Test] " + e.getMessage());
        }
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String newEntry = "[" + time + "] " + message + "\n";
        tvLogs.append(newEntry);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(GalleryObserverService.ACTION_STATUS_BROADCAST);
        filter.addAction(BackupService.ACTION_BACKUP_PROGRESS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, filter);
        }

        refreshGalleryStats();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(broadcastReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
