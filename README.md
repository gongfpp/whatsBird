# whatsBird · 识鸟

面向中国大陆常见鸟类的 Android 相机识别原型。使用 CameraX 展示取景画面，在本机检测鸟的位置、分类鸟种，并在画面上显示标签；支持保存原图与带标签的照片。

**当前为开发中的技术原型，尚未通过完整功能验收。** 最近一次复验发现实时扫描的统计日志会导致闪退，模型不可用时的拍照降级仍需完善，识别精度和跨版本测试集独立性也未达标。公开源码便于继续开发，不代表可稳定使用的正式版本。详情见 [整改复验报告](docs/ACCEPTANCE_RECHECK_2026-09-14.md)。

## 开发与构建

- Android 10 / API 29 及以上；项目使用 compileSdk / targetSdk 36。
- Kotlin、Jetpack Compose、CameraX、MediaPipe、LiteRT。
- 使用 Android Studio，或配置好 JDK 与 Android SDK 后通过 Gradle Wrapper 构建。本项目近期验证使用 JDK 21。

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

SDK 路径由本机的 `android/local.properties` 或 `ANDROID_HOME` 提供，相关机器配置不会提交。构建 APK 位于 `android/app/build/outputs/apk/debug/`。

## 仓库内容

| 路径 | 内容 |
| --- | --- |
| `android/` | App、单元测试、真机测试及随包模型 |
| `ml/` | 数据抓取、完整性检查、训练、导出和诊断脚本 |
| `tools/` | MIUI 调试安装辅助脚本 |
| `docs/` | 历史验证记录、验收结果和可公开的回归探针 |
| `DEVELOPMENT_PLAN.md` | 原始开发计划与实现方案 |

随包分类模型包含 51 个目标鸟种及 background 类；检测模型为 MediaPipe EfficientDet-Lite2。训练说明见 [ml/README.md](ml/README.md)。训练数据、训练中间产物、个人拍摄照片、设备截图和原始日志仅保留本地；公开的验收报告中引用这些资料时会明确说明未随仓库发布。

连接设备时使用 `adb devices -l` 返回的序列号，通过命令参数或 `ANDROID_SERIAL` 传给安装工具。训练脚本通过 `PY` 选择解释器，不依赖开发者个人目录。

公开提交前的检查范围与处理结果见 [发布检查记录](docs/PUBLICATION_CHECK.md)。
