# 随包模型与测试图片来源

当前源码使用以下资产；文件身份用于复现，不代表模型质量已经通过验收。

| 资产 | 来源与用途 | SHA-256 |
| --- | --- | --- |
| `bird_detector.tflite` | MediaPipe 官方发布的 EfficientDet-Lite2 float16，检测 COCO 中的 bird 类 | `5d4ebec1029bc9907aeadb9e7b4ac9cb1da6a19d01ad375210a9ae18ba173302` |
| `bird_classifier.tflite` | 本项目训练并导出的 MobileNetV3-Small v2 float16，51 个目标鸟种及 background | `1a207e39023f686a1e76f1129800fe73972b5d4b4925267d5b88d534e193060f` |
| `species.json` | 模型输入配置、物种名称与类别顺序 | `0c8216507c668729011c9e0882625d26b2ff0bf7ef260834f2139e1c95487774` |

检测模型的原始下载地址见 [ML 说明](../ml/README.md)，上游为 [Google MediaPipe 模型文件](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float16/1/efficientdet_lite2.tflite)。训练脚本使用 iNaturalist 照片及各照片的来源和许可记录。完整训练资料和中间权重仅保留在开发者本地。

真机测试包含 36 张公开来源的 iNaturalist 小尺寸样本。逐张来源、作者署名和许可记录已从本地 manifest 按 SHA-256 匹配提取至 [sample-attributions.json](../android/app/src/androidTest/assets/sample-attributions.json)，没有改变图片字节。它们不是用户手机拍摄的照片；36 张均不含 EXIF 数据。具体许可及来源以对应记录和来源页面为准。

当前模型存在识别精度不足、同源图异标签和跨版本测试集与训练资料重叠问题；详见 [整改复验报告](ACCEPTANCE_RECHECK_2026-09-14.md)。历史训练数值不能作为模型达到发布标准的证明。
