package com.eenot.eeditor;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private FileInterfaceNew fileInterface;
    private static final int PERMISSION_REQUEST_CODE = 123;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        // Создаём интерфейс до настройки WebView чтобы можно было использовать его в shouldInterceptRequest
        fileInterface = new FileInterfaceNew(this, webView);
        setupWebView();

        checkAndRequestPermissions();

        // Проверяем, был ли запущен через intent открытия файла
        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            String type = intent.getType();
            if (Intent.ACTION_VIEW.equals(action)) {
                Uri fileUri = intent.getData();
                if (fileUri != null) {
                    Log.d("MainActivity", "Opening file: " + fileUri.toString());
                    Log.d("MainActivity", "File type: " + type);
                    fileInterface.setCurrentFileUri(fileUri);
                    loadLocalHtmlWithFile();
                } else {
                    Log.d("MainActivity", "No file URI provided");
                    loadLocalHtml();
                }
            } else {
                loadLocalHtml();
            }
        } else {
            loadLocalHtml();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri fileUri = intent.getData();
            if (fileUri != null) {
                Log.d("MainActivity", "Opening file from new intent: " + fileUri.toString());
                fileInterface.setCurrentFileUri(fileUri);
                loadLocalHtmlWithFile();
            }
        }
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webSettings.setDatabaseEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("WebView", consoleMessage.message());
                return true;
            }
        });

        webView.addJavascriptInterface(fileInterface, "Android");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                startActivity(intent);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d("WebViewLoad", "onPageStarted url=" + url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                return handleIntercept(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return handleIntercept(url);
            }

            private WebResourceResponse handleIntercept(String url) {
                if (url != null && url.startsWith("filedata://")) {
                    try {
                        String fileContent = fileInterface.getCurrentFileContent();
                        String fileName = fileInterface.getCurrentFileName();

                        String jsonResponse = String.format(
                                "{\"fileName\":\"%s\",\"content\":%s}",
                                fileName.replace("\"", "\\\""),
                                JSONObjectEscape(fileContent)
                        );

                        return new WebResourceResponse(
                                "application/json",
                                "UTF-8",
                                new ByteArrayInputStream(jsonResponse.getBytes(StandardCharsets.UTF_8))
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                        String errorJson = String.format(
                                "{\"error\":\"%s\"}",
                                (e.getMessage() == null ? "null" : e.getMessage().replace("\"", "\\\""))
                        );
                        return new WebResourceResponse(
                                "application/json",
                                "UTF-8",
                                new ByteArrayInputStream(errorJson.getBytes(StandardCharsets.UTF_8))
                        );
                    }
                }
                return null;
            }

            private String JSONObjectEscape(String s) {
                if (s == null) return "null";
                StringBuilder sb = new StringBuilder();
                sb.append("\"");
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '\\': sb.append("\\\\"); break;
                        case '"': sb.append("\\\""); break;
                        case '\b': sb.append("\\b"); break;
                        case '\f': sb.append("\\f"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (c < 0x20 || c > 0x7E) {
                                sb.append(String.format("\\u%04x", (int)c));
                            } else {
                                sb.append(c);
                            }
                    }
                }
                sb.append("\"");
                return sb.toString();
            }
        });
    }

    private void checkAndRequestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            String[] permissions = {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };

            boolean needRequest = false;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }

            if (needRequest) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted && webView != null) {
                webView.post(() -> webView.loadUrl("javascript:showError('Ошибка', 'Необходимы разрешения для работы с файлами');"));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        fileInterface.handleActivityResult(requestCode, resultCode, data);
    }

    /**
     * loadLocalHtml:
     * - если нет интернета -> assets/index.html
     * - если интернет есть:
     *     - пытаемся прочитать settings.json из getExternalFilesDir(null)
     *     - readFile() может вернуть "wrapper" {"ok":true,"content":"..."} или сам контент файла.
     *     - если в реальном содержимом файла есть поле "link" -> открываем его
     *     - иначе -> assets/page/hello.html
     */
    private void loadLocalHtml() {
        new Thread(() -> {
            boolean online = isOnlineAvailable();
            Log.d("MainActivity", "loadLocalHtml - online = " + online);

            String urlToLoad;

            if (!online) {
                urlToLoad = "file:///android_asset/index.html";
                Log.d("MainActivity", "No internet -> loading asset index.html");
            } else {
                String rawRead = null;
                String fileBody = null;
                String linkFromSettings = null;

                try {
                    Log.d("MainActivity", "Trying to read settings.json from app files (via fileInterface.readFile)");
                    rawRead = fileInterface.readFile("settings.json", "text");
                    Log.d("MainActivity", "raw readFile response: " + (rawRead == null ? "null" : rawRead.length() + " bytes"));
                } catch (Exception e) {
                    Log.e("MainActivity", "Exception while calling readFile", e);
                }

                // Если rawRead содержит JSON-обёртку {ok:true, content: "..."} — извлечём content
                if (rawRead != null) {
                    try {
                        rawRead = rawRead.trim();
                        // Detect wrapper with "ok" and "content"
                        if (rawRead.startsWith("{")) {
                            JSONObject wrapper = new JSONObject(rawRead);
                            if (wrapper.optBoolean("ok", false) && wrapper.has("content")) {
                                fileBody = wrapper.optString("content", null);
                                Log.d("MainActivity", "readFile returned wrapper with content length: " + (fileBody == null ? "null" : fileBody.length()));
                            } else {
                                // Возможно это уже сам settings.json
                                fileBody = rawRead;
                                Log.d("MainActivity", "readFile returned raw JSON (not wrapper)");
                            }
                        } else {
                            // Не JSON — используем как есть
                            fileBody = rawRead;
                            Log.d("MainActivity", "readFile returned non-JSON raw data");
                        }
                    } catch (Exception e) {
                        Log.e("MainActivity", "Error parsing readFile response wrapper, using rawRead as file body", e);
                        fileBody = rawRead;
                    }
                } else {
                    Log.d("MainActivity", "rawRead is null (settings.json not found or read error)");
                }

                // Если у нас есть тело файла — убираем BOM и пробелы, и пробуем распарсить реальный JSON
                if (fileBody != null) {
                    String trimmed = fileBody.trim();
                    if (trimmed.startsWith("\uFEFF")) {
                        trimmed = trimmed.substring(1).trim();
                        Log.d("MainActivity", "Detected and removed BOM from settings content");
                    }
                    try {
                        if (trimmed.startsWith("{")) {
                            JSONObject settingsJson = new JSONObject(trimmed);
                            linkFromSettings = settingsJson.optString("link", null);
                            Log.d("MainActivity", "Parsed settings.json content, link=" + linkFromSettings);
                        } else {
                            Log.d("MainActivity", "File body does not start with '{' after trim -> not JSON");
                        }
                    } catch (Exception e) {
                        Log.e("MainActivity", "Error parsing settings.json content as JSON", e);
                    }
                } else {
                    Log.d("MainActivity", "No file body available from readFile");
                }

                if (linkFromSettings != null && !linkFromSettings.isEmpty()) {
                    urlToLoad = linkFromSettings;
                    Log.d("MainActivity", "Using link from settings.json: " + urlToLoad);
                } else {
                    urlToLoad = "file:///android_asset/page/hello.html";
                    Log.d("MainActivity", "settings.json missing/invalid or no link -> loading asset hello.html");
                }
            }

            final String finalUrlToLoad = urlToLoad;
            runOnUiThread(() -> webView.loadUrl(finalUrlToLoad));
        }).start();
    }

    private void loadLocalHtmlWithFile() {
        fileInterface.prepareFileContent(() -> {
            runOnUiThread(this::loadLocalHtml);
        });
    }

    private boolean isOnlineAvailable() {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                    new java.net.URL("http://clients3.google.com/generate_204").openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            return (200 <= responseCode && responseCode <= 399);
        } catch (Exception exception) {
            return false;
        }
    }
}
