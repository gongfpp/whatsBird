# whatsBird 整改复验

2026-09-14 16:45，复验 Work Buddy 本次修改后的源码和实际 APK。**结论：不通过。两项旧缺陷已经修复，但新增的统计日志错误会让真实相机扫描闪退；模型故障时的拍照降级仍不完整，识别质量和数据独立性也没有改善。** 应先修复闪退，再继续功能验收。

本轮构建当前源码，在 Redmi Note 8 Pro / Android 10 上覆盖安装并保留应用数据。设备上的应用 APK、测试 APK 与本地构建 SHA-256 均一致，应用 APK 为 `aee8e025ca8059f90930a54877269fbe984c2f7980dbda86295ef7902d4a8dec`。未修改业务代码、模型、数据集或用户设置；本次仅新增本报告和复验证据。核对的 46 个业务源码、资源、模型及 ML 脚本在验收期间没有变化。

| 项目 | 本次结果 | 判断 |
| --- | --- | --- |
| Debug APK、测试 APK 构建 | 成功 | 通过 |
| 项目自带单元测试 | 强制重跑 28/28 通过，含新增 3 项恢复和统计测试 | 通过，但未覆盖下面的故障分支 |
| Lint | 0 Error、35 Warning；恢复 `abortOnError=true` | 上次阻断问题已修复 |
| 上次两项缺陷探针 | 2/2 通过：漏检后释放分类入队标志；零检测完成不再虚报检测 FPS | 已修复 |
| 本次三项补充探针 | 3/3 失败：定时统计日志、模型加载失败后的快门、模型不可用的结果状态 | 已复现问题 |
| 真机仪器化测试 | 7 项中 6 项通过，完整鸟帧处理测试因日志异常失败 | 不通过 |
| 实际打开相机 | `wb-frames` 线程出现致命异常，应用进程退出 | 不通过，阻断本版本拍照和持续扫描复验 |
| 分类与检测样本 | 分类 top-1 14/30；6 张背景/清单外样本中 2 张达到命名门限；鸟照出框 25/30 | 与上次相同，尚无识别质量改善 |

**P1：新增统计日志导致真实扫描闪退，必须优先修复。** [BirdPipeline.kt:444](../android/app/src/main/java/com/whatsbird/pipeline/BirdPipeline.kt#L444) 将日志模板拆成两个字符串相加，却只对第二段调用 `.format(...)`。第二段中的 `%d` 因此收到 `detectionFps` 这个 Float，抛出 `IllegalFormatConversionException: d != java.lang.Float`。本次既在真机完整链路测试中复现，也在正常打开应用后复现：设备时间 16:43:44.213，`AndroidRuntime` 记录 `FATAL EXCEPTION: wb-frames`，调用链为 `FrameAnalyzer.analyze → BirdPipeline.submitFrame → updateStats`，随后应用进程退出。修复应对完整模板统一格式化，或改用不会错位的字符串插值，并加入实际触发日志分支的回归测试。现有计数单测只推进到约 2 秒，未进入日志间隔为 5 秒的分支，不能发现此问题；这不表示真机一定要打开满 5 秒才会闪退，因为传入的是系统运行时间。

**P2：模型加载失败仍会禁用快门，普通拍照降级没有接通。** [ScanViewModel.kt:224](../android/app/src/main/java/com/whatsbird/ui/ScanViewModel.kt#L224) 的 `newDetector == null` 分支仍然执行 `capture = null` 后返回，未调用已经放宽条件的 `refreshCaptureHandler()`；模型重建入口也会先清空 capture。相机先完成绑定、随后模型加载失败时，已有拍照处理器会被抹掉，点击快门只返回 `FAILED / model unavailable`。本次隔离分支探针实际验证了这个调用顺序和结果。修复应让模型加载、重建和失败状态都保留可用的原图拍照通路，覆盖“先绑定相机后失败”和“先失败后绑定相机”两种顺序，以及 GPU 切换时的重建。该故障尚未向真机注入，证据是源码与 JVM 分支探针。

**P2：保存空标注副本会掩盖模型故障。** [PhotoCapture.kt:132](../android/app/src/main/java/com/whatsbird/capture/PhotoCapture.kt#L132) 收到 `modelUnavailable=true` 后仍继续渲染并保存没有标注的 `_labeled.jpg`；[PhotoCapture.kt:192](../android/app/src/main/java/com/whatsbird/capture/PhotoCapture.kt#L192) 又将状态计算成 `modelUnavailable && labeled == null`。只要副本写入成功，故障就被清成 false，界面会显示“两份已保存”，而非模型不可用。本次通过 `resolve()` 分支探针复现了该状态丢失。建议分别返回原图保存、识别处理、标签副本三种状态；模型不可用时保存原图并明确标注失败，不能以成功写入一个空副本代表识别完成。“仅标签图”模式还需要确保模型故障时能回退保存原图。

同一问题还存在于同步推理失败路径：[BirdDetector.kt:134](../android/app/src/main/java/com/whatsbird/detect/BirdDetector.kt#L134) 仍把异常转换为空检测列表，分类器也会把推理异常转换为空候选。上层无法区分“模型正常运行但没有找到鸟”和“识别过程失败”。新增 `modelUnavailable` 目前只识别 detector / pipeline 为 null 的情况，需要一并覆盖推理期错误。

**P1：识别质量和测试集独立性仍未通过。** 随包分类模型、检测模型和物种字典 SHA-256 均与上轮完全相同。本次真机重跑同一组小样本，目标鸟种 top-1 仍是 14/30（46.7%）；默认 0.55 门限下，6 张背景/清单外样本仍有 2 张被命名。这是分类器样本测试，未包含实时投票，不能等同野外准确率。当前默认门限说明仍引用 `data2` 的 1,612 张测试图，而本次重新核对 manifest，其中 570 个源照片 ID 已出现在 `data` 训练集，涉及相同观察事件的测试记录有 578 条；同源照片被分配多种标签的数量仍为 data 87 张、data2 170 张。out4 的记录也仍注明从 `out2/best.keras` 继续训练，不能通过更换目录或微调消除训练历史。相关位置：[AppSettings.kt:40](../android/app/src/main/java/com/whatsbird/settings/AppSettings.kt#L40)、out4 训练来源（本地资料 `ml/out4/train_metrics.json`，未公开）。

下一步应先固定跨数据版本、跨模型训练来源的样本划分，清理同图异标签，使用独立验证集选门限，再以未参与训练或调参的测试集验收实际检测框裁剪、实时投票及照片镜像平均三条路径。上轮 821 张清理后旧测试记录的离线结果仍可作参考，本次未重复计算；详见[上轮报告](ACCEPTANCE_REVIEW_2026-09-14.md)。不能用本次通过的分类器“能运行”测试代替识别精度门槛。

其余整改有实质进展：异步检测错误回调已清理在途状态，并接入带 10 秒限流的模型重建；`MediaStoreSaver.finish()` 已检查解除 `IS_PENDING` 的影响行数，失败会进入清理和失败返回；设置页源码已加入“保守／平衡／更多候选”和高级设置，并删除“清单外不会猜”的保证。这些属于源码确认；本次没有注入真机底层检测或存储故障，也没有因扫描闪退而完成新版设置页的完整交互和拍照复验，不能宣称端到端已通过。

性能统计的计数方向已正确，但仍需调整口径：`previewFps` 实际统计进入识别管道的帧数，不是屏幕渲染帧率；`firstStableNameMs` 从第一次检测回调起算，即使当时没有鸟也开始计时，并且只记会话中的第一次稳定种名。应分别记录真实预览帧率、每只鸟从首次可见到稳定命名的延迟 P50/P95，以及同屏多鸟时的更新间隔。本次静态 1280×960 检测约 CPU 555.4 ms、GPU 189.2 ms，分类约 13.8 ms/裁剪；这些不代表完整相机的持续帧率。

建议修复顺序：日志闪退 → 模型故障下快门与保存状态 → 独立数据与识别质量 → 多鸟跟随和持续运行。下一次验收至少应包含：本次 5 项探针全部通过、现有 7 项真机测试全部通过、当前 APK 实际拍照并在相册打开、真实移动多鸟视频、15 分钟持续扫描。原始“鸟头上标签”目前仍是鸟框上方的标签，没有头部关键点定位；应在多鸟交叉、遮挡和出入画面时检查标签是否跟错对象或长时间滞留。

证据见[复验目录](acceptance/2026-09-14-recheck)，其中 [summary.json](acceptance/2026-09-14-recheck/summary.json) 汇总版本与结果，device-tests.txt（本地资料 `docs/acceptance/2026-09-14-recheck/device-tests.txt`，未公开） 为真机测试输出，device-metrics.txt（本地资料 `docs/acceptance/2026-09-14-recheck/device-metrics.txt`，未公开） 含实际闪退堆栈。复验探针通过临时 sourceSet 加载，未加入业务测试源目录；测试结束后已恢复并重跑项目自带 28 项测试，全部通过。

从项目根目录重跑补充探针（当前预期 2 通过、3 失败）：

```sh
./android/gradlew -p android -I docs/acceptance/2026-09-14-recheck/recheck.init.gradle :app:testDebugUnitTest --tests 'com.whatsbird.audit.*'
```

探针用反射隔离特定失败分支，不启动 JVM 内无法代表真机的原生模型或相机；其中快门探针的测试夹具仅用于证明当前失败路径，在实现调整后应升级为完整的相机绑定／模型加载顺序测试。常规单测可用不含 `-I` 和 `--tests` 的 `:app:testDebugUnitTest` 命令恢复。
