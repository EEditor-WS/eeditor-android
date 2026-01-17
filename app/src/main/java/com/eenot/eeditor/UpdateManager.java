package com.eenot.eeditor;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import android.content.Context;
import android.os.AsyncTask;
import java.io.*;
import java.net.URL;
import java.net.HttpURLConnection;  // Add this import
import java.util.zip.ZipInputStream;

public class UpdateManager {
    private static final String CHANNEL_ID = "update_channel";
    private static final int NOTIFICATION_ID = 1;
    private final NotificationManager notificationManager;
    private NotificationCompat.Builder builder;
    private final Context context;
    private final String versionUrl = "https://raw.githubusercontent.com/eenot-eenot/EEditor-Warnament-Scenario/refs/heads/main/version.txt";
    private final String repoUrl = "https://github.com/eenot-eenot/EEditor-Warnament-Scenario/archive/refs/heads/main.zip";
    private final File localVersionFile;
    private final File scenarioDir;
    private UpdateCallback callback;

    public interface UpdateCallback {
        void onUpdateCompleted(boolean updated);
        void onUpdateProgress(boolean isLoading, int progress);
    }

    public UpdateManager(Context context) {
        this.context = context;
        this.localVersionFile = new File(context.getFilesDir(), "version.txt");
        this.scenarioDir = new File(context.getFilesDir(), "scenario");
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        builder = createNotificationBuilder();
    }

    private NotificationCompat.Builder createNotificationBuilder() {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
    }

    public void checkAndUpdate(UpdateCallback callback) {
        this.callback = callback;
        new UpdateCheckTask().execute();
    }

    public File getIndexHtmlFile() {
        return new File(scenarioDir, "index.html");
    }

    private class UpdateCheckTask extends AsyncTask<Void, Integer, Boolean> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (callback != null) {
                callback.onUpdateProgress(true, 0);
            }
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                publishProgress(0); // Начало проверки
                String remoteVersion = readUrl(versionUrl);
                String localVersion = "";
                
                if (localVersionFile.exists()) {
                    localVersion = readFile(localVersionFile);
                }

                if (localVersion.isEmpty() || remoteVersion.compareTo(localVersion) > 0) {
                    downloadAndExtract();
                    writeFile(localVersionFile, remoteVersion);
                    return true;
                }
                // Если обновление не требуется, отправляем 100% и завершаем
                publishProgress(100);
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (callback != null) {
                callback.onUpdateProgress(true, values[0]);
            }
        }

        @Override
        protected void onPostExecute(Boolean updated) {
            if (callback != null) {
                // Больше не отправляем прогресс здесь
                callback.onUpdateCompleted(updated);
            }
        }

        private String readUrl(String urlString) throws IOException {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new URL(urlString).openStream()))) {
                return reader.readLine().trim();
            }
        }

        private void downloadAndExtract() throws IOException {
            File tempFile = new File(context.getCacheDir(), "scenario.zip");
            
            // Download with progress
            publishProgress(5); // Начинаем загрузку
            URL url = new URL(repoUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.connect();
            
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + connection.getResponseCode());
            }
            
            int fileLength = connection.getContentLength();
            if (fileLength <= 0) {
                fileLength = 1000000;
            }
            
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(tempFile)) {
                
                byte[] buffer = new byte[4096];
                long totalBytesRead = 0;
                int bytesRead;
                int lastProgress = 5;
                
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    int progress = 5 + (int) (totalBytesRead * 40 / fileLength); // 5-45%
                    if (progress > lastProgress) {
                        publishProgress(progress);
                        lastProgress = progress;
                    }
                }
            } finally {
                connection.disconnect();
            }

            // Extract files
            deleteRecursive(scenarioDir);
            scenarioDir.mkdirs();

            publishProgress(50); // Начало распаковки
            int filesExtracted = 0;
            int totalFiles = countZipEntries(tempFile);
            
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(tempFile))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        String fileName = entry.getName().substring(entry.getName().indexOf("/") + 1);
                        File destFile = new File(scenarioDir, fileName);
                        File parent = destFile.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        
                        try (FileOutputStream output = new FileOutputStream(destFile)) {
                            byte[] buffer = new byte[4096];
                            int len;
                            while ((len = zip.read(buffer)) > 0) {
                                output.write(buffer, 0, len);
                            }
                        }
                        
                        filesExtracted++;
                        int extractProgress = 50 + (filesExtracted * 45 / Math.max(totalFiles, 1)); // 50-95%
                        publishProgress(extractProgress);
                    }
                    zip.closeEntry();
                }
            }
            
            tempFile.delete();
            publishProgress(100); // Завершение
        }

        private int countZipEntries(File zipFile) {
            int count = 0;
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
                while (zip.getNextEntry() != null) {
                    count++;
                    zip.closeEntry();
                }
            } catch (IOException e) {
                e.printStackTrace();
                return 0;
            }
            return Math.max(count, 1);
        }

        private void deleteRecursive(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                for (File child : fileOrDirectory.listFiles()) {
                    deleteRecursive(child);
                }
            }
            fileOrDirectory.delete();
        }

        private String readFile(File file) throws IOException {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                return reader.readLine().trim();
            }
        }

        private void writeFile(File file, String content) throws IOException {
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
        }
    }
}
