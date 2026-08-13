package com.xiangqiai.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * 皮卡鱼象棋 · 安卓版
 *
 * 原理：在手机本机启动一个带 COOP/COEP 响应头的 HTTP 服务器（NanoHTTPD），
 * WebView 加载 http://localhost:PORT/index.html。
 * - localhost 是安全上下文（Secure Context）
 * - COOP/COEP 头开启 crossOriginIsolated → SharedArrayBuffer 可用
 * → 皮卡鱼 wasm 引擎可运行多线程（multi_simd 变体），突破 file:// 下只能单线程的限制。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private AppServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());

        try {
            server = new AppServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            int port = server.getListeningPort();
            webView.loadUrl("http://localhost:" + port + "/index.html");
        } catch (IOException e) {
            Toast.makeText(this, "本地服务器启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
    }

    /** 内置 HTTP 服务器：从 assets/www 提供页面，所有响应带 COOP/COEP 头 */
    private class AppServer extends NanoHTTPD {

        AppServer() { super(0); } // 端口 0 = 自动分配空闲端口

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if (uri == null || uri.equals("/")) uri = "/index.html";
            try {
                InputStream is = getAssets().open("www" + uri);
                byte[] data = readAll(is);
                Response res = newFixedLengthResponse(
                        Response.Status.OK, getMime(uri), new ByteArrayInputStream(data), data.length);
                // 关键：开启 crossOriginIsolated → SharedArrayBuffer → wasm 多线程
                res.addHeader("Cross-Origin-Embedder-Policy", "require-corp");
                res.addHeader("Cross-Origin-Opener-Policy", "same-origin");
                res.addHeader("Cross-Origin-Resource-Policy", "cross-origin");
                res.addHeader("Cache-Control", "no-store");
                return res;
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: " + uri);
            }
        }
    }

    private byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private String getMime(String uri) {
        if (uri.endsWith(".html")) return "text/html";
        if (uri.endsWith(".js")) return "text/javascript";
        if (uri.endsWith(".css")) return "text/css";
        if (uri.endsWith(".json")) return "application/json";
        if (uri.endsWith(".wasm")) return "application/wasm";
        if (uri.endsWith(".webp")) return "image/webp";
        if (uri.endsWith(".png")) return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        if (uri.endsWith(".ico")) return "image/x-icon";
        if (uri.endsWith(".mp3")) return "audio/mpeg";
        if (uri.endsWith(".webmanifest")) return "application/manifest+json";
        if (uri.endsWith(".data")) return "application/octet-stream";
        if (uri.endsWith(".svg")) return "image/svg+xml";
        if (uri.endsWith(".woff") || uri.endsWith(".woff2")) return "font/woff2";
        if (uri.endsWith(".ttf")) return "font/ttf";
        if (uri.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
}
