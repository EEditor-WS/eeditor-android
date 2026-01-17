package com.eenot.eeditor;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public class FileInterfaceNew {
    private final Context context;
    private final WebView webView;
    private final File baseDir; // getExternalFilesDir(null)

    public FileInterfaceNew(Context ctx, WebView webView) {
        this.context = ctx;
        this.webView = webView;
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) {
            ext = ctx.getFilesDir();
        }
        this.baseDir = ext;
    }

    public void prepareFileContent(Runnable callback) {
        if (callback != null) callback.run();
    }

    public void handleActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        // Заглушка — при необходимости реализуем
    }

    @Nullable
    private File resolveSafe(String relativePath) {
        try {
            if (relativePath == null) relativePath = "";
            relativePath = relativePath.replace('\\', '/');
            while (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

            File result = new File(baseDir, relativePath);
            String canonicalBase = baseDir.getCanonicalPath();
            String canonicalResult = result.getCanonicalPath();

            if (!canonicalResult.startsWith(canonicalBase)) {
                return null;
            }
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    @JavascriptInterface
    public String readFile(String relativePath, String mode) {
        try {
            File f = resolveSafe(relativePath);
            if (f == null) return makeError("Invalid path or access denied");
            if (!f.exists()) return makeError("File not found");
            if (f.isDirectory()) return makeError("Path is a directory");

            byte[] bytes = readAllBytes(f);

            if ("base64".equalsIgnoreCase(mode)) {
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                ok.put("content", b64);
                ok.put("encoding", "base64");
                return ok.toString();
            } else {
                String text = new String(bytes, StandardCharsets.UTF_8);
                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                ok.put("content", text);
                ok.put("encoding", "utf-8");
                return ok.toString();
            }
        } catch (Exception e) {
            return makeError(e.getMessage());
        }
    }

    @JavascriptInterface
    public String writeFile(String relativePath, String content, String mode) {
        try {
            if (relativePath == null) return makeError("Empty path");
            File f = resolveSafe(relativePath);
            if (f == null) return makeError("Invalid path or access denied");

            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                boolean created = parent.mkdirs();
                if (!created && !parent.exists()) {
                    return makeError("Cannot create directories");
                }
            }

            byte[] data;
            if ("base64".equalsIgnoreCase(mode)) {
                data = Base64.decode(content, Base64.DEFAULT);
            } else {
                data = content.getBytes(StandardCharsets.UTF_8);
            }

            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
                out.write(data);
                out.flush();
            }

            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            return ok.toString();
        } catch (Exception e) {
            return makeError(e.getMessage());
        }
    }

    @JavascriptInterface
    public String listFiles(String relativePath) {
        try {
            File target;
            if (relativePath == null || relativePath.length() == 0) {
                target = baseDir;
            } else {
                target = resolveSafe(relativePath);
                if (target == null) return makeError("Invalid path or access denied");
            }

            if (!target.exists()) return makeError("Directory not found");
            if (!target.isDirectory()) return makeError("Path is not a directory");

            File[] files = target.listFiles();
            if (files == null) files = new File[0];

            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            JSONArray arr = new JSONArray();
            String baseCanonical = baseDir.getCanonicalPath();

            for (File f : files) {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                String rel;
                try {
                    String canonical = f.getCanonicalPath();
                    if (canonical.startsWith(baseCanonical)) {
                        rel = canonical.substring(baseCanonical.length());
                        if (rel.startsWith(File.separator)) rel = rel.substring(1);
                    } else {
                        rel = f.getName();
                    }
                } catch (IOException e) {
                    rel = f.getName();
                }
                o.put("relativePath", rel.replace(File.separatorChar, '/'));
                o.put("isDirectory", f.isDirectory());
                o.put("size", f.isDirectory() ? 0 : f.length());
                o.put("modified", f.lastModified());
                arr.put(o);
            }

            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            ok.put("files", arr);
            return ok.toString();
        } catch (Exception e) {
            return makeError(e.getMessage());
        }
    }

    @JavascriptInterface
    public String deleteFile(String relativePath) {
        try {
            File f = resolveSafe(relativePath);
            if (f == null) return makeError("Invalid path or access denied");
            if (!f.exists()) return makeError("File not found");
            boolean deleted = f.delete();
            if (!deleted) return makeError("Cannot delete file");
            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            return ok.toString();
        } catch (Exception e) {
            return makeError(e.getMessage());
        }
    }

    private static String makeError(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg == null ? "null" : msg);
            return o.toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    private static byte[] readAllBytes(File f) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f));
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[8192];
            int r;
            while ((r = in.read(tmp)) != -1) {
                buf.write(tmp, 0, r);
            }
            return buf.toByteArray();
        }
    }

    private String currentFileContent = null;
    private String currentFileName = null;

    public void setCurrentFileUri(Uri uri) {
        try {
            String path = uri.getPath();
            currentFileName = path != null ? new File(path).getName() : "";
            try (java.io.InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    byte[] bytes = readStreamToBytes(is);
                    currentFileContent = new String(bytes, StandardCharsets.UTF_8);
                } else {
                    currentFileContent = null;
                }
            }
        } catch (Exception e) {
            currentFileContent = null;
        }
    }

    public String getCurrentFileContent() {
        return currentFileContent == null ? "" : currentFileContent;
    }

    public String getCurrentFileName() {
        return currentFileName == null ? "" : currentFileName;
    }

    private static byte[] readStreamToBytes(java.io.InputStream is) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(is);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[8192];
            int r;
            while ((r = in.read(tmp)) != -1) {
                buf.write(tmp, 0, r);
            }
            return buf.toByteArray();
        }
    }

    @JavascriptInterface
    public String getBaseDir() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("baseDir", baseDir.getAbsolutePath());
            return o.toString();
        } catch (Exception e) {
            return makeError(e.getMessage());
        }
    }
}
