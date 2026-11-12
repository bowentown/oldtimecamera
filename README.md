# 旧时光相机 · Old Time Camera

一个极简的 Android 胶片相机：**一块取景框、七卷经典胶片、一个快门按钮**。
滤镜在取景时实时生效，按下快门后同一套着色器作用于全分辨率照片，再叠上相纸白边与橙色日期背印，写入系统相册。

纯本地、无账号、无网络请求、无第三方 SDK、无广告。

---

## 1. 功能

- **实时预览**：CameraX 的预览帧直接进 OpenGL ES 外部纹理，片元着色器逐帧处理，不走 WebView、不做 CPU 逐像素转换。
- **七卷胶片**（每卷是一整套搭配，不只是调色）：

  | 胶片 | 色彩 | 相纸 / 水印搭配 |
  |---|---|---|
  | `ORIGINAL` | 不施滤镜 | 象牙白边 + 橙色日期 |
  | `PORTRA 400` | 暖肤色、低反差、细颗粒 | 象牙白边 + 日期 + 轻微暖调漏光 |
  | `GOLD 200` | 金黄日光、暖高光 | 米黄相纸 + 日期 + 暖黄漏光 |
  | `SUPERIA 400` | 青绿阴影、冷调 | 冷白相纸 + 日期 |
  | `VELVIA 50` | 高饱和、强反差、深绿蓝 | 纯白相纸，无漏光 |
  | `TRI-X 400` | 黑白硬调、粗颗粒、重暗角 | 灰白相纸，**不印日期** |
  | `POLAROID 600` | 褪色偏黄、强漏光 | 宽白边 + 橙色日期 |

- **同一套参数，两处渲染**：预览用 `samplerExternalOES`，成片用 FBO 离屏渲染 `sampler2D`，共用一份 `filmColor()` GLSL，保证「所见即所得」。
- **每格成像效果**：3×3 色彩矩阵、饱和度 / 对比度 / 亮度、褪色黑位、halation 高光晕、漏光、暗角、动态颗粒、黑白模式。
- **相纸排版**：照片白边、下沿胶片型号、右下角复古橙色日期，全部在保存时由 Canvas 烘焙进 JPEG。
- **基础相机控制**：前后摄切换、闪光灯 AUTO / ON / OFF、日期水印 / 相纸白边 / 加强颗粒开关、胶卷计数、最近一张缩略图（点击打开相册）。
- **离线保存**：Android 10+ 通过 MediaStore 写入 `Pictures/OldTimeCamera`，无需存储权限；Android 8/9 回退到公共目录 + 媒体扫描。

## 2. 技术栈

| 层 | 选择 |
|---|---|
| 语言 | Java 17（不使用 Kotlin / Compose，减少工具链变量） |
| 相机 | CameraX 1.3.4（`Preview` + `ImageCapture`） |
| 渲染 | OpenGL ES 2.0，`GL_TEXTURE_EXTERNAL_OES` + 一个通用片元着色器 |
| 构建 | Gradle 8.9 + Android Gradle Plugin 8.7.3，JDK 17 |
| 平台 | `minSdk 26` / `targetSdk 35` / `compileSdk 36` |
| 依赖 | 仅 CameraX 四个模块，不引入图片加载、滤镜、统计、广告库 |

## 3. 构建

### 本地

需要 JDK 17、Android SDK（platform 36 + build-tools 36.0.0），并设置 `ANDROID_HOME` / `local.properties`：

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # release 使用 debug 签名，便于直接安装
```

`release` 用 debug key 签名，是为了不在公开仓库里放私有 keystore；正式上架前应换成自己的签名配置。

### GitHub Actions

`main` 分支的每次 push 都会跑 `.github/workflows/build-apk.yml`，产出 debug / release 两份 APK 作为构建产物；推送 `v*` 标签时会额外创建 Release 并附上 APK。

## 4. 目录

```
app/src/main/java/com/bowentown/oldtimecamera/
├── MainActivity.java       相机生命周期、权限、UI、拍照与保存流程
├── FilmCameraView.java     GLSurfaceView 封装、取景比例、GL 线程任务队列
├── GLFilmRenderer.java     着色器、外部纹理、FBO 离屏成片
├── FilmPreset.java         一卷胶片的参数模型
├── FilmPresets.java        七卷内置胶片
├── PhotoSaver.java         相纸排版与 MediaStore 写入
└── PerforationView.java    胶片齿孔装饰
docs/开源项目调研.md          立项阶段的开源竞品与许可证调研
```

## 5. v1 已知边界

- 成片长边限制为 2048px（控制离屏 FBO 与 `glReadPixels` 的内存峰值）。
- 不做持续定位、地图、书籍、笔记、账号、同步、分享社区——v1 只是一台相机。
- 相机方向依赖 CameraX 的 `TransformationInfo`，不同机型的传感器方向若有差异，可能需要微调旋转方向。
- 未做多摄 / 广角切换、手动曝光、RAW、连拍、视频。
- 尚未在真机上做完整的兼容性矩阵验证；建议先侧载安装，检查取景方向与成片方向。

## 6. 许可

本仓库仅作源码展示，**未授予任何开源许可**：© 2026 bowentown，保留所有权利。
未经许可不得复制、修改、再分发或用于商业目的。若后续决定开源，请在此处补充 LICENSE 并同步 CI 依赖合规检查。

> 仓库中的 `docs/开源项目调研.md` 记录了相关开源项目的许可证边界，其中 AGPL / GPL 项目仅作为思路参考，本工程未引入其代码或素材。所有胶片预设均为参数组合，不含任何第三方 LUT 文件或商业 App 的美术资产。
