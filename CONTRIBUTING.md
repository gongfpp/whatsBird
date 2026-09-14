# 贡献指南

感谢愿意动手。本项目是个人维护的离线鸟类识别 App，改动请尽量小、可验证。

## 许可

本项目采用 **Apache License 2.0**（见 [LICENSE](LICENSE)）。提交 PR 即表示你同意你的贡献同样以
Apache-2.0 授权。代码之外的部分另算：模型权重见 [MODEL_LICENSES.md](MODEL_LICENSES.md)，
第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

新增源文件请在文件头加上与 `LICENSE` 附录一致的声明（版权行里带仓库链接，
这样他人再分发时出处会跟着被保留）：

```kotlin
/*
 * Copyright 2026 gongfpp (https://github.com/gongfpp/whatsBird)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
```

如果你修改的不是自己的文件，**不要删掉或修改原有的版权行**——Apache-2.0 第 4 条要求保留
这些声明。

## 环境

- JDK 21（Gradle 8.14.3 / AGP 8.13.2）
- Android SDK，`minSdk 29`
- macOS 上如果 `JAVA_HOME` 没被 shell 读到，显式指定：
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

## 提交前必过

```bash
cd android
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

**`BUILD SUCCESSFUL` 不等于没问题。** `lintDebug` 的报告要真正读一遍，确认没有
`Call requires API level N (current min is 29)` 这类条目——`abortOnError` 已恢复为 `true`，
但警告级问题仍会漏过去。

## 几个已经踩过的坑

**`minSdk` 是 29，而 `compileSdk` 更高。** 编译通过不代表设备能跑；用了高版本 API 的表现是
真机上 `NoSuchMethodError` / `NoClassDefFoundError`。已知不可用：`PointF(PointF)`（API 35）、
`Thread#threadId()`（API 36）。拷贝 `PointF` 写 `PointF(p.x, p.y)`。

**不要引入网络权限。** 应用在 manifest 层用 `tools:node="remove"` 强制剔除
`INTERNET` 与 `ACCESS_NETWORK_STATE`。升级依赖后请重新 grep 一次合并后的 manifest，
新库可能把它们带回来。加网络权限属于安全相关变更，必须在 PR 里显式说明。

**不要用 `exclude` 精简 MediaPipe。** `tasks-core` 创建 task 时真的引用
`TransportRuntime`，实测 `exclude(group = "com.google.android.datatransport")` 会让真机直接
`NoClassDefFoundError`，pipeline 永远到不了 READY。离线只在 manifest 层保证。

**改了数据或模型前后跑一次完整性检查。** `ml/dataset_integrity.py --data <dir>`，
修用 `--apply`。抓取脚本可续跑，中断重跑会把两轮切分混在一起，模型会训练自己的测试集。

## 需要真机验证的改动

涉及相机、模型加载、识别链路的改动，请在真机上跑一次。真机是 Redmi Note 8 Pro（Android 10）。

```bash
adb connect <设备IP>:5555
# 装包走这个脚本，MIUI 的 USB 安装确认弹窗会导致 adb install 超时失败
python3 tools/accept-usb-install.py android/app/build/outputs/apk/debug/app-debug.apk <serial>
```

排查崩溃：`adb logcat -b crash -d -v threadtime`，以及
`adb shell run-as com.whatsbird.debug ls files/crashes`。

## 提交信息

用 Conventional Commits：`feat:` / `fix:` / `chore:` / `docs:` / `refactor:` / `test:`。
一个 PR 只做一件事，描述里写清**为什么**改，以及你是怎么验证的。
