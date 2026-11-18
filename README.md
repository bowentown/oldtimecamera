# 旧时光相机 · Old Time Camera

一个克制、优雅的 Android 纯本地胶片相机（`v1.5.0`）：**一块无遮挡光学取景窗、十卷胶片、一颗机械快门**。
实时预览与全分辨率成片共用同一套 OpenGL ES 片元着色器，拍摄或导入洗印后叠上相纸边框、编号与复古橙色日期背印，写入系统相册并保留相机拍摄 EXIF 元数据。

纯本地存储、无账号、无统计/广告 SDK。唯一的网络访问由你主动触发：暗房面板里的「检查更新」会请求本仓库的 GitHub Releases API，其余任何功能都不会联网。

---

## 1. 功能亮点

- **胶片暖调暗房精修**：
  - 哑光碳黑机身（`#11100E`）+ 暖象牙纸白（`#F0E8D8`）+ 琥珀指示灯（`#F2A65A`）+ 陶土橙点缀（`#D25A34`），层级文字排版、按压水波纹反馈与 Edge-to-Edge 安全区适配。
  - 统一接入开源线性图标族 **Phosphor Icons**（Regular，MIT）与内置等宽字体 **JetBrains Mono Regular**（OFL 1.1），确保帧数计数、EV、焦段与底片页码具备一致的光学器械精密感。
  - 取景器与底栏分区明晰，切换画幅时仿旁轴相机的遮幅帘幕平滑收合；支持点击或长按取景框右下角橙色 LED 日期直接切换/隐藏日期印记。
- **新手教学指导与任务闭环（视觉直觉 + 微复盘）**：
  - **多模构图辅助、方位指引箭头 & 6 套姿势线稿模板**：顶部网格按钮循环切换 `关 → 三分线 → 黄金螺旋 → 中心十字`（黄金螺旋模式下长按旋转 `90°` 起始方向）；主体按「Camera2 硬件人脸 → 最近点按对焦点 → `32×24` 视觉显著性重心」自动选取。当主体明显偏离构图点时绘制指向最近交点的极细导引箭头与头顶留白警界线；左下角极简浮标可一键轮换 `人像三分 / 背影留白 / 低角仰拍 / 建筑对称 / 桌面静物 / 黄金对角` 6 套纯矢量构图/站位线稿。
  - **底栏测光表与直方条（`MeterStripView`）**：在主控胶囊上方展示 12 档实时亮度分布柱与 `-2..0..+2` 机械风格测光指针，点击即可联动展开 EV 滑条。
  - **智能曝光提示（单行轻点一键修正）**：`GLFilmRenderer` 每 ~300ms 从主预览流中额外下采样一帧未套滤镜的 `32×24` 原始帧，纯 Java `ExposureAdvisor` 识别「太亮 · 下拉 EV / 偏暗 · 上推 EV / 逆光 · 点主体测光」；单行浮现且可点击直接调整 EV 或对主体测光，无操作自动淡出且从不堆叠多层气泡。
  - **暗房「新手三卷入门挑战」（6 格微任务）+ 拍后一句话微复盘**：可在暗房面板「摄影练习卷」随时领用《三分与留白》《光线与测光》《水平与街头》三卷入门挑战（每卷 6 格）；按 *One-Change Rule* 在快门成像后通过底栏胶囊与底片灯箱附上一句简洁拍摄诊断，绝不弹窗阻断连拍。
  - **场景识别推荐胶片（仅推荐，永不自动换片）**：纯 Java `SceneClassifier` 结合暗场/人脸/逆光/金黄时刻/冷调阴天/高饱和风光/低饱和黑白共 6 类信号与通用态，在胶片条上方轻提示可忽略的 `试试 …` 胶囊，点按试片或点 `×` 忽略当前场景，绝不强行切走用户选片；镜头被遮挡或有效帧缺失时不会误判为夜景。
  - **手抖提示 & 稳拍快门**：`StabilityMeter` 基于陀螺仪角速率滑动窗口 RMS（无陀螺仪设备自动回退高通滤波加速度计）识别手抖并单行提示 `拿稳一点`；样本不足 3 帧时不会误判为「稳」，快门直接放行。开启「稳拍快门」后，手抖状态下按快门会在快门外环绘制最长 `1.5s` 的琥珀等待弧，一旦持稳、到达超时或再次点按快门立即成像（切镜头或退后台自动取消）。
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
  - **多模拍摄切换（`1× 单张 / 2× 重曝 / ½ 半格 / 4 格连拍`）**：点击底部模式胶囊即可切换，长按展开 4 格快选栏 —— 支持经典 **Screen 滤色双重曝光**、每卷 72 格按握持方向智能双拼的 **½ 半格双联底片**（竖拍左右并排、横拍上下叠拼），以及按下快门连续抓拍 4 帧的 **4 格动作胶片条**。
  - **黑柔光漫射、摇一摇漏光 & 自定义胶片配方（Recipe）**：暗房开启「黑柔光」可在高光处柔和散射电影光晕；「摇一摇换漏光」随手轻晃机身切换漏光角度；长按底栏任意胶片胶囊可实时微调 **浓度 / 颗粒 / 冷暖** 并保存为个人专属配方胶囊。
  - **前置暖光屏幕补光 & 自动横竖屏 EXIF**：三档定时自拍（OFF / 3s / 10s）、四态构图辅助线、闪光灯 `AUTO / ON / OFF`（前置开启 `ON` 时触发 `#FFF4E2` 暖白高亮度屏幕补光）、`OrientationEventListener` 迟滞量化实现锁定竖屏 UI 下的真横/竖屏成像与原始相机 EXIF（机型/快门/光圈/ISO/焦距等）保留、音量键实体快门、机械幕帘快门动效与触觉反馈。
- **暗房调校面板 & 相纸定制**：
  - 自定义暗房底部面板：可选 **暖白相纸 / 黑胶底片边 / 35mm 齿孔底片框（附 EXIF 快门光圈 ISO 印记） / 无边框**，**今日日期 / '98 时光机日期 / 关闭**，**质感（颗粒、漏光、黑柔光、摇一摇换漏光、水平仪、快门声、镜像）**、**辅助 & 摄影练习卷**、取景旋转校准、装入新胶卷。
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
| `INTERNET` | 仅「检查更新」时访问 GitHub Releases API |
| `REQUEST_INSTALL_PACKAGES` | 把下载好的更新交给系统安装器 |

没有定位、通讯录、电话状态、存储读取（Android 10+ 走 Scoped Storage）等任何其他权限。

## 3. 构建与测试

### 本地

需要 JDK 17、Android SDK（platform 36 + build-tools 36.0.0），并配置 `ANDROID_HOME` / `local.properties`：

```bash
./gradlew testDebugUnitTest  # 运行 10 个 JVM 单元测试类（54 项测试）
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 需 keystore.properties，否则产出 unsigned 包
```

### release 签名

仓库**不包含任何密钥**。`app/build.gradle` 按以下顺序读取签名信息：

1. 环境变量 `OTC_STORE_FILE` / `OTC_STORE_PASSWORD` / `OTC_KEY_ALIAS` / `OTC_KEY_PASSWORD`；
2. 根目录 `keystore.properties`（已在 `.gitignore` 中）。

两者都缺失时 release 包不会被签名，产物名为 `app-release-unsigned.apk` —— 这是有意的：**公开仓库里的 debug key 不能用于签名发布包**。release 构建已开启 R8 混淆与资源压缩，`proguard-rules.pro` 保留了布局中以类名反射实例化的自定义 View。

### GitHub Actions

- `pull_request`、`main` push、`workflow_dispatch`：安装依赖 → 跑单测 → 构建 debug + release → 上传 artifact。
- `v*` tag：额外触发 `release` job（仅该 job 拥有 `contents: write`），把**已签名的** `app-release.apk` 挂到 GitHub Release；debug 包只留在 Actions artifact 中，不再对外发布。
- 签名材料来自仓库 Secrets（`OTC_KEYSTORE_BASE64` / `OTC_STORE_PASSWORD` / `OTC_KEY_ALIAS` / `OTC_KEY_PASSWORD`），构建时写入 runner 临时目录，随 runner 销毁。

## 4. 目录

```
app/src/main/java/com/bowentown/oldtimecamera/
├── MainActivity.java           相机生命周期、Camera2 人脸/测光、对焦/变焦/EV、多模式拍摄、辅助统筹
├── GalleryActivity.java        底片库、灯箱 EXIF/微复盘、分享/删除、35mm 联系表导出
├── DarkroomSheet.java          暗房底部面板（画幅、相纸、日期、质感、辅助、练习卷、校准、许可、更新）
├── DarkroomEditorDialog.java   相册照片导入暗房洗印、套用当前胶片配方与相纸注记
├── AspectFrameLayout.java      按相机缓冲宽高比锁定取景宿主容器
├── FilmCameraView.java         GLSurfaceView 封装、手势、GL 任务队列、资源释放
├── ViewfinderOverlayView.java  遮幅帘幕、角标、网格、导引箭头、6 套站位线稿、水平仪、双曝/半格预览
├── MeterStripView.java         12 档实时亮度柱状条与 -2..+2 机械指针测光尺
├── ShutterProgressView.java    稳拍等待进度弧 & 4 格动作连拍节拍环
├── GLFilmRenderer.java         统一 GLSL（含黑柔光漫射）、纹理变换、测光 FBO、离屏成片、GL 资源释放
├── FilmRenderMath.java         颗粒尺度一致性、黑柔光步长与指针测光停档换算（纯 Java）
├── FilmRecipe.java             胶片浓度/颗粒/冷暖自定义配方合成与存储（纯 Java）
├── PracticeRoll.java           新手三卷 6 格练习挑战与 One-Change 拍后诊断引擎（纯 Java）
├── CameraTransformMath.java    GL UV 矩阵、hasCameraTransform 双路径、EXIF 与方向量化
├── CompositionMath.java        构图几何、黄金螺旋、导引向量、6 套矢量线稿原语、坐标反算
├── PreviewStats.java           32×24 原始帧亮度/12 档分布/高光/死黑/色温/显著性
├── ExposureAdvisor.java        实时曝光建议与迟滞状态机
├── SceneClassifier.java        场景识别与可忽略推荐（绝不自动切换）
├── StabilityMeter.java         滑动窗口 RMS 手抖检测 & 摇一摇漏光触发器
├── FilmPreset.java             胶片配方参数模型
├── FilmPresets.java            十卷内置胶片（自有命名）
├── PhotoSaver.java             裁切、Screen/半格/4格拼拍、35mm 齿孔与相纸排版、EXIF/配方、联系表
├── CameraPrefs.java            胶卷计数、模式/网格/线稿/配方/练习卷/诊断记录持久化
├── FilmUiHelper.java           安全区适配、字体加载与内置/自定义胶片色卡条
├── LicenseDialog.java          APK 内 MIT / OFL / NOTICE 查看
└── UpdateChecker.java          GitHub Releases 检查更新与证书校验安装
app/src/main/assets/licenses/   MIT、OFL、NOTICE 全文（随 APK 分发）
app/src/test/java/com/bowentown/oldtimecamera/   10 个纯 JVM 测试类（54 项测试）
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
