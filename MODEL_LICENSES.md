# 模型与训练数据许可

根目录的 [LICENSE](LICENSE)（Apache-2.0）**只覆盖代码**。模型权重与训练数据是可单独分发的产物，
许可状态不同，单独说明如下。

## 随包模型

| 文件 | 来源 | 许可 |
|---|---|---|
| `bird_detector.tflite` | MediaPipe 官方 EfficientDet-Lite2 float16，取 COCO 的 bird 类 | Apache-2.0 |
| `bird_classifier.tflite` | 本项目在 iNaturalist 照片上迁移训练的 MobileNetV3-Small | 见下 |
| `species.json` | 由 `ml/export_assets.py` 生成，类别顺序来自 `ml/common_birds.py` | Apache-2.0（随代码） |

各文件的 SHA-256 与下载来源见 [docs/MODELS.md](docs/MODELS.md)。

## 分类模型权重

模型由 `ml/fetch_and_train.sh` 训练，训练语料是 iNaturalist 上按 CC 许可发布的照片。

> **发布状态（2026-09-15）：当前随包的这版权重不是可发布版本。**
> 两个未闭环问题：
> 1. **语料许可残留**（见下文「硬约束」）——本版权重训练语料含 CC BY-SA 照片，与 CC BY 4.0
>    的声明在保守解释下冲突；
> 2. **基础 checkpoint 来源**（见下文「上游预训练权重」）——ImageNet 预训练底座的许可链
>    尚未完全论证。
> `v1.0.0` 正式发布的前提：用 `ml/fetch_inat.py` 当前默认筛选（仅 CC0/CC-BY）重新抓取并重训，
> 产物携带 `training_provenance.json`，并更新 `docs/MODELS.md` 中的 SHA-256。

### 计划采用的许可：CC BY 4.0

为什么不跟代码一样用 Apache-2.0：权重不是代码，它是否属于训练照片的"演绎作品"在法律上没有定论，
而 CC 系列正是为这类资产设计的。为什么不选 CC0：CC0 是放弃全部权利、别人连署名都不用给，
与本仓库"使用可以，但必须标明出处"的立场冲突。CC BY 4.0 的三条正好对上：

| | CC BY 4.0 |
|---|---|
| 随意修改、再分发 | 允许 |
| 商业使用 | 允许 |
| 必须署名（保留出处） | **要求** |

署名方式：转载或二次分发 `bird_classifier.tflite` 时，请注明来源
`whatsBird — https://github.com/gongfpp/whatsBird`（权重许可 CC BY 4.0），
并保留训练语料的归属记录（`sample-attributions.json` 是测试样本部分，完整训练集的逐条来源见下）。

### 由此产生的一条硬约束：语料里不能有 CC BY-SA

CC BY-SA 4.0 只允许演绎作品升级到 **BY-SA 4.0 或 GPL-3.0 这类 BY-SA 兼容许可**，
**没有给 CC BY 或 Apache-2.0 开口子**（对照 Creative Commons 官方兼容许可列表，CC BY 不在其上）。
所以只要训练语料里混有 BY-SA 照片，"权重用 CC BY 4.0" 在严格解释下就站不住。

处置：`ml/fetch_inat.py` 的 `DEFAULT_LICENSES` 已默认**排除 cc-by-sa**，只收 CC0 / CC-BY。
这样**今后每次重训都是干净的**；确实需要 BY-SA 语料时再显式 `--licenses` 指定，
同时把权重许可改成 CC BY-SA 4.0。

当前随包的这一版权重是在改动默认筛选**之前**抓的语料上训出来的，语料里确实含 CC BY-SA 照片。
这不是靠文档解释就能关闭的风险项——**消除方式只有一次重训**（新语料 + 新权重 + 更新
`docs/MODELS.md` 的 SHA-256 + 淘汰旧权重），重训完成后本节的状态行应改为"已闭环"。

### 另一条约束：CC BY-NC 已被排除

抓取脚本刻意只收允许商用的许可，NC 语料不进入训练集。CC BY 4.0 允许商用，
与 NC 语料会直接冲突，所以这条筛选不要放宽。

## 上游预训练权重（ImageNet checkpoint）

`ml/train.py` 的迁移学习不是从零开始：backbone 加载 Keras Applications 的
MobileNetV3-Small `weights="imagenet"` 预训练 checkpoint。这层来源链此前没有记录，现补齐：

| 项 | 说明 |
|---|---|
| 来源 | Keras Applications 运行时自动从 `storage.googleapis.com/tensorflow/keras-applications/` 下载，缓存于 `~/.keras/models/` |
| 版本与身份 | 具体 checkpoint 文件名与 SHA-256 由 `train.py` 写入 `training_provenance.json` 的 `base_model` / `base_model_hash`，每次训练都会固化 |
| 许可 | Keras（keras-team，含 keras-applications）代码与发行物为 Apache-2.0；MobileNetV3 原始 checkpoint 出自 Google Research 的 MobileNetV3 发布（`tensorflow/models` 系列，Apache-2.0） |
| 数据层说明 | ImageNet ILSVRC-2012 **数据集**本身的访问条款限定非商业研究/教育用途。已发布的 checkpoint 与数据集是两个东西，但"在受限数据集上训练出的权重的再分发边界"在业界并没有一致定论 |

**立场与行动**：

1. 本项目随包的模型是**在 ImageNet 权重之上做参数级微调**的产物，其再分发许可按上表
   以 Apache-2.0 之下的 checkpoint 发行惯例处理；
2. `training_provenance.json` 固化了每次训练实际使用的 checkpoint 身份，任何"悄悄换了
   底座"都可检出；
3. 若后续需要更保守的解释（例如评估认为微调不足以脱离原数据条款），替代路径是**仅用
   CC0/CC-BY 语料从零训练**——成本高，但在许可上完全自洽。该决定应在 `v1.0.0` 发布前作出并
   记录于此。

## 训练数据本身不随包发布

`ml/data`、`ml/data2` 与中间权重仅保留在开发者本地，不进仓库。仓库内的
`android/app/src/androidTest/assets/sample-attributions.json` 只覆盖 36 张测试样本，
**不是完整训练集的署名清单**。训练集的逐张来源记录需向维护者索取。

## 完整训练资料

如需复现模型，运行 `ml/fetch_and_train.sh`（会重新抓取并重新训练）。
导出的产物与 `docs/MODELS.md` 中记录的 SHA-256 可能因数据版本不同而变化。
