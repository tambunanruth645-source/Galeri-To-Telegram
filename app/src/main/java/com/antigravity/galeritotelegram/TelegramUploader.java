package com.antigravity.galeritotelegram;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

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

    public static void sendPhoto(Context context, String botToken, String chatId, Uri imageUri, String caption, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (botToken == null || botToken.trim().isEmpty() || chatId == null || chatId.trim().isEmpty()) {
                    notifyError(callback, "Token Bot atau Chat ID belum diisi!");
                    return;
                }

                InputStream inputStream = context.getContentResolver().openInputStream(imageUri);
                if (inputStream == null) {
                    notifyError(callback, "Gagal membaca file gambar dari galeri!");
                    return;
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
                    notifyError(callback, "File gambar kosong atau masih diproses kamera!");
                    return;
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

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        notifyError(callback, "Koneksi gagal: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws java.io.IOException {
                        String respBody = response.body() != null ? response.body().string() : "";
                        if (response.isSuccessful()) {
                            notifySuccess(callback, "Berhasil terkirim ke Telegram!");
                        } else {
                            notifyError(callback, "Telegram API Error (" + response.code() + "): " + respBody);
                        }
                    }
                });

            } catch (Exception e) {
                notifyError(callback, "Error: " + e.getMessage());
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
