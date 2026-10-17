package com.antigravity.galeritotelegram;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TelegramUploader {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface UploadCallback {
        void onSuccess(String message);
        void onError(String errorMessage);
    }

    public static class UploadResult {
        public boolean success;
        public String message;
        public int retryAfterSeconds = 0;

        public UploadResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public UploadResult(boolean success, String message, int retryAfter) {
            this.success = success;
            this.message = message;
            this.retryAfterSeconds = retryAfter;
        }
    }

    /**
     * Upload sinkron untuk Batch / Backup satu per satu di background thread.
     */
    public static UploadResult sendPhotoSync(Context context, String botToken, String chatId, Uri imageUri, String caption) {
        try {
            if (botToken == null || botToken.trim().isEmpty() || chatId == null || chatId.trim().isEmpty()) {
                return new UploadResult(false, "Token Bot atau Chat ID kosong!");
            }

            InputStream inputStream = context.getContentResolver().openInputStream(imageUri);
            if (inputStream == null) {
                return new UploadResult(false, "Tidak bisa membaca file gambar!");
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int nRead;
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            byte[] imageBytes = buffer.toByteArray();
            inputStream.close();

            if (imageBytes.length == 0) {
                return new UploadResult(false, "File kosong.");
            }

            RequestBody imageBody = RequestBody.create(
                    imageBytes,
                    MediaType.parse("image/jpeg")
            );

            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("photo", "photo_" + System.currentTimeMillis() + ".jpg", imageBody);

            if (caption != null && !caption.isEmpty()) {
                builder.addFormDataPart("caption", caption);
            }

            RequestBody requestBody = builder.build();
            String url = "https://api.telegram.org/bot" + botToken + "/sendPhoto";

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    return new UploadResult(true, "Berhasil");
                } else if (response.code() == 429) {
                    // Telegram Rate limit (Flood control)
                    int retryAfter = 5;
                    try {
                        JSONObject json = new JSONObject(respBody);
                        if (json.has("parameters")) {
                            retryAfter = json.getJSONObject("parameters").optInt("retry_after", 5);
                        }
                    } catch (Exception ignored) {
                    }
                    return new UploadResult(false, "Rate limit Telegram (429)", retryAfter);
                } else {
                    return new UploadResult(false, "Telegram API Error (" + response.code() + "): " + respBody);
                }
            }
        } catch (Exception e) {
            return new UploadResult(false, "Koneksi gagal: " + e.getMessage());
        }
    }

    /**
     * Upload asinkron untuk tombol Test Kirim.
     */
    public static void sendPhoto(Context context, String botToken, String chatId, Uri imageUri, String caption, UploadCallback callback) {
        new Thread(() -> {
            UploadResult result = sendPhotoSync(context, botToken, chatId, imageUri, caption);
            if (result.success) {
                notifySuccess(callback, result.message);
            } else {
                notifyError(callback, result.message);
            }
        }).start();
    }

    private static void notifySuccess(UploadCallback callback, String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(msg));
        }
    }

    private static void notifyError(UploadCallback callback, String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(msg));
        }
    }
}
