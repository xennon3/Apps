package app.sketchbench;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bar = night ? 0xFF121618 : 0xFFDDE2DF;
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);
        if (!night) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        web.setBackgroundColor(bar);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "SketchbenchApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // Serve the Google Fonts the page asks for from the bundled copies, so it looks the same offline.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                try {
                    String file = null, mime = null;
                    if (host.equals("fonts.googleapis.com")) { file = "fonts/fonts.css"; mime = "text/css"; }
                    else if (host.equals("fonts.gstatic.com") && u.getLastPathSegment() != null) { file = "fonts/" + u.getLastPathSegment(); mime = "font/woff2"; }
                    if (file != null) {
                        InputStream in = getAssets().open(file);
                        WebResourceResponse res = new WebResourceResponse(mime, "utf-8", in);
                        Map<String, String> h = new HashMap<>();
                        h.put("Access-Control-Allow-Origin", "*");
                        res.setResponseHeaders(h);
                        return res;
                    }
                } catch (IOException ignored) { }
                return null;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }

    private Uri insertPng(String base64, String name) throws IOException {
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sketchbench");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("insert failed");
        try (OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(data); }
        return uri;
    }

    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }

    private class Bridge {
        @JavascriptInterface
        public boolean savePng(String base64, String name) {
            try { insertPng(base64, name); return true; } catch (Exception e) { toast("Could not save the image"); return false; }
        }

        @JavascriptInterface
        public boolean sharePng(String base64, String name) {
            try {
                Uri uri = insertPng(base64, name);
                Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Share sketch")));
                return true;
            } catch (Exception e) { toast("Could not share the image"); return false; }
        }
    }
}
