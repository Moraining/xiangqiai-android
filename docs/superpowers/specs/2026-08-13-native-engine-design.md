# 原生皮卡鱼引擎（Native Pikafish Engine）设计文档

日期：2026-08-13
状态：已确认（用户批准）
关联工程：xiangqiai-android（Android App：WebView + NanoHTTPD 本地服务器 + 皮卡鱼 wasm 引擎）

## 1. 背景与问题

App 用 WebView 加载原网站（xiangqiai.com）的 Vue SPA。wasm 多线程引擎依赖 SharedArrayBuffer，
而 SharedArrayBuffer 需要 `crossOriginIsolated=true`，它要求 COOP + COEP 响应头同时生效。
**Android WebView 忽略 `Cross-Origin-Opener-Policy` 头**（WebView 没有浏览器标签页/opener 模型），
导致 `crossOriginIsolated=false`，wasm 多线程引擎（multi/multi_simd 变体）无法运行。

当前状态：单线程 SIMD wasm 引擎（single_simd）已验证可用。目标：实现真正的多线程，且不依赖 COOP 头。

## 2. 目标

1. App 内引擎以**真多线程**运行（吃满手机所有 CPU 核心），不依赖 COOP/COEP/SharedArrayBuffer。
2. 保持原网站 UI 与交互逻辑不变（仅加一层桥）。
3. 失败时自动回退到现有 wasm 单线程引擎，不阻塞使用。

## 3. 方案概述

把皮卡鱼 C++ 源码用 NDK 交叉编译成**原生可执行文件**，App 用子进程管道运行它，
网页通过 `addJavascriptInterface` + 注入 JS 控制。

**为什么可行**：皮卡鱼官方 Makefile 原生支持 `COMP=ndk`（`OS := Android`，编译器
`aarch64-linux-android29-clang++`）。UCI 引擎本质是"stdin 指令 → stdout 输出"的文本协议，
`ProcessBuilder` + 管道天然匹配，无需修改引擎源码。

## 4. 架构

```
WebView（http://localhost）UI（原站 MainView.js 等，原样保留）
  ├─ NativeEngineBridge（注入 JS）：sendCommand(cmd) → NativeEngine.sendCommand
  ├─ window.__nativeStdout(line) → MainView.onReceiveOutput(line)
  └─ 标志 __NATIVE_READY__（native 可用性）
        │ addJavascriptInterface
        ▼
MainActivity（Java）
  ├─ NativeEngine（JS 桥对象）
  │    ├ sendCommand(cmd)  → 写引擎进程 stdin
  │    ├ terminate()       → 终止子进程
  │    └ stdout 读线程     → 逐行 → webView.post → evaluateJavascript("__nativeStdout(...)")
  └─ pikafish 子进程（ProcessBuilder，从 assets/native 拷贝到 filesDir，chmod +x）
       ├ 多线程：皮卡鱼 native 自动用满核心（UCI Threads 默认=核数）
       └ NNUE：编译嵌入或随包提供
```

## 5. 组件与职责

| # | 组件 | 文件 | 职责 |
|---|---|---|---|
| 1 | NDK 编译产物 | `app/src/main/assets/native/pikafish-arm64` | GitHub Actions 编译产出；arm64-v8a 可执行文件 |
| 2 | 进程桥 | `MainActivity.java` 内 `NativeEngine` | 拷贝二进制、exec、stdin/stdout 线程、生命周期管理 |
| 3 | JS 桥 | `MainActivity.java` 内 `addJavascriptInterface` | 暴露 `NativeEngine.sendCommand/terminate` |
| 4 | JS 注入层 | `onPageFinished` 注入 JS 常量 | 包装引擎对象、stdout 转发、`__NATIVE_READY__` |
| 5 | MainView patch | `assets/www/assets/MainView.*.js` | `initEngine()` 开头插入 native 分支（3 行 + 辅助对象） |

### 5.1 MainView.js patch 细节

在 `initEngine:()=>{this.Ready=!1;let e=this.WasmType;` 之后插入：

```js
if(window.__NATIVE_READY__){this.Mode="multi";this.WasmType="multi_simd";window.__nativeEngine=this;
  this.Engine=new window.__NativeEngineBridge();this.Ready=!0;
  setTimeout(()=>{this.sendCommand("uci"),this.setOptionList(this.EngineOptions)},100);return}
```

- 桥引擎对象接口（对齐 multi 分支 emscripten 对象）：`sendCommand(cmd)`、`terminate()`、
  属性 `WasmType="multi_simd"`（设置页显示"多线程:支持"、hash=512）。
- `window.__nativeStdout(line)` 转发到 `window.__nativeEngine.onReceiveOutput(line)`。
- `onExit` 由 MainActivity 检测进程退出后调用 `window.__nativeEngine.onExit(code)`（容错 try/catch）。

## 6. 数据流

```
MainView.sendCommand("position startpos ... moves ...")
  → NativeEngineBridge.sendCommand → Java NativeEngine.sendCommand → 引擎进程 stdin
引擎搜索（多线程）→ stdout: "info depth 18 ..." / "bestmove ..."
Java stdout 线程逐行读取 → webView.post(Runnable)
  → evaluateJavascript("window.__nativeStdout(<JSON 转义的行>)")
  → MainView.onReceiveOutput(line) → 棋盘/评估条/PV 更新
```

关键点：stdout 行须 JSON 序列化后再嵌入 JS 字符串（防引号/换行/Unicode 破坏）。

## 7. 错误处理与回退

1. **ABI 不支持**（手机非 arm64，或 assets 无二进制）→ 不注入 `__NATIVE_READY__` → 走原 wasm 单线程。
2. **二进制启动失败**（IOException / exec 被拒）→ 同上回退。
3. **引擎进程崩溃/退出** → stdout 读线程 EOF → `terminate()` 清理 → 回调 `onExit` → MainView 按原逻辑重建引擎（若 native 已死则回退 wasm）。
4. **App 生命周期** → `onDestroy()` 调 `terminate()`。
5. **引擎启动后首次 `uci` 指令超时**（NNUE 初始化慢）→ 保持现有 MainView 的 `Ready` 流程（收到 `uciok` 后置 Ready），不做额外超时逻辑。

## 8. GitHub Actions 构建

改造 `.github/workflows/build.yml`：

```yaml
- uses: android-ndk/ndk-download@v1        # 安装 NDK r25c（或 setup-android 动作）
- run: |
    export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
    git clone --depth 1 https://github.com/official-pikafish/Pikafish.git /tmp/pikafish
    cd /tmp/pikafish/src && make build COMP=ndk ARCH=armv8-dotprod
    cp pikafish "$GITHUB_WORKSPACE/app/src/main/assets/native/pikafish-arm64"
    chmod +x "$GITHUB_WORKSPACE/app/src/main/assets/native/pikafish-arm64"
- run: gradle assembleDebug --no-daemon
```

- 若 `armv8-dotprod` 编译失败，回退 `ARCH=armv8`（不带 dotprod 的 SIMD）。
- 产物命名 `pikafish-arm64`；`build.gradle` 无特殊配置（assets 自动打包）。

## 9. 验证标准

1. App 设置页「浏览器环境」显示 **多线程: 支持**、**SIMD: 支持**，不再弹"不支持多线程"提示。
2. 开局后引擎输出 `info` 的 NPS 明显高于单线程（数倍），手机 CPU 多核占用。
3. 原生二进制缺失或启动失败的设备：自动回退 wasm 单线程，功能正常。

## 10. 非目标

- 不修改引擎搜索逻辑/网络权重。
- 不做 32 位（armeabi-v7a）二进制（现代手机均为 arm64；旧设备走 wasm 回退）。如用户需要可后续补。
- 不做多实例 Worker 并行（皮卡鱼无协同搜索协议，无性能收益）。
- 不替换原站 UI。

## 11. 风险与缓解

| 风险 | 缓解 |
|---|---|
| NDK 编译参数需调（dotprod 不支持） | 先试 armv8-dotprod，失败回退 armv8；Actions 可反复试 |
| NNUE 网络文件缺失导致引擎启动报错 | 检查皮卡鱼二进制是否内嵌网络；若需外部文件，随 assets 提供并在启动时传路径 |
| 个别设备 SELinux 限制 exec | 罕见；启动失败自动回退 wasm |
| MainView.js patch 破坏压缩代码 | 采用最小插入（在 `initEngine:` 后插），复用单文件版 patch 的验证手段 |
