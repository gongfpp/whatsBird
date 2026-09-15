# 安全策略

## 支持范围

| 版本 | 是否修复 |
|---|---|
| `main` 分支最新提交 | 是 |
| 已发布的 APK | 视情况，通常只修当前源码 |

## 报告方式

**请不要开公开 issue 报告安全漏洞。** 请通过仓库的 Security Advisories 私密上报，
或直接联系维护者。

请在报告中包含：

- 复现步骤（含设备型号与 Android 版本）
- 影响面（本地提权 / 隐私泄露 / 拒绝服务等）
- 如有可能，附 `adb logcat` 或 `files/crashes/` 下的崩溃记录

## 本项目的攻击面特点

- **应用不申请 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限**，在 `AndroidManifest.xml` 中以
  `tools:node="remove"` 强制剔除，识别全部在本地完成，照片与识别结果不上传。
  任何重新引入网络权限的改动都应被视为安全相关变更并在 PR 中显式说明。
- 拍摄的照片写入**公开系统相册**（`MediaStore.Images`，相对路径 `Pictures/识鸟/`），因此在
  Android 10+ 上无需申请存储权限。照片保存在用户可见的相册中而不是应用私有目录——这是一次
  刻意的产品选择，但意味着照片与其它相册内容享有同样的可见性；除该目录外 App 不读写任何用户媒体。
  `SECURITY.md` 与相册路径的对应关系如有变更需同步更新本文档。
- 未捕获异常由 `util/CrashLog` 落到 `filesDir/crashes/`，**该目录不参与云备份与设备迁移**
  （Android 12+ 见 `res/xml/data_extraction_rules.xml`；Android 10/11 见
  `res/xml/backup_rules.xml` 与 manifest 中的 `android:fullBackupContent`）。

## 提交前的敏感信息检查

仓库根目录有 `.gitleaks.toml`。提交前请跑一次扫描，避免密钥、签名材料、设备日志、
个人照片进入版本库。签名用的 keystore 与口令**永不入库**，通过 `WHATSBIRD_*`
环境变量或 `~/.gradle/gradle.properties` 提供。
