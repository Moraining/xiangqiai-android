# 皮卡鱼象棋 · 安卓版

把 `xiangqiai.com`（皮卡鱼象棋 AI 在线分析）打包成本地安卓 App。
**所有资源内置、完全离线可用，引擎为 NDK 编译的原生皮卡鱼（真多线程 + SIMD）。**

## 为什么安卓版能真多线程（而 wasm 网页方案不行）

- wasm 多线程引擎依赖 SharedArrayBuffer，它要求 `crossOriginIsolated=true`
- `crossOriginIsolated` 需要 COOP + COEP 响应头同时生效；**Android WebView 会忽略
  `Cross-Origin-Opener-Policy` 头**（WebView 没有浏览器标签页/opener 机制），所以 WebView 里
  wasm 多线程永远跑不起来——手机浏览器（Chrome）可以，App 内嵌 WebView 不行
- **本 App 的解法**：不依赖 COOP，直接把皮卡鱼 C++ 用 NDK 交叉编译成 **arm64 原生库（`.so`）**，
  通过 **JNI（`System.loadLibrary`）加载**——这是 Android 官方保证的 native 执行路径，不受
  "app 目录禁止 exec 二进制" 的限制。原生引擎用 pthread 自动吃满手机全部 CPU 核心，是**真多线程 + SIMD**
- 网页（原站 UI）与原生引擎之间通过一层轻量桥通信：JS → `addJavascriptInterface` →
  JNI 命令队列 → 引擎；引擎输出 → JNI 回调 → `evaluateJavascript` 推回页面。界面与原站完全一致

> 内置网页版 wasm 引擎（single_simd）仍保留：当设备不是 arm64、或原生二进制启动失败时，
> 自动回退到 wasm 单线程引擎，功能不受影响。

## 功能

- ✅ 棋盘、走棋、悔棋、翻转、编辑
- ✅ 皮卡鱼 AI 分析（原生多线程 + SIMD，线程数 = CPU 核数）
- ✅ 音效、动画、棋谱导入导出
- 🌐 云库查询（需联网，实时查询 chessdb.cn）
- ❌ 棋盘截图识别（原站后端接口）

## 构建方式

### 方式一：Android Studio（本地构建）
1. 安装 Android Studio（Hedgehog 或更新）与 Android NDK
2. 把 `app/src/main/jniLibs/arm64-v8a/libpikafishjni.so`、`app/src/main/assets/native/pikafish.nnue`
   放入对应目录（可从 GitHub Actions 构建产物中获取，或自行按 CI 流程编译）
3. 打开本目录（`xiangqiai-android/`）作为项目，等待 Gradle 同步
4. Build → Build APK(s)，产物在 `app/build/outputs/apk/debug/app-debug.apk`

### 方式二：GitHub Actions（在线构建，无需本地环境，推荐）
1. 把本目录推送到 GitHub 仓库
2. Actions → Build APK → 自动触发（push）或手动 Run workflow
3. Actions 会自动：安装 NDK → 克隆官方皮卡鱼源码编译原生引擎 → 打包进 APK
4. 构建完成后在 Artifacts 下载 `xiangqiai-apk`，解压得到 APK

> 构建说明：AGP 8.1.4 / Gradle 8.5 / minSdk 26（Android 8.0+）/ targetSdk 34。
> 需要联网下载 NanoHTTPD 依赖与皮卡鱼源码（GitHub）。

## 安装

把 APK 传到手机，点击安装（需允许「安装未知来源应用」）。
> APK 包含原生引擎与神经网络文件，体积约 50-100MB，属正常。

## 目录结构

```
xiangqiai-android/
├── settings.gradle / build.gradle / gradle.properties
├── .github/workflows/build.yml   # GitHub Actions 自动构建（NDK 编译 + 打包）
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/xiangqiai/app/MainActivity.java   # 服务器 + WebView + JNI 引擎桥
        ├── cpp/wrapper.cpp                            # 皮卡鱼 UCI 循环 → JNI 包装
        ├── res/                                       # 布局 / 图标 / 主题
        └── assets/
            ├── native/pikafish.nnue                   # 神经网络权重（Actions 编译时下载）
            └── www/                                   # 网页资源（HTML/JS/wasm 引擎/皮肤）
                ├── index.html
                ├── assets/      # Vite 打包的 JS/CSS
                ├── engine/      # 皮卡鱼 wasm 变体（回退用 single_simd）
                ├── api/         # 皮肤配置
                └── skins/       # 皮肤图片 + 音效
```

## 备注

- 首页加载需几秒（WebView 解析内置资源 + 原生引擎启动），属正常
- 「设置 → 浏览器环境」显示**多线程: 支持** = 原生引擎生效；若显示不支持 = 已回退 wasm 单线程
- 若想了解原生引擎进度，可用 `adb logcat -s XQWEB` 查看日志
