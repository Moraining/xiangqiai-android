package com.xiangqiai.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import org.json.JSONObject;

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

    /** 原生皮卡鱼引擎（NDK 编译，真多线程，不依赖 COOP/SharedArrayBuffer） */
    private Process engineProcess;
    private BufferedWriter engineIn;
    private volatile boolean nativeReady = false;

    /** 注入到页面的桥层 JS：包装引擎对象 + stdout 转发 + 可用性标志 */
    private static final String BRIDGE_JS =
            "window.__nativeStdout=function(l){try{if(window.__nativeEngine&&window.__nativeEngine.onReceiveOutput)window.__nativeEngine.onReceiveOutput(l)}catch(e){}};" +
            "window.__nativeReady=function(v){try{window.__NATIVE_READY__=!!v}catch(e){}};" +
            "window.__nativeExit=function(c){try{window.__NATIVE_READY__=false;if(window.__nativeEngine&&window.__nativeEngine.onExit)window.__nativeEngine.onExit(parseInt(c)||0)}catch(e){}};" +
            // terminate 为空操作：保留 native 进程，MainView 重建引擎时继续复用（UCI 协议支持重新初始化）；
            // 真正杀进程由 onDestroy 完成。若这里真的杀掉进程，重建时 __NATIVE_READY__ 仍为 true，
            // 会导致向已死进程发命令而引擎假死。
            "window.NativeEngineBridge=function(){this.WasmType='multi_simd';this.sendCommand=function(c){try{window.NativeEngine.sendCommand(String(c))}catch(e){}};this.terminate=function(){}};";

    /** 诊断浮层：捕获页面 JS 错误，出错时在底部显示，方便截图反馈 */
    private static final String DIAG_JS =
            "(function(){try{if(window.__DBG_INSTALLED)return;window.__DBG_INSTALLED=1;" +
            "var dbg=window.__DBG=[];" +
            "var push=function(s){dbg.push(new Date().toTimeString().slice(0,8)+' '+s);if(dbg.length>60)dbg.shift();};" +
            "window.addEventListener('error',function(e){push('ERR: '+(e.message||e.type));});" +
            "window.addEventListener('unhandledrejection',function(e){var r=e.reason;push('REJ: '+((r&&r.message)||r));});" +
            "var oe=console.error;console.error=function(){var a=[];for(var i=0;i<arguments.length;i++){try{a.push(typeof arguments[i]==='string'?arguments[i]:JSON.stringify(arguments[i]))}catch(x){a.push(String(arguments[i]))}}push('CE: '+a.join(' '));if(oe)oe.apply(console,arguments);};" +
            "var d=document.createElement('div');" +
            "d.id='__dbg_float';" +
            "d.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:999999;background:rgba(0,0,0,0.82);color:#0f0;font:11px/1.4 monospace;padding:6px;max-height:35vh;overflow:auto;display:none;white-space:pre-wrap;';" +
            "document.body.appendChild(d);" +
            "setInterval(function(){if(dbg.length){d.textContent=dbg.slice(-40).join('\\n');d.style.display='block';}},1000);" +
            "}catch(e){}})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 关键：让 <meta viewport> 生效，页面按设备宽度正确缩放（否则棋盘在手机上显示异常）
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 诊断：把 JS 的 console/错误打到 logcat（adb logcat -s XQWEB），并在页面上显示错误浮层
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.i("XQWEB", cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 注入全局错误捕获：出错时底部弹出一个绿色错误浮层，直接截图即可反馈
                view.evaluateJavascript(DIAG_JS, null);
                // 注入原生引擎桥层 + 可用性标志（MainView 懒加载在其后执行，顺序安全）
                view.evaluateJavascript(BRIDGE_JS + "window.__NATIVE_READY__="
                        + (nativeReady ? "true" : "false") + ";", null);
            }
        });

        // 原生引擎 JS 桥：必须在 loadUrl 之前注册
        startNativeEngine();
        webView.addJavascriptInterface(new NativeEngine(), "NativeEngine");

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
        stopEngine();
        if (server != null) server.stop();
    }

    // ===================== 原生引擎（NDK 编译皮卡鱼，真多线程） =====================

    /** 启动原生引擎子进程；失败时 nativeReady=false，页面自动回退 wasm 单线程引擎 */
    private void startNativeEngine() {
        try {
            File dir = getFilesDir();
            File bin = new File(dir, "pikafish-arm64");
            File nnue = new File(dir, "pikafish.nnue");
            copyAsset("native/pikafish-arm64", bin);
            copyAsset("native/pikafish.nnue", nnue);
            if (!bin.setExecutable(true)) {
                Log.w("XQWEB", "setExecutable failed, fallback to wasm");
                return;
            }
            // 工作目录设为 filesDir，引擎按默认 EvalFile "pikafish.nnue" 即可命中
            engineProcess = new ProcessBuilder(bin.getAbsolutePath())
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
            engineIn = new BufferedWriter(new OutputStreamWriter(engineProcess.getOutputStream()));
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(engineProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        final String out = line;
                        webView.post(() -> pushToPage("__nativeStdout", out));
                    }
                } catch (IOException ignored) {
                }
                // 进程退出：通知页面回退，并触发 MainView 的 onExit
                webView.post(() -> {
                    pushToPage("__nativeReady", "false");
                    pushToPage("__nativeExit", "0");
                });
            }, "pikafish-stdout");
            t.setDaemon(true);
            t.start();
            nativeReady = true;
            Log.i("XQWEB", "native engine started");
        } catch (Exception e) {
            Log.e("XQWEB", "native engine start failed", e);
            stopEngine();
        }
    }

    private void copyAsset(String assetPath, File dst) throws IOException {
        try (InputStream is = getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }

    /** 写一条 UCI 命令到引擎 stdin */
    private void nativeSend(String cmd) {
        if (engineIn != null) {
            try {
                engineIn.write(cmd);
                engineIn.write("\n");
                engineIn.flush();
            } catch (IOException e) {
                Log.w("XQWEB", "stdin write failed", e);
            }
        }
    }

    private void stopEngine() {
        if (engineProcess != null) {
            try {
                engineProcess.destroy();
            } catch (Exception ignored) {
            }
            engineProcess = null;
        }
        engineIn = null;
        nativeReady = false;
    }

    /** 把一行引擎输出以 JSON 字符串安全地推给页面 JS */
    private void pushToPage(String fn, String data) {
        String js = "try{" + fn + "(" + JSONObject.quote(data) + ");}catch(e){}";
        webView.evaluateJavascript(js, null);
    }

    /** JS 桥对象：页面里 window.NativeEngine.sendCommand(cmd) / terminate() */
    private class NativeEngine {
        @JavascriptInterface
        public void sendCommand(String cmd) {
            nativeSend(cmd);
        }

        @JavascriptInterface
        public void terminate() {
            stopEngine();
        }
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
                // 关键：引擎通过 data: URL 创建的 Worker（opaque origin）加载 pikafish.data / .wasm，
                // 属于跨域 CORS 请求，必须放行（原站 nginx 也带这个头，缺了引擎会卡在 0.0%）
                res.addHeader("Access-Control-Allow-Origin", "*");
                res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                res.addHeader("Access-Control-Allow-Headers", "*");
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
