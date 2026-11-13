# 旧时光相机 · Old Time Camera

一个克制、优雅的 Android 纯本地胶片相机（`v1.2.0`）：**一块无遮挡光学取景窗、十卷经典胶片、一颗机械快门**。
实时预览与全分辨率成片共用同一套 OpenGL ES 片元着色器，拍摄或导入洗印后叠上相纸边框、编号与复古橙色日期背印，写入系统相册并保留相机拍摄 EXIF 元数据。

纯本地、无账号、无网络请求、无第三方广告/统计 SDK。

---

## 1. 功能亮点

- **简洁克制的暗房审美**：
  - 哑光碳黑机身（`#11100E`）+ 暖象牙纸白（`#F0E8D8`）+ 陶土橙点缀（`#D25A34`），自绘矢量细线图标与 Edge-to-Edge 安全区适配；移除冗余标题与常驻描述文字，把画面留给取景器本身。
  - 取景器与底栏分区明晰，切换画幅时仿旁轴相机的遮幅帘幕平滑收合；支持点击或长按取景框右下角橙色 LED 日期直接切换/隐藏日期印记。
- **十卷经典胶片**（色彩矩阵 + S 曲线 + 双重银盐颗粒 + Halation 高光晕 + 光学暗角 + 漏光 + 配套相纸）：

  | 胶片 | 风格特点 | 典型用途 |
  |---|---|---|
  | `RAW` | 原片直出，不施滤镜 | 保留原始色彩 + 相纸排版 |
  | `PORTRA 400` | 暖肤色、低反差、柔和高光晕 | 人像与日常光影 |
  | `GOLD 200` | 金黄日光、暖调高光、暖黄漏光 | 晴天街头与秋日 |
  | `SUPERIA` | 青绿阴影、清透冷调 | 街拍与阴天城市 |
  | `VELVIA 50` | 高饱和、强反差、深邃蓝绿 | 风景与自然色彩 |
  | `800T` | 钨丝灯电影卷、冷青暗部、霓虹红晕 | 夜景与霓虹灯火 |
  | `KODA 64` | 纪实暖褐红、厚重反差 | 人文纪实与建筑 |
  | `TRI-X 400` | 硬调黑白、粗颗粒、重暗角 | 经典高反差黑白 |
  | `HP5` | 柔和高宽容度、细腻灰阶 | 人文扫街黑白 |
  | `POLA 600` | 褪色奶油暖黄、强漏光、宽底边相纸 | 一次成像拍立得 |

- **实时摄影操控与手势捷径**：
  - **点按对焦与测光**（取景框准星脉冲动画，严格限定于当前画幅内）+ **EV 曝光补偿滑条**。
  - **手势快捷操控**：**双指捏合变焦**（配合 `1.0x` / `1.5x` / `2.0x` 焦段胶囊）、**双击取景器切换前后镜头**、**左右滑取景器切卷换片**。
  - **五档画幅切换**：`3:4` 经典底片 / `3:2` 35mm 全画幅 / `1:1` 方形拍立得 / `16:9` 宽银幕 / `XPAN`（`65:24` 超宽电影画幅），预览与出图逐帧一致；横拍或导入横图时 `3:4` 自动保持 `4:3` 横幅构图。
  - **2× 双重曝光（Screen Double Exposure）**：点击 HUD 的 `2×` 重曝按钮后，首帧捕获后在取景框叠加半透明残影辅助二次构图，第二帧通过经典胶片 **Screen 滤色混合** 合成洗印。
  - **前置暖光屏幕补光 & 自动横竖屏 EXIF**：三档定时自拍（OFF / 3s / 10s）、三分构图网格、闪光灯 `AUTO / ON / OFF`（前置开启 `ON` 时触发 `#FFF4E2` 暖白高亮度屏幕补光）、`OrientationEventListener` 迟滞量化实现锁定竖屏 UI 下的真横/竖屏成像与原始相机 EXIF（机型/快门/光圈/ISO/焦距等）完整保留、音量键实体快门、机械幕帘快门动效与触觉反馈。
- **暗房调校面板 & 相纸定制**：
  - 自定义暗房底部面板：可选 **暖白相纸 / 黑胶底片边 / 无边框**，**今日日期 / '98 时光机日期 / 关闭**，以及 **颗粒增强、随机漏光、水平仪（重力水平准线与水平卡点震动反馈）、快门声、自拍镜像**、取景旋转校准与装入新胶卷（每卷 36 张自动计数并持久化）。
- **相册导入暗房冲印（Darkroom Lab）**：
  - 从手机相册选取任意照片进入暗房（自动保留横/竖屏与 8 态 EXIF 朝向），实时切换 10 卷胶片预览，并可在相纸下沿印上一句简短注记（最多 36 字）后洗印保存。
- **应用内胶卷底片库 & 35mm 联系表（Contact Sheet）**：
  - 点击左下角缩略图进入底片库：支持网格浏览、全屏左右滑动灯箱、一键系统分享与删除。
  - 支持多选 2–9 张照片生成带有 35mm 胶片齿孔与 `01A / 02A` 帧号的 **Contact Sheet 胶片联系表** 长图。
- **所见即所得双管线渲染与精确方向变换**：
  - 预览使用 `samplerExternalOES`，成片与导入图使用 FBO 离屏 `sampler2D`，共用同一份基于屏幕/画幅归一化坐标的 `filmColor()` GLSL 着色器。
  - 借助 `CameraTransformMath` 严格区分 CameraX `TransformationInfo.hasCameraTransform()` 双路径并补偿 `cropRect` 与显示旋转，配合 `AspectFrameLayout` 锁定取景器宿主容器宽高比，确保前/后摄与各厂商 HAL 下取景、漏光、暗角与最终成像完全一致。

## 2. 技术栈

| 层 | 选择 |
|---|---|
| 语言 | Java 17（零多余依赖，保持安装包轻量） |
| 相机 | CameraX 1.3.4（`Preview` + `ImageCapture` + `FocusMeteringAction` + `Zoom/EV`） |
| 渲染 | OpenGL ES 2.0，`GL_TEXTURE_EXTERNAL_OES` + 通用胶片片元着色器 |
| 构建 | Gradle 8.9 + Android Gradle Plugin 8.7.3，JDK 17 |
| 平台 | `minSdk 26` / `targetSdk 35` / `compileSdk 36` |
| 依赖 | 仅 CameraX 1.3.4 + `androidx.activity:1.9.3`（无图片框架、无统计、无网络库）；JVM 单测使用 JUnit 4.13.2 |

## 3. 构建与测试

### 本地

需要 JDK 17、Android SDK（platform 36 + build-tools 36.0.0），并配置 `ANDROID_HOME` / `local.properties`：

```bash
./gradlew testDebugUnitTest  # 运行 CameraTransformMath 与 PhotoSaver/Prefs JVM 单元测试
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # release 使用 debug 签名，便于直接侧载安装
```

### GitHub Actions

推送到 `main` 分支会触发 `.github/workflows/build-apk.yml`，自动运行 `./gradlew testDebugUnitTest assembleDebug assembleRelease` 并产出 debug / release 两份 APK；推送 `v*` 标签时会同步创建 GitHub Release 并附上 APK。

## 4. 目录

```
app/src/main/java/com/bowentown/oldtimecamera/
├── MainActivity.java           相机生命周期、对焦/变焦/EV、倒计时、前摄屏闪、2× 双曝、自动横竖屏拍照
├── GalleryActivity.java        应用内底片库、全屏灯箱、分享/删除、35mm 联系表导出
├── DarkroomSheet.java          自定义暗房调校底部面板（画幅、相纸、日期、水平仪、质感与校准）
├── DarkroomEditorDialog.java   相册照片导入暗房洗印与相纸注记
├── AspectFrameLayout.java      按相机缓冲宽高比锁定取景宿主容器，保证叠加层与角标对齐
├── FilmCameraView.java         GLSurfaceView 封装、捏合/点按/双击换镜/左滑右滑切卷手势、GL 任务队列
├── ViewfinderOverlayView.java  旁轴遮幅帘幕、角标、三分网格、水平仪、双曝残影、快门幕帘与对焦准星
├── GLFilmRenderer.java         统一 GLSL 胶片着色器、外部纹理变换与 FBO 离屏成片
├── CameraTransformMath.java    纯 Java GL UV 矩阵、hasCameraTransform 双路径、cropRect/EXIF 与方向量化
├── FilmPreset.java             胶片配方参数模型
├── FilmPresets.java            十卷内置经典胶片（RAW ~ POLA 600）
├── PhotoSaver.java             画幅裁切、Screen 双重曝光混合、相纸排版、EXIF 保留、联系表与 MediaStore 写入
├── CameraPrefs.java            胶卷计数与暗房偏好持久化
└── FilmUiHelper.java           沉浸式安全区适配与胶片色卡条共享组件
app/src/test/java/com/bowentown/oldtimecamera/
├── CameraTransformMathTest.java CameraX UV 矩阵、EXIF 8 态、旋转与方向迟滞量化单测
└── PhotoSaverAndPrefsTest.java  画幅裁切、横图/横拍比例、相纸几何、Screen 混合与胶片库单测
docs/开源项目调研.md              立项阶段及二次复核的开源竞品许可证与 clean-room 架构调研
```

## 5. 许可

本仓库仅作源码展示，**未授予任何开源许可**：© 2026 bowentown，保留所有权利。
未经许可不得复制、修改、再分发或用于商业目的。
