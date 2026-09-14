# 模型与训练数据许可

根目录的 [LICENSE](LICENSE)（Apache-2.0）**只覆盖代码**。模型权重与训练数据是可单独分发的产物，
许可状态不同，单独说明如下。

## 随包模型

| 文件 | 来源 | 许可 |
|---|---|---|
| `bird_detector.tflite` | MediaPipe 官方 EfficientDet-Lite2 float16，取 COCO 的 bird 类 | Apache-2.0 |
| `bird_classifier.tflite` | 本项目在 iNaturalist 照片上迁移训练的 MobileNetV3-Small | 见下 |
| `species.json` | 由 `ml/export_assets.py` 生成，类别顺序来自 `ml/common_birds.py` | GPL-3.0（随代码） |

各文件的 SHA-256 与下载来源见 [docs/MODELS.md](docs/MODELS.md)。

## 分类模型权重

模型由 `ml/fetch_and_train.sh` 训练，训练语料是 iNaturalist 上按 CC 许可发布的照片，
抓取脚本只收 **CC0 / CC-BY / CC-BY-SA**（见 `ml/fetch_inat.py`）。

本项目主张：权重按与代码相同的 **Apache-2.0** 提供，便于随应用一起分发。

需要如实提示的未决风险 —— **相比代码采用 GPL-3.0 时，这一项反而更需要注意**：

1. **CC BY-SA 的传染性**：若训练语料中含有 CC BY-SA 授权的照片，其"演绎作品"是否及于模型权重，
   目前法律上没有定论。**CC BY-SA 4.0 明文允许单向升级为 GPL-3.0，但没有为 Apache-2.0 开同样的口子。**
   也就是说，如果认定权重属于语料的演绎作品，Apache-2.0 就无法满足 ShareAlike 义务。
   可选的处置：
   - 把权重单独改为 **CC BY-SA 4.0** 或 **CC BY 4.0** 授权（模型权重用 CC 很常见）；或
   - 训练时只保留 **CC0 / CC-BY** 语料，把 BY-SA 排除掉；或
   - 核实 `ml/` 抓取记录里每条的 CC 版本（目前没有保存版本号），再决定。
2. **CC BY-NC 已被排除**：抓取脚本刻意只收允许商用的许可，NC 语料不进入训练集。
   Apache-2.0 允许商用，与 NC 语料会直接冲突，所以这条筛选不要放宽。

## 训练数据本身不随包发布

`ml/data`、`ml/data2` 与中间权重仅保留在开发者本地，不进仓库。仓库内的
`android/app/src/androidTest/assets/sample-attributions.json` 只覆盖 36 张测试样本，
**不是完整训练集的署名清单**。训练集的逐张来源记录需向维护者索取。

## 完整训练资料

如需复现模型，运行 `ml/fetch_and_train.sh`（会重新抓取并重新训练）。
导出的产物与 `docs/MODELS.md` 中记录的 SHA-256 可能因数据版本不同而变化。
