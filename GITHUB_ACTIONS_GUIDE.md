# 用 GitHub Actions 免费在线构建 APK —— 超详细步骤

不需要安装 Android Studio、不需要写命令（主流程用图形化的 GitHub Desktop）。
全程在浏览器和 GitHub Desktop 里点鼠标完成，然后由 GitHub 的服务器自动帮你构建 APK。

---

## 需要准备的东西

1. 一个 **GitHub 账号**（免费）
2. 电脑上**解压好的工程文件夹**：`xiangqiai-android`

---

## 第 0 步：解压工程

1. 找到 `xiangqiai-android.zip`，右键 → **解压到当前文件夹**
2. 得到文件夹 `xiangqiai-android`，点进去确认能看到：
   - `app`（文件夹）
   - `settings.gradle`、`build.gradle`、`gradle.properties`（文件）
   - `.github`（**隐藏文件夹**，如果看不到：文件资源管理器 → 查看 → 勾选「隐藏的项目」）

---

## 第 1 步：注册 / 登录 GitHub

1. 浏览器打开 https://github.com
2. 没账号点 **Sign up** 注册（按提示填邮箱、密码、用户名），有账号直接 **Sign in** 登录

---

## 第 2 步：创建一个空的 GitHub 仓库

1. 登录后，点右上角头像旁边的 **「+」** → **New repository**
2. **Repository name** 填：`xiangqiai-android`
3. **Public / Private**：随便选（Private 也行，构建照常工作）
4. **不要勾选** "Add a README file"（避免和稍后上传的文件冲突）
5. 点 **Create repository**
6. 创建后进入一个空仓库页面（会显示一些 `git init` 命令，**不用管它**），保持这个页面即可

---

## 第 3 步：安装并登录 GitHub Desktop

1. 浏览器打开 https://desktop.github.com → 下载并安装 **GitHub Desktop**
2. 打开 GitHub Desktop → 菜单 **File → Options**（Mac 是 **GitHub Desktop → Preferences**）→ **Accounts** → 用你的 GitHub 账号登录

---

## 第 4 步：把工程文件夹推送到 GitHub

1. GitHub Desktop → 菜单 **File → Add local repository...**
2. 点 **Choose...** → 选中第 0 步解压出来的 `xiangqiai-android` 文件夹
3. 点 **Add Repository**
   - 如果提示「该文件夹还不是 Git 仓库」，选 **create a repository**（或先 `File → New repository` 选这个路径，再点 Add）
4. 文件列表出现后，在左下角「**Summary**」填：`initial` → 点 **Commit to main**
5. 点窗口顶部中间的 **Publish repository**
   - 仓库名默认 `xiangqiai-android`，**Description** 可留空
   - 点 **Publish repository**
6. 等待右下角提示「推送到 GitHub」完成

> **不用 GitHub Desktop 的替代方案（Git 命令行）**
> 先安装 Git（https://git-scm.com），然后在 `xiangqiai-android` 文件夹里打开命令行执行：
> ```
> git init
> git add .
> git commit -m "initial"
> git branch -M main
> git remote add origin https://github.com/你的用户名/xiangqiai-android.git
> git push -u origin main
> ```
> 最后一步会弹窗要 GitHub 用户名和密码（密码处填 **Personal access token**，在 GitHub → Settings → Developer settings → Personal access tokens 里生成，勾选 repo 权限）。

---

## 第 5 步：触发自动构建

1. 推送完成后，回到浏览器刷新你的仓库页面（`github.com/你的用户名/xiangqiai-android`）
2. 点页面顶部的 **Actions** 标签
3. 应该能看到 **Build APK** 这个 workflow **正在运行**（黄色圆点）
4. 等 **3~5 分钟**（首次要下载 Gradle 和依赖），变**绿色** = 构建成功

> 如果没自动触发：在 Actions 页 → 左边选 **Build APK** → 右侧 **Run workflow** → 绿色按钮 → **Run workflow**（手动触发一次）。

---

## 第 6 步：下载 APK

1. Actions 页面变绿后，点进 **Build APK** 那条运行记录
2. 页面**底部**有 **Artifacts** → 点 **xiangqiai-apk** 下载（是个 zip）
3. 解压得到 **app-release.apk**

---

## 第 7 步：安装到手机

1. 把 `app-release.apk` 传到手机（微信/QQ 发送给自己、数据线、网盘都行）
2. 在手机文件管理器里点击 APK 安装
3. 提示「未知来源应用」时允许：
   - 小米/红米：设置 → 更多设置 → 特殊权限设置 → 安装未知应用 → 允许「文件管理」
   - 华为/荣耀：设置 → 安全 → 更多安全设置 → 安装外部来源应用 → 允许
   - 其他安卓：安装时会弹窗，点「设置」→ 允许 → 返回再装
4. 装好打开「皮卡鱼象棋」

---

## 常见问题

| 现象 | 解决 |
|---|---|
| 构建变红（失败） | 点进失败记录 → 看日志，把红色报错内容复制发给我 |
| 页面没有 Actions 标签 | 等 1 分钟刷新；或仓库 Settings → Actions → General → 勾选 Allow all actions |
| 下载的 APK 装不上 | 手机需 Android 8.0 及以上；确认未重复安装过签名不同的旧包 |
| 安装后「设置 → 浏览器环境」显示不支持多线程 | 更新「Android System WebView」后再试 |
| 上传时提示文件过大 | 本工程最大文件 4MB，正常不会触发；确认没有把 zip 本体也传上去 |
