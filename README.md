# 旧时光相机 · Old Time Camera

一个克制、优雅的 Android 纯本地胶片相机（`v1.8.0`）：**一块无遮挡光学取景窗、十卷胶片、一颗机械快门**。
实时预览与全分辨率成片共用同一套 OpenGL ES 片元着色器，拍摄或导入洗印后叠上相纸边框、编号与复古橙色日期背印，写入系统相册并保留相机拍摄 EXIF 元数据。

默认全离线运行、无账号、无统计/广告 SDK。网络访问**仅在用户主动点击**时发生：暗房面板里的「检查更新」（GitHub Releases API）与可选的 **BYOK AI 摄影导师**（支持自带 DeepSeek `deepseek-flash` / 通义千问 VL / OpenAI / 自定义兼容接口 API Key，经 `AndroidKeyStore` 加密保存在本机，绝不后台扫描或偷跑流量）。

---

## 1. 功能亮点

- **胶片暖调暗房精修**：
  - 哑光碳黑机身（`#11100E`）+ 暖象牙纸白（`#F0E8D8`）+ 琥珀指示灯（`#F2A65A`）+ 陶土橙点缀（`#D25A34`），层级文字排版、按压水波纹反馈与 Edge-to-Edge 安全区适配。
  - 统一接入开源线性图标族 **Phosphor Icons**（Regular，MIT）与内置等宽字体 **JetBrains Mono Regular**（OFL 1.1），确保帧数计数、EV、焦段与底片页码具备一致的光学器械精密感。
  - 取景器与底栏分区明晰，切换画幅时仿旁轴相机的遮幅帘幕平滑收合；支持点击或长按取景框右下角橙色 LED 日期直接切换/隐藏日期印记。
- **克制的构图与拍摄辅助**：
  - **实时移动引导（画框边缘极简符号，零遮挡主体）**：结合 Camera2 人脸边界（`mapSensorBoxToActiveFrame`）、点按对焦点与 `32×24` 显著性重心/离散度（`MoveGuideState` + 迟滞防抖），仅在主体偏移或距离失衡时于取景框四边中点渐显极简琥珀方向环（`←` / `→` / `↑` / `↓`）并在底边中央轻提示距离徽标（`»·« 往前` / `«·» 往后`），构图到位即自动淡出，绝不在脸上划线。
  - **纯净取景辅助线**：顶部网格按钮循环切换 `关 → 三分线 → 黄金螺旋 → 中心十字`（黄金螺旋模式下长按旋转 `90°` 起始方向），搭配机械风格实时水平仪。
  - **曝光轻提示（单行轻点一键修正）**：`GLFilmRenderer` 从主预览流下采样 `32×24` 原始帧，`ExposureAdvisor` 仅在过曝、欠曝或明显逆光时显示可点单行轻提示，点击直接补偿 EV 或对主体测光，无操作自动淡出。
  - **稳拍快门**：`StabilityMeter` 基于陀螺仪角速率（无陀螺仪回退高通加速度计）判断手抖状态；开启后按快门会在快门外环绘制最长 `1.5s` 的琥珀等待弧，持稳、超时或再次点按快门立即成像。
- **BYOK AI 摄影导师（按需看片诊断 + 一键套用）**：
  - **取景底栏教练卡（零遮挡取景器）**：点击取景器左下角图标仅在当次抓取预览帧（或仅传光表参数）询问模型；卡片弹出在底部操作区之上，取景画面与日期背印全程保持完全无遮挡。针对 DeepSeek 默认开启的思考模式显式传入 `thinking: {"type": "disabled"}` 并过滤 `reasoning_content`，杜绝思考草稿挤占回复。输出结构化「诊断 + 改法」，如模型建议切换胶片、增减 EV 或开启辅助线，可直接点 **`一键套用`** 生效。
  - **一句话 AI 调色配方师**：长按底栏胶片胶囊呼出配方面板点 `✦ AI 调色`，输入电影感/光线描述即可联动匹配基底胶片、浓度/颗粒/冷暖与黑柔光开关并实时预览。
  - **暗房灯箱 AI 复盘**：在底片灯箱点 `AI 点评`，结合成片与 EXIF 输出「诊断 + 改法」并本地缓存结果。
- **十卷自有命名胶片**（色彩矩阵 + S 曲线 + 双重银盐颗粒 + Halation 高光晕 + 光学暗角 + 漏光 + 配套相纸）：

  | 胶片 | 风格特点 | 典型用途 |
  |---|---|---|
  | `原色` | 原片直出，不施滤镜 | 保留原始色彩 + 相纸排版 |
  | `暖肤 400` | 暖肤色、低反差、柔和高光晕 | 人像与日常光影 |
  | `日光 200` | 金黄日光、暖调高光、暖黄漏光 | 晴天街头与秋日 |
  | `冷调 400` | 青绿阴影、清透冷调 | 街拍与阴天城市 |
  | `浓郁 50` | 高饱和、强反差、深邃蓝绿 | 风景与自然色彩 |
  | `钨丝 800` | 钨丝灯夜景、冷青暗部、霓虹红晕 | 夜景与霓虹灯火 |
  | `旧日 64` | 纪实暖褐红、厚重反差 | 人文纪实与建筑 |
  | `硬调黑白` | 硬调黑白、粗颗粒、重暗角 | 经典高反差黑白 |
  | `柔调黑白` | 柔和高宽容度、细腻灰阶 | 人文扫街黑白 |
  | `一次成像` | 褪色奶油暖黄、强漏光、宽底边相纸 | 一次成像风格 |

  > 片名是本项目自创的描述性命名，只描述观感，不暗示与任何胶片厂商的关联或背书。
- **实时摄影操控与手势捷径**：
  - **点按对焦与测光**（取景框准星脉冲动画，严格限定于当前画幅内）+ **EV 曝光补偿滑条**。
  - **手势快捷操控**：**双指捏合变焦**（配合 `1.0x` / `1.5x` / `2.0x` 焦段胶囊）、**双击取景器切换前后镜头**、**左右滑取景器切卷换片**。
  - **五档画幅切换**：`3:4` 经典底片 / `3:2` 35mm 全画幅 / `1:1` 方形 / `16:9` 宽银幕 / `XPAN`（`65:24` 超宽电影画幅）。横持时竖构图比例会翻转为 `4:3`，**取景遮幅与成片裁切使用同一个比例函数**，所见即所得。
  - **三模式拍摄切换（`1× 单张 / 2× 重曝 / ½ 半格`）**：点击底部模式胶囊一键轮换 —— 支持经典 **Screen 滤色双重曝光** 与每卷 72 格按握持方向智能双拼的 **½ 半格双联底片**（竖拍左右并排、横拍上下叠拼）。
  - **黑柔光漫射 & 自定义胶片配方（Recipe）**：暗房开启「黑柔光」可在高光处柔和散射电影光晕；长按底栏任意胶片胶囊可实时微调 **浓度 / 颗粒 / 冷暖** 并保存为个人专属配方胶囊。
  - **前置暖光屏幕补光 & 自动横竖屏 EXIF**：三档定时自拍（OFF / 3s / 10s）、闪光灯 `AUTO / ON / OFF`（前置开启 `ON` 时触发 `#FFF4E2` 暖白高亮度屏幕补光）、`OrientationEventListener` 迟滞量化实现锁定竖屏 UI 下的真横/竖屏成像与原始相机 EXIF（机型/快门/光圈/ISO/焦距等）保留、音量键实体快门、机械幕帘快门动效与触觉反馈。
- **暗房调校面板 & 相纸定制**：
  - 自定义暗房底部面板：可选 **暖白相纸 / 黑胶底片边 / 35mm 齿孔底片框（附 EXIF 快门光圈 ISO 印记） / 无边框**，**今日日期 / '98 时光机日期 / 关闭**，**质感（颗粒、漏光、黑柔光、水平仪、快门声、镜像）**、**轻辅助（曝光轻提示、稳拍快门）**、AI 胶片顾问、取景旋转校准、装入新胶卷。
  - **第三方开源许可**：面板内「开源许可」可查看随包附带的 MIT / OFL 全文与 NOTICE。
  - **检查更新**：点按后请求 GitHub Releases API，比对本机 `versionName`；有新版本时显示 tag 与更新说明，可直接下载 APK —— 下载完成会先比对签名证书与已安装应用是否一致，通过后才交给系统安装器；不一致则丢弃并转到浏览器。若设备禁止未知来源安装，会回退到浏览器下载。无后台轮询、无账号、无统计。
- **相册导入暗房冲印（Darkroom Lab）**：
  - 从手机相册选取任意照片进入暗房（自动保留横/竖屏与 8 态 EXIF 朝向），实时切换 10 卷胶片预览，并可在相纸下沿印上一句简短注记（最多 36 字）后洗印保存。
- **应用内胶卷底片库 & 35mm 联系表（Contact Sheet）**：
  - 点击左下角缩略图进入底片库：支持网格浏览、全屏左右滑动灯箱、系统分享与删除（Android 8/9 通过 `FileProvider` 分享，不会抛 `FileUriExposedException`；删除失败会明确提示）。
  - 支持多选 2–9 张照片生成带有 35mm 胶片齿孔与 `01A / 02A` 帧号的 **Contact Sheet 胶片联系表** 长图。
- **所见即所得双管线渲染与精确方向变换**：
  - 预览使用 `samplerExternalOES`，成片与导入图使用 FBO 离屏 `sampler2D`，共用同一份基于屏幕/画幅归一化坐标的 `filmColor()` GLSL 着色器；颗粒频率按画幅短边（参考 1180px）统一定义，成片在快门瞬间冻结预览的时间种子，避免「预览与成片的颗粒图案不是同一张」。
  - 借助 `CameraTransformMath` 严格区分 CameraX `TransformationInfo.hasCameraTransform()` 双路径并补偿 `cropRect` 与显示旋转，配合 `AspectFrameLayout` 锁定取景器宿主容器宽高比。

## 2. 技术栈

| 层 | 选择 |
|---|---|
| 语言 | Java 17（零多余依赖，保持安装包轻量） |
| 相机 | CameraX 1.3.4（`Preview` + `ImageCapture` + `Camera2Interop` 人脸检测 + `FocusMeteringAction` + `Zoom/EV`） |
| 渲染 | OpenGL ES 2.0，`GL_TEXTURE_EXTERNAL_OES` + 通用胶片片元着色器 + `32×24` 轻量级 FBO 原始帧采样子管线 |
| 构建 | Gradle 8.9 + Android Gradle Plugin 8.7.3，JDK 17 |
| 平台 | `minSdk 26` / `targetSdk 35` / `compileSdk 36` |
| 依赖 | CameraX 1.3.4 + `androidx.activity:1.9.3` + `androidx.core:1.13.1`（FileProvider）；JVM 单测使用 JUnit 4.13.2 |

权限只有四项，全都有明确用途：

| 权限 | 用途 |
|---|---|
| `CAMERA` | 取景与拍摄 |
| `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion=28`） | Android 8/9 写入相册 |
| `INTERNET` | 仅在用户主动点击「检查更新」或「AI 胶片顾问」时发起 HTTPS 请求 |
| `REQUEST_INSTALL_PACKAGES` | 把下载好的更新交给系统安装器 |

没有定位、通讯录、电话状态、存储读取（Android 10+ 走 Scoped Storage）等任何其他权限。

## 3. 构建与测试

### 本地

需要 JDK 17、Android SDK（platform 36 + build-tools 36.0.0），并配置 `ANDROID_HOME` / `local.properties`：

```bash
./gradlew testDebugUnitTest  # 运行 9 个 JVM 单元测试类（52 项测试）
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 需 keystore.properties，否则产出 unsigned 包
```

### release 签名

仓库**不包含任何密钥**。`app/build.gradle` 按以下顺序读取签名信息：

1. 环境变量 `OTC_STORE_FILE` / `OTC_STORE_PASSWORD` / `OTC_KEY_ALIAS` / `OTC_KEY_PASSWORD`；
2. 根目录 `keystore.properties`（已在 `.gitignore` 中）。

两者都缺失时 release 包不会被签名，产物名为 `app-release-unsigned.apk` —— 这是有意的：**公开仓库里的 debug key 不能用于签名发布包**。release 构建已开启 R8 混淆与资源压缩，`proguard-rules.pro` 保留了布局中以类名反射实例化的自定义 View。

### GitHub Actions

- `pull_request`、`main` push、`workflow_dispatch`：安装依赖 → 跑单测 → 构建 debug（仅验证编译，且桌面名为 `旧时光 · 测试` 避免与正式版混淆）与 release → 仅将 release APK 上传至 artifact。
- `v*` tag：额外触发 `release` job（仅该 job 拥有 `contents: write`），把**已签名的** `app-release.apk`（固定包名 `com.bowentown.oldtimecamera` 与固定正式签名证书）挂到 GitHub Release，支持旧版正式包直接原地覆盖更新；debug 包不再随构建产出打包提供。
- 签名材料来自仓库 Secrets（`OTC_KEYSTORE_BASE64` / `OTC_STORE_PASSWORD` / `OTC_KEY_ALIAS` / `OTC_KEY_PASSWORD`），构建时写入 runner 临时目录，随 runner 销毁。

## 4. 目录

```
app/src/main/java/com/bowentown/oldtimecamera/
├── MainActivity.java           相机生命周期、Camera2 人脸/测光、对焦/变焦/EV、多模式拍摄、AI 导师底栏卡
├── GalleryActivity.java        底片库、灯箱 EXIF/AI 复盘、分享/删除、35mm 联系表导出
├── DarkroomSheet.java          暗房底部面板（画幅、相纸、日期、质感、轻辅助、校准、许可、更新）
├── DarkroomEditorDialog.java   相册照片导入暗房洗印、套用当前胶片配方与相纸注记
├── AspectFrameLayout.java      按相机缓冲宽高比锁定取景宿主容器
├── FilmCameraView.java         GLSurfaceView 封装、手势、GL 任务队列、资源释放
├── ViewfinderOverlayView.java  遮幅帘幕、角标、边缘移动极简符号、网格/黄金螺旋、水平仪、双曝/半格预览
├── ShutterProgressView.java    稳拍等待琥珀进度弧
├── GLFilmRenderer.java         统一 GLSL（含黑柔光漫射）、纹理变换、测光 FBO、离屏成片、GL 资源释放
├── FilmRenderMath.java         颗粒尺度一致性、黑柔光步长与指针测光停档换算（纯 Java）
├── FilmRecipe.java             胶片浓度/颗粒/冷暖自定义配方合成与存储（纯 Java）
├── CameraTransformMath.java    GL UV 矩阵、hasCameraTransform 双路径、EXIF 与方向量化
├── CompositionMath.java        构图几何、黄金螺旋、四向/前后防抖移动引导状态机、传感器边界反算
├── PreviewStats.java           32×24 原始帧亮度/12 档分布/高光/死黑/色温/显著性重心与离散度
├── ExposureAdvisor.java        实时曝光轻提示与迟滞状态机
├── StabilityMeter.java         滑动窗口 RMS 手抖检测（稳拍快门判定）
├── FilmPreset.java             胶片配方参数模型
├── FilmPresets.java            十卷内置胶片（自有命名）
├── PhotoSaver.java             裁切、Screen/半格双拼、35mm 齿孔与相纸排版、EXIF/配方、联系表
├── CameraPrefs.java            胶卷计数、模式/网格/配方/轻辅助/AI 点评缓存持久化
├── FilmUiHelper.java           安全区适配、字体加载与内置/自定义胶片色卡条
├── AiCoachMath.java            BYOK AI 端点归一化、结构化建议与提示词/JSON 容错解析（纯 Java）
├── AiKeyStore.java             AndroidKeyStore AES-GCM 硬件加密存储用户 API Key
├── AiCoachClient.java          零第三方依赖 HTTPS 客户端（自动关 DeepSeek 思考模式并过滤 reasoning）
├── AiSettingsDialog.java       暗房 BYOK 模型与密钥设置弹窗（支持连通测试）
├── AiRecipeDialog.java         一句话自然语言调胶片配方弹窗
├── LicenseDialog.java          APK 内 MIT / OFL / NOTICE 查看
├── UpdateChecker.java          GitHub Releases 检查更新与证书校验安装
└── VersionMath.java            语义化版本比较（纯 Java）
app/src/main/assets/licenses/   MIT、OFL、NOTICE 全文（随 APK 分发）
app/src/test/java/com/bowentown/oldtimecamera/   9 个纯 JVM 测试类（52 项测试）
LICENSES/                       仓库侧第三方许可全文
NOTICE                          专有声明 + 第三方资产例外 + 商标说明
docs/                           OFL 原文与开源项目调研
```

## 5. 第三方开源资产致谢

以下 UI 视觉资产依据其许可证合规纳入。许可全文同时存在于仓库 `LICENSES/` 与 APK 内 `assets/licenses/`，并可在应用「暗房 → 开源许可」中查看：

- **Phosphor Icons**（Regular 线性图标集）：**MIT License**，Copyright (c) 2020-2023 Phosphor Icons。转为本项目矢量图标（`app/src/main/res/drawable/ic_*.xml`，启动图标除外）。
- **JetBrains Mono**（`jetbrains_mono_regular.ttf`）：**SIL Open Font License 1.1**，Copyright 2020 The JetBrains Mono Project Authors。未修改、未单独销售，随包附许可证正文。

AndroidX / CameraX 通过 Maven 以 Apache-2.0 依赖引入，未在本仓库内二次分发源码。

## 6. 许可

本仓库仅作源码展示，**未授予任何开源许可**：© 2026 bowentown，保留所有权利。未经许可不得复制、修改、再分发或用于商业目的。

例外：上述第三方资产仍归其各自许可证管辖，详见 `NOTICE` 与 `LICENSES/`。
