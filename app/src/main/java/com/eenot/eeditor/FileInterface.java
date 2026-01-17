package com.eenot.eeditor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import org.json.JSONObject;
import java.io.File;
import java.io.OutputStream;

public class FileInterface {
    private Activity activity;
    private WebView webView;
    private static final int FILE_CHOOSER_RESULT_CODE = 1;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri selectedUri = null;
    private String currentFileName;
    private Uri currentFileUri;
    private String cachedFileContent;
    private boolean isContentReady = false;

    public interface ContentCallback {
        void onReady();
    }

    public FileInterface(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void openFilePicker() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            activity.startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
        });
    }

    @JavascriptInterface
    public void openAppSpecificFilePicker() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            
            // Get the app's external storage directory path
            File appExtDir = activity.getExternalFilesDir(null);
            if (appExtDir != null) {
                // Set initial URI to app's external directory
                intent.putExtra("android.provider.extra.INITIAL_URI", 
                    Uri.parse(appExtDir.getAbsolutePath()));
            }
            
            activity.startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
        });
    }

    @JavascriptInterface
    public String readAppSpecificFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            
            File appExtDir = activity.getExternalFilesDir(null);
            if (appExtDir != null) {
                intent.putExtra("android.provider.extra.INITIAL_URI", 
                    Uri.parse(appExtDir.getAbsolutePath()));
            }
            
            activity.startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
            
            while (selectedUri == null) {
                Thread.sleep(100);
            }

            // Сохраняем информацию о файле
            currentFileUri = selectedUri;
            currentFileName = getFileName(selectedUri);
            
            String content = readFileContent(selectedUri);
            selectedUri = null;
            return content;
            
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }

    @JavascriptInterface
    public void saveFile(String content) {
        try {
            if (currentFileUri != null) {
                OutputStream outputStream = activity.getContentResolver().openOutputStream(currentFileUri, "wt");
                if (outputStream != null) {
                    outputStream.write(content.getBytes());
                    outputStream.close();
                    
                    activity.runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            "showSuccess('Сохранено', 'Файл успешно сохранен')",
                            null
                        );
                    });
                }
            } else {
                throw new IOException("No file selected");
            }
        } catch (Exception e) {
            e.printStackTrace();
            final String errorMsg = e.getMessage();
            activity.runOnUiThread(() -> {
                webView.evaluateJavascript(
                    String.format("showError('Ошибка сохранения', '%s')", 
                        errorMsg.replace("'", "\\'")),
                    null
                );
            });
        }
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE && resultCode == Activity.RESULT_OK && data != null) {
            selectedUri = data.getData();
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    String content = readFileContent(uri);
                    String filename = getFileName(uri);
                    
                    // Create JSON object for validation and proper escaping
                    JSONObject contentObj = new JSONObject(content);
                    final String jsonString = contentObj.toString();
                    
                    activity.runOnUiThread(() -> {
                        // Pass data through evaluateJavascript instead of loadUrl
                        webView.evaluateJavascript(
                            String.format("handleFileContent('%s', %s)", 
                                filename.replace("'", "\\'"), 
                                jsonString),
                            null
                        );
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    final String errorMsg = e.getMessage();
                    activity.runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            String.format("showError('Ошибка чтения файла', '%s')", 
                                errorMsg.replace("'", "\\'")),
                            null
                        );
                    });
                }
            }
        }
    }

    public void setCurrentFileUri(Uri uri) {
        this.currentFileUri = uri;
        try {
            this.currentFileName = getFileName(uri);
            this.cachedFileContent = readFileContent(uri);
        } catch (Exception e) {
            e.printStackTrace();
            this.cachedFileContent = null;
        }
    }

    public String getCurrentFileName() {
        return currentFileName != null ? currentFileName : "";
    }

    public void prepareFileContent(ContentCallback callback) {
        new Thread(() -> {
            try {
                android.util.Log.d("FileInterface", "Preparing file content");
                if (currentFileUri != null && cachedFileContent == null) {
                    cachedFileContent = readFileContent(currentFileUri);
                    android.util.Log.d("FileInterface", "Content cached");
                }
                isContentReady = true;
                activity.runOnUiThread(callback::onReady);
            } catch (Exception e) {
                android.util.Log.e("FileInterface", "Error preparing content: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    public String getCurrentFileContent() throws IOException {
        if (!isContentReady) {
            android.util.Log.d("FileInterface", "Content not ready");
            throw new IOException("Content not ready");
        }
        if (currentFileUri == null) {
            android.util.Log.d("FileInterface", "No file selected");
            throw new IOException("No file selected");
        }
        if (cachedFileContent == null) {
            android.util.Log.d("FileInterface", "Reading file content");
            cachedFileContent = readFileContent(currentFileUri);
        }
        android.util.Log.d("FileInterface", "Returning cached content");
        return cachedFileContent;
    }

    public String getCurrentFilePath() {
        if (currentFileUri == null) return "";
        
        if ("file".equals(currentFileUri.getScheme())) {
            return currentFileUri.getPath();
        }
        
        return currentFileUri.toString();
    }

    @JavascriptInterface
    public String getOpenedFileData() {
        if (currentFileUri != null && cachedFileContent != null) {
            try {
                JSONObject response = new JSONObject();
                // response.put("fileName", getCurrentFileName());
                response.put("content", cachedFileContent);
                //return response.toString();
                return cachedFileContent;
            } catch (Exception e) {
                e.printStackTrace();
                return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }
        return "{\"error\": \"No file data available\"}";
    }

    private String readFileContent(Uri uri) throws IOException {
        StringBuilder content = new StringBuilder();
        InputStream inputStream = null;
        try {
            inputStream = activity.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                throw new IOException("Cannot open file stream");
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
}
