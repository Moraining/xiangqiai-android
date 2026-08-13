# 皮卡鱼象棋 · 安卓版

把 `xiangqiai.com`（皮卡鱼象棋 AI 在线分析）打包成本地安卓 App。
**所有资源内置、完全离线可用，且 wasm 引擎支持多线程 + SIMD。**

## 为什么安卓版能多线程（而 file:// 网页不能）

- 网页 `file://` 双击 → 浏览器不提供 COOP/COEP 响应头 → `crossOriginIsolated=false` → 无 SharedArrayBuffer → 只能单线程
- 本 App 在**手机本机**启动一个带 `Cross-Origin-Embedder-Policy` / `Cross-Origin-Opener-Policy` 响应头的
  HTTP 服务器（NanoHTTPD），WebView 加载 `http://localhost:PORT/index.html`
- `localhost` 是安全上下文（Secure Context）+ COOP/COEP 头 → `crossOriginIsolated=true` →
  **SharedArrayBuffer 可用 → 皮卡鱼引擎跑多线程（multi_simd 变体，线程数 = CPU 核数）**

这正是在线原站（HTTPS + COOP/COEP）能多线程的同一套机制，现在本地 App 也具备了。

## 功能

- ✅ 棋盘、走棋、悔棋、翻转、编辑
- ✅ 皮卡鱼 AI 分析（多线程 + SIMD）
- ✅ 音效、动画、棋谱导入导出
- 🌐 云库查询（需联网，实时查询 chessdb.cn）
- ❌ 棋盘截图识别（原站后端接口）

## 构建方式

### 方式一：Android Studio（本地构建）
1. 安装 Android Studio（Hedgehog 或更新）
2. 打开本目录（`xiangqiai-android/`）作为项目
3. 等待 Gradle 同步（首次会下载 Gradle 8.2 + 依赖，需联网）
4. Build → Build App Bundle(s) / APK(s) → Build APK(s)
5. 产物在 `app/build/outputs/apk/debug/app-debug.apk`

### 方式二：GitHub Actions（在线构建，无需本地环境）
1. 把本目录推送到 GitHub 仓库（保持 `xiangqiai-android/` 为根目录之一）
2. Actions → Build APK → 手动 Run workflow（或 push 自动触发）
3. 构建完成后在 Artifacts 下载 `xiangqiai-apk`，解压得到 APK

> 构建说明：AGP 8.1.4 / Gradle 8.2 / minSdk 26（Android 8.0+）/ targetSdk 34。
> 需要联网下载 NanoHTTPD 依赖（Maven Central）。

## 安装

把 APK 传到手机，点击安装（需允许「安装未知来源应用」）。

## 目录结构

```
xiangqiai-android/
├── settings.gradle / build.gradle / gradle.properties
├── .github/workflows/build.yml   # GitHub Actions 自动构建
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/xiangqiai/app/MainActivity.java   # 服务器 + WebView 核心
        ├── res/                                       # 布局 / 图标 / 主题
        └── assets/www/                                # 网页资源（HTML/JS/引擎/皮肤）
            ├── index.html
            ├── assets/      # Vite 打包的 JS/CSS
            ├── engine/      # 皮卡鱼引擎 5 个变体（single/single_simd/multi/multi_simd/multi_simd_relaxed）
            ├── api/         # 皮肤配置
            └── skins/       # 皮肤图片 + 音效
```

## 备注

- 首页加载需几秒（WebView 解析内置资源），属正常
- 若「设置 → 浏览器环境」显示不支持多线程，通常是 WebView 版本过旧（需 Chromium 92+），
  可在应用商店更新「Android System WebView」
- 引擎数据（pikafish.data 4MB）已内置，无需网络
