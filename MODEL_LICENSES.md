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

**分类模型权重采用 CC BY 4.0。**

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
**没有给 CC BY 或 Apache-2.0 开口子**。所以只要训练语料里混有 BY-SA 照片，
"权重用 CC BY 4.0" 在严格解释下就站不住。

处置：`ml/fetch_inat.py` 的 `DEFAULT_LICENSES` 已默认**排除 cc-by-sa**，只收 CC0 / CC-BY。
这样**今后每次重训都是干净的**；确实需要 BY-SA 语料时再显式 `--licenses` 指定，
同时把权重许可改成 CC BY-SA 4.0。

当前随包的这一版权重是在改动默认筛选**之前**抓的语料上训出来的，语料里确实含 CC BY-SA 照片。
这属于已知的残留风险，下一次重训（用新的默认筛选）即可消除。

### 另一条约束：CC BY-NC 已被排除

抓取脚本刻意只收允许商用的许可，NC 语料不进入训练集。CC BY 4.0 允许商用，
与 NC 语料会直接冲突，所以这条筛选不要放宽。

## 训练数据本身不随包发布

`ml/data`、`ml/data2` 与中间权重仅保留在开发者本地，不进仓库。仓库内的
`android/app/src/androidTest/assets/sample-attributions.json` 只覆盖 36 张测试样本，
**不是完整训练集的署名清单**。训练集的逐张来源记录需向维护者索取。

## 完整训练资料

如需复现模型，运行 `ml/fetch_and_train.sh`（会重新抓取并重新训练）。
导出的产物与 `docs/MODELS.md` 中记录的 SHA-256 可能因数据版本不同而变化。
