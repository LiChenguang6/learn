# Klei Gift Watcher（Android 原生项目）

**这是一个可编译的 Android 项目源码包，不是已经验证运行的 APK。**

面向《饥荒联机版》Klei Creator Link 礼物：检查指定 B站 UP 主发布的动态，提取科雷礼物链接，并在手机端**尽力自动执行领取**。无需 Docker、服务器、外部机器人或上传账号密码。

## 功能

- 默认监控 UP 主 UID：`12875864`，可编辑。
- 自定义间隔 **30～3600 秒**，默认 60 秒；推荐 60 秒或更长（B站接口可能限流）。
- 前台服务轮询，在通知栏显示“监控运行中”；可停止。
- 解析 B站动态 JSON 及近期部分图文正文，匹配：
  - `https://accounts.klei.com/creatorlink/...`
  - `https://accounts.klei.com/link/...`（常规科雷奖励链接）
- 新链接去重、保存本机记录、发送通知。
- App 内 WebView 登录科雷网站或 B站；Cookie 只保存在手机应用 WebView 存储中。
- 检测到新链接后可关闭自动领取，仅通知；开启时尝试用已登录会话打开链接并点击标准领取按钮。
- 可粘贴某个链接进行手动测试；支持从记录中再次尝试或进入页面手动领取。

## 构建 APK（推荐：GitHub Actions，无需自己安装 Android SDK）

1. 新建一个 GitHub 仓库，上传这个压缩包**解压后的所有文件和目录**，确保仓库根目录能看到 `settings.gradle.kts` 和 `.github/workflows/build-apk.yml`。
2. 在仓库 `Actions` 页启用工作流 `Build Android APK`，点击 `Run workflow`；推送到 `main` 也会触发。
3. 等构建完成后，在该工作流运行页面的 `Artifacts` 下载 `KleiGiftWatcher-debug-apk`。
4. 解压 Artifact，得到 `app-debug.apk`；复制到 Android 手机安装（允许对应来源安装未知应用）。
5. GitHub Actions 用 Java 17、Gradle 8.9、Android Gradle Plugin 8.7.3 编译。

也可以用 Android Studio 打开根目录，配置 JDK 17 和 Gradle 8.9 后执行 `:app:assembleDebug`。当前源码包不包含 Gradle Wrapper 的二进制 JAR，建议优先按上面的 GitHub Actions 方法构建。

## 使用顺序

1. 首先点击 **登录科雷账号**。在嵌入的 Klei 页面选择 Steam 或其它平台，完成登录，**核对是否为打算领取奖励的科雷账号**。
2. 如果 B站 API 报 `-352`、`412` 或需要登录，可点击 **登录 B站（可选）**，登录后返回 App；接口可能依然受风控。
3. 设置 UP 主 UID、轮询秒数；勾选“自动尝试领取”。
4. 点击 **保存并启动监控**。
5. 在“领取记录”中检查执行状态：
   - `发现新链接`：已检测，尚未确认领取。
   - `已点击领取按钮，等待网页反馈`：已尝试网页交互。
   - `网页显示已领取（请核实账号）`：页面出现成功提示，但不是官方服务器的可验证回执。
   - `需要在 App 内重新登录` / `未找到领取按钮`：请打开链接手动处理。
6. 可以先粘贴一个之前已经领过或无效的 Klei 链接，验证登录与跳转流程，不建议直接用仅有一次机会的稀有礼物进行首次测试。

## 自动领取能力边界（请务必阅读）

- Klei 官方 Creator Link 页面可能要求登录、验证码或人工确认。**本程序并没有 Klei 官方授权的领取 API；采用 WebView 网页自动化，属于实验性功能。** 未找到按钮、页面结构变更、网页登录失效时不能保证领取。
- 手机上的离屏 WebView 在不同厂商设备上运行行为不完全相同。锁屏、省电策略、断网或进程终止均可能阻止自动操作。不要把它当作稳定的 24×7 服务。
- `已点击`、`已尝试` **不等于成功**；即使页面含有成功字样，仍应登录科雷网页核对奖励账户。Klei 明确提示奖励可能无法迁移到另一个账号。
- Steam 等平台可能禁止 WebView 内登录。如果登录环节被拒绝，不能通过外部浏览器登录来直接共享 App 内 WebView Cookie；请用 App 的手动查看链接功能或改用浏览器人工领取。
- Android 14+ 要求指定前台服务类型。本项目 `targetSdk=34` 是私人侧载调试版配置；**不代表绕过系统后台限制**，后续 Android 版本或应用商店政策可能要求适配。后台长时间持续轮询无法保证。
- 出于 B站接口限流和手机耗电考虑，最低间隔设置为 **30 秒**，不会自动无限重试失败领取，发现同一链接只自动处理一次；可以手动点“重新尝试”。
- B站动态接口为社区文档记录的网页接口，可能变更或加风控；图文的部分内容可能无法通过当前解析器发现。检测失败会写进界面状态，不会假装成功。
- `https://accounts.klei.com/creatorlink/` 为 Klei 官方 Creator Links 格式：<https://support.klei.com/hc/en-us/articles/48344703634324-Creator-Links>。

## 隐私、安全与发布

- 不要在 GitHub 仓库添加 Klei Cookie、SESSDATA、密钥、验证码或密码。
- 程序没有远程数据采集服务；网络请求仅发往 B站动态 API、科雷网页及其登录平台。历史记录在 Android SharedPreferences，WebView Cookie 由 Android WebView 管理。
- 本项目只允许自动打开 `https://accounts.klei.com/creatorlink/...` 或 `/link/...`，**不是通用的任意网址执行器**。
- Debug APK 使用调试签名，适合自行安装测试，不适合未经评估直接公开上架或大规模发布。

## 项目说明

纯原生 Kotlin + Android SDK，不需要第三方库；UI 使用程序化原生控件。

```
app/src/main/java/com/example/kleigiftwatcher/
├── MainActivity.kt       # 主界面、配置、状态与领取记录
├── BrowserActivity.kt    # 登录 B站/Klei、人工领取
├── MonitorService.kt     # 前台轮询、通知、去重
├── BiliScanner.kt        # B站动态接口、链接提取
├── AutoRedeemer.kt       # Klei WebView 尝试自动点击
└── Store.kt              # 设置、本地记录、域名校验
.github/workflows/build-apk.yml  # GitHub 云构建 APK
```

**验证状态**：已做源码及目录静态检查，但当前编写环境没有 Android SDK 与 Gradle，因此**未能实际编译 APK，也未在真实科雷 Creator Link 页面验证按钮行为**。先使用测试链接验证，再根据日志迭代识别逻辑。
