# 第三方许可声明

本项目源码采用 **Apache License 2.0**（见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)）。
本文件列出运行时随应用分发的第三方组件及其许可，用于满足 Apache-2.0 第 4 条的署名要求。

最终 APK 内的 `META-INF/LICENSE*` / `META-INF/NOTICE*` 由构建合并进包，是这些组件许可文本的权威副本；
本文件是便于人工查阅的索引。升级依赖后请同步更新。

## 开源组件（可自由再分发）

| 组件 | 版本 | 许可 |
|---|---|---|
| AndroidX（CameraX、Compose、Material3、Lifecycle、Activity、DataStore、ExifInterface、Core、Collection、Annotation） | 见 `android/gradle/libs.versions.toml` | Apache-2.0 |
| Kotlin / Kotlinx Coroutines | 见 `android/gradle/libs.versions.toml` | Apache-2.0 |
| MediaPipe Tasks Vision（`com.google.mediapipe:tasks-vision`） | 0.10.35 | Apache-2.0 |
| LiteRT（`com.google.ai.edge.litert`） | 2.2.0 | Apache-2.0 |
| Firebase Encoders（`firebase-encoders*`） | 18.0.0 / 17.0.0 / 16.0.0 | Apache-2.0 |
| Android Datatransport（`transport-*`） | 3.0.0 / 3.1.0 | Apache-2.0 |
| Guava、failureaccess、listenablefuture | 33.5.0-android 等 | Apache-2.0 |
| Okio | 3.4.0 | Apache-2.0 |
| Protocol Buffers（protobuf-javalite） | 4.26.1 | BSD-3-Clause |
| Error Prone Annotations、AutoValue Annotations、Flogger、j2objc-annotations、jspecify | 见依赖树 | Apache-2.0 |

## 专有组件（非开源）

以下由 MediaPipe `tasks-core` 传递引入，**不是开源软件**：

| 组件 | 版本 | 许可 |
|---|---|---|
| `com.google.android.gms:play-services-basement` | 18.4.0 | Google Play 服务条款 / Android SDK 许可协议 |
| `com.google.android.gms:play-services-tasks` | 18.2.0 | 同上 |
| `com.google.android.play:core-common` | 2.0.4 | 同上 |
| `com.google.android.play:asset-delivery` | 2.3.0 | 同上 |
| `com.google.android.play:ai-delivery` | 0.1.1-alpha01 | 同上 |

Apache-2.0 不限制与专有库组合分发，所以**这与本项目当前的许可不冲突**——这也是选择
Apache-2.0 而非 GPL-3.0 的原因之一（GPL-3.0 下这些库不允许随二进制再分发，apk 会成为无法
合法发布的混合体）。

但接收方需要清楚：**这些组件不在 Apache-2.0 授权范围内**，它们的使用受 Google 的条款约束。
如果要去掉它们，得先让 MediaPipe 不再依赖——`tasks-core` 在创建 task 时确实引用这些类，
无法简单剔除（实测剔除 `com.google.android.datatransport` 后立即
`NoClassDefFoundError: TransportRuntime`，pipeline 到不了 READY）。详见
`android/app/build.gradle.kts` 中的注释。

## 测试图片

36 张测试样本来自 iNaturalist 公开数据，逐条的 photo_id、原始 URL、作者署名与许可记录在
[`android/app/src/androidTest/assets/sample-attributions.json`](android/app/src/androidTest/assets/sample-attributions.json)
（36/36 覆盖；cc-by 27、cc-by-sa 3、cc0 6，其中 30 张需要保留署名）。

**再分发这份源码时必须连该文件一起保留**，否则不满足 CC BY / CC BY-SA 的署名要求。
仓库内的副本已去除 EXIF（仅保留 JFIF 与 ICC 色彩配置段）。

这些图片是**原样随源码聚合分发、未被修改**，不构成演绎作品，因此 CC BY-SA 的相同方式共享义务
不会触发到代码本身；唯一的硬性要求是保留署名。仍需注意：记录里没有保存 CC 版本号，
如果需要严格证明，建议核实每条记录的具体 CC 版本。

## 模型与训练数据

见 [MODEL_LICENSES.md](MODEL_LICENSES.md)。
