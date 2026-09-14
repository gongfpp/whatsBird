# whatsBird · 识鸟

面向中国大陆常见鸟类的 Android 本地识别原型。应用使用 CameraX 获取相机画面，在设备端完成鸟类检测、鸟种分类、目标跟踪和标签叠加，并支持保存原图或带标签照片。

> 当前仍处于开发阶段，尚未作为稳定版本发布。识别精度、异常降级和持续扫描稳定性仍在完善，不建议将当前源码构建视为正式发行版。

## 技术栈

- Kotlin / Jetpack Compose
- CameraX
- MediaPipe Object Detector
- LiteRT / TensorFlow Lite
- DataStore
- Android 10 / API 29 及以上

核心识别链路：

`相机帧 → 鸟类检测 → 目标跟踪 → 鸟种分类 → 多帧稳定 → 标签叠加`

拍照时会基于实际拍到的静态照片重新执行检测和分类，不直接复用预览画面中的旧标签。

## 构建

项目近期使用 JDK 21 构建，Android 源码位于 `android/`：

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

SDK 路径通过本机 `android/local.properties` 或 `ANDROID_HOME` 配置，不提交到仓库。Debug APK 生成在：

```text
android/app/build/outputs/apk/debug/
```

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `android/` | Android App、单元测试、真机测试和随包模型 |
| `ml/` | 数据获取、完整性检查、训练、模型导出和诊断脚本 |
| `docs/MODELS.md` | 随包模型、测试图片及来源说明 |
| `.gitleaks.toml` | 开源提交的敏感信息扫描规则 |

模型与训练流程说明见 [ml/README.md](ml/README.md)，随包模型和测试资产来源见 [docs/MODELS.md](docs/MODELS.md)。

## 数据与隐私

训练数据、训练中间产物、个人拍摄照片、设备截图、原始日志、APK、签名材料以及本机配置均不提交到仓库。公开的 Android 测试图片来自允许使用的公开来源，并保留逐张来源和许可记录。

应用设计目标是让识别在设备本地完成，照片不需要上传到服务端。正式发布前仍应以最终 APK 的合并 Manifest 和实际网络行为为准完成隐私验证。

## 测试

仓库包含针对标签稳定、目标跟踪、坐标转换、异常恢复以及真实端侧模型链路的测试。基础检查可执行：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug
```

真机模型与相机相关测试位于 `android/app/src/androidTest/`，需要连接 Android 设备运行。
