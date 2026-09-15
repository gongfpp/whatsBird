# 随包模型与测试图片来源

当前源码使用以下资产；文件身份用于复现，不代表模型质量已经达到正式发布标准。

| 资产 | 来源与用途 | SHA-256 |
| --- | --- | --- |
| `bird_detector.tflite` | MediaPipe 官方发布的 EfficientDet-Lite2 float16，检测 COCO 中的 bird 类 | `5d4ebec1029bc9907aeadb9e7b4ac9cb1da6a19d01ad375210a9ae18ba173302` |
| `bird_classifier.tflite` | 本项目训练并导出的 MobileNetV3-Small v2 float16，51 个目标鸟种及 background | `1a207e39023f686a1e76f1129800fe73972b5d4b4925267d5b88d534e193060f` |
| `species.json` | 模型输入配置、物种名称与类别顺序 | `0c8216507c668729011c9e0882625d26b2ff0bf7ef260834f2139e1c95487774` |

检测模型的原始下载地址见 [ML 说明](../ml/README.md)，上游为 [Google MediaPipe 模型文件](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float16/1/efficientdet_lite2.tflite)。训练脚本使用 iNaturalist 照片，并保留各照片的来源和许可记录。完整训练资料和中间权重仅保留在开发者本地。

真机测试包含 36 张公开来源的 iNaturalist 小尺寸样本。逐张来源、作者署名和许可记录见 [sample-attributions.json](../android/app/src/androidTest/assets/sample-attributions.json)。这些图片不是用户手机拍摄照片，仓库中的测试副本不包含 EXIF 数据。

当前分类模型仍属于开发阶段模型，**不是 `v1.0.0` 发布候选**：训练语料含 CC BY-SA 照片（许可链未闭环，
见 [MODEL_LICENSES.md](../MODEL_LICENSES.md)），ImageNet 预训练底座的 provenance 记录也尚未随权重固化。
历史训练结果和仓库内测试样本只能用于开发回归，不应被视为独立测试集上的正式准确率证明。
模型质量评估应使用与训练、调参和模型选择过程相互独立的数据集；每次重训的来源链
（git 提交、manifest 哈希、训练/验证/测试集的照片与观察 id、基础 checkpoint 及其哈希）
由 `ml/train.py` 写入产物目录的 `training_provenance.json`。发布前重训完成后，本表与
许可状态需同步更新。
