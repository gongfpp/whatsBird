# ml — 鸟种分类模型流水线

这个目录负责产出 App 真正随包发布的那个模型文件，以及与之配套的 `species.json`。
App 不训练、不下载、不联网，只读取这里导出的产物。

## 为什么需要自己训一个

先用现成模型做过可行性检查，结论是不合用。Google 的 iNaturalist 鸟类分类器
（`mobilenet_v2_1.0_224_inat_bird_quant`，964 种）在中国大陆最常见的鸟上覆盖只有一半左右，
而且缺的正好是最高频的几种：**白头鹎、珠颈斑鸠、乌鸫、八哥、灰喜鹊全都不在词表里**。
对一个"对着小区树上的鸟拍一张"的 App 来说，识别不出最常见的鸟等于不可用。

所以本目录用 iNaturalist 的 CC 许可照片，针对中国大陆常见鸟种清单做迁移训练。
详见 [随包模型与测试图片来源](../docs/MODELS.md)。当前模型的质量限制以 [整改复验报告](../docs/ACCEPTANCE_RECHECK_2026-09-14.md) 为准，以下训练记录保留作历史参考。

## 流程

```bash
# 0. 激活已安装 TensorFlow 的训练环境，再选择 Python 解释器
PY=${PY:-python3}

# 1. 先探数据量：哪些鸟种照片够、哪些不够
$PY probe_inat.py

# 2. 抓数据（按观察事件切分 train/val/test，只收 CC0/CC-BY）
#    background 的份量要跟着目标数据一起涨，否则模型会变得爱猜种名——见「数据翻倍之后」一节
$PY fetch_inat.py --out data --per-species 150 --background 600

# 2.5 抓取被中断过就必须查切分完整性，否则训练出来的数字是泄漏（见下节）
$PY dataset_integrity.py --data data --apply

# 3. 迁移训练（MobileNetV3-Small，两阶段）
$PY train.py --data data --out out

# 4. 导出 TFLite、评测、并把产物装进 App
$PY export_assets.py --data data --out out --android ../android/app/src/main/assets/models
```

产物：

| 文件 | 说明 |
| --- | --- |
| `out/model.keras` | 训练好的 Keras 模型 |
| `out/classifier_int8.tflite` | int8 量化版，随包发布 |
| `out/classifier_float32.tflite` | 浮点版，作为量化精度损失的对照 |
| `out/eval_report.json` | 测试集指标，机读 |
| `out/MODEL_CARD.md` | 模型卡，人读，含各类召回与已知限制 |
| `../android/app/src/main/assets/models/{bird_classifier.tflite,species.json}` | App 实际加载的文件 |

`species.json` 由 `export_assets.py` 生成，类别顺序直接来自 `common_birds.py`，
所以改清单顺序会让已有模型失效——这也是把它放在同一处生成的原因。

## 数据切分完整性（训练前必查）

`train.py` 用 `image_dataset_from_directory` 读**目录**，而目录归属是由抓到一半就能被杀死的
`fetch_inat.py` 决定的。抓取可续跑（`download()` 跳过已存在文件），所以**中断后重跑会把两次运行的
切分混在同一个目录树里**：一张照片可能躺在 `train/`（上一轮给的），而本轮 manifest 把它判给了
`test/`。模型于是拿测试集训练自己，跑出来的每个数字都是假的。

这不是假设。`data2` 第一次抓取在 background 阶段被杀，续跑后：

```
manifest: 16256 photos test=1601, train=13059, val=1596
[train] on disk 16632 | orphans 1290 | misplaced 2283   (test=1121, val=1162)  <-- 泄漏
[val]   on disk 3225  | orphans 263  | misplaced 1366   (train=1217, test=149) <-- 泄漏
[test]  on disk 3165  | orphans 277  | misplaced 1287   (train=1157, val=130) <-- 泄漏
```

那次运行报的 val accuracy **0.778 是泄漏，不是学习**（val 里 2962 张有 1217 张是训练图）。
症状很好认：第 1 个 epoch 就 0.778，之后 14 个 epoch **一次都没涨过**。

```bash
# 只报告
$PY dataset_integrity.py --data data2
# 修复（移入 data2/quarantine/ 并同步改写 manifest，可逆，不是删除）
$PY dataset_integrity.py --data data2 --apply
```

审计**双向**进行，两个方向都会让结果作废：磁盘上有 manifest 不认的文件（孤儿/错位），
以及 manifest 列了但磁盘上没有的文件。后者看起来无害，实际上会让所有按 manifest 取图的脚本
直接崩——`export_assets.py` 与 `sweep_threshold.py` 都报
`NotFoundError: .../images/test/Turdus eunomus/108693031.jpg; No such file or directory`。
所以 `--apply` 不只是移动文件，它还会把 manifest 改写成「恰好列出磁盘上存在的文件」；
否则修完一半，数据集反而从「数字虚高」变成「跑不起来」。

`train.py` 现在**无条件**在建立数据集之前做同样的检查，不一致就直接拒绝训练并打印修复命令
（要强行跑得显式加 `--allow-split-mismatch`）。`fetch_inat.py` 结束时的清理也从
`os.remove()` 改成移动到 `quarantine/`——删除不可逆，而且上千个文件的批量删除正好会撞上
安全删除护栏，于是**抓取报成功、目录却还是脏的**（上次就是这样）。

判定的依据是 manifest，它是「这张照片属于哪个 split」的唯一权威；文件名就是 iNaturalist 的
photo id，所以同一张源图无论出现在哪个目录都能被认出来。另外 `fetch_inat.py` 现在按 photo_id
**全局去重**：近缘种会互相 cross-list 照片，同一张图此前既可能进 `train/` 又进 `test/`，
也可能在 `train/` 里挂着两个种名。

## 定位「识别不准」

只拿到一张照片和一句「识别效果很差」时，先用 `diagnose_photo.py` 把它拆成可操作的一类。
它加载的是 App 随包的那两个模型、走的是 App 完全相同的预处理，所以打印出来的就是 App 的结论：

```bash
$PY diagnose_photo.py --threshold 0.60 --margin 0.15 photo.jpg
```

每个检测框输出 top-5（含中文名）与 App 会给出的判定。四种失败长着同一张脸，只有这样才能分开：

| 现象 | 含义 | 修法 |
| --- | --- | --- |
| 检测器没找到鸟 | 检测问题 | 换检测档位 / 降检测阈值 |
| 裁剪被拒（打印了像素尺寸） | 管道缺陷 | `BitmapOps.crop` 的 24 px 下限 |
| top-1 是对的种但分数低于门限 | 操作点 | 调 `--threshold`，见下 |
| 分数过门限但与第二名差距不够 | 操作点 | 调 `--margin` |
| top-1 判错 | 模型 | 只能补数据重训 |

真机上的等价物是 `PhotoDiagnosisOnDeviceTest`（把照片推到
`/sdcard/Android/data/com.whatsbird.debug/files/diagnose/` 后跑该测试）。macOS 的 MediaPipe
wheel 一加载检测模型就 abort 在 `TensorsToDetectionsCalculator` 的 Metal 图服务上，headless
进程拿不到这个服务，所以本机只能让设备来跑。

## 操作点怎么定

显示门限与静态照片的差距门限**都不靠手感**，由 `sweep_threshold.py` 在冻结测试集上量出来：

```bash
# 完整跑一次（慢，约一两分钟）
$PY sweep_threshold.py --model out2/classifier_float16.tflite --data data \
  --json-out out2/threshold_sweep.json --grid --rows-out /tmp/rows.json

# 换个约束条件重新制表（秒级，不重跑推理）
$PY sweep_threshold.py --rows-in /tmp/rows.json --min-precision 0.75 --grid
```

`--grid` 打印「门限 × 差距门限」的完整二维面（覆盖率/精确率）。`--rows-in/--rows-out` 把逐图结果
缓存下来，因为推理是唯一慢的部分，而选操作点要反复换约束看表。

两个已知事实（2026-09-14，v2 模型，829 张测试集）：

- **门限 ≥ 0.55 时差距门限完全不生效**，所以出厂门限下 `STILL_MARGIN` 是零成本的安全网，只在用户
  把门限滑到 0.50 以下时才起作用；
- 在「精确率 ≥ 80% 且清单外误报 ≤ 15%」的约束下扫描推荐 **0.65**，而 App 出厂用 **0.60**。
  0.60 多给 3.6 个百分点的覆盖率，代价是已显示种名的正确率从 82.7% 降到 79.8%。
  这是政策选择，不是对错问题。

## 检测模型（不训练，直接取官方发布件）

分类器是本目录训出来的，**检测器不是**：它用的是 MediaPipe 官方发布的
EfficientDet，直接下载放进 `assets/models/bird_detector.tflite`。

```sh
# 当前随包的是这一档（12,138,859 B）
curl -sL -o ../android/app/src/main/assets/models/bird_detector.tflite \
  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float16/1/efficientdet_lite2.tflite
```

官方还发布 `efficientdet_lite0/float32`（13,836,895 B，输入 320×320）与
`efficientdet_lite2/int8`、`efficientdet_lite2/float32`。真机同条件对比记录在
`../docs/ON_DEVICE_VERIFICATION.md`：Lite0@320 的留出集出框率 73.3%、Lite2@448 为 83.3%，
且 Lite2 的文件反而更小。**换这一档模型必须复测检测延迟**——Lite2 比 Lite0 慢一倍多，
GPU 委托下才落在 8 次/秒的节流预算内。

模型自带 COCO 标签表（存在 TFLite metadata 里），所以 `bird` 这个类是按名字过滤的，
没有用 `setCategoryAllowlist`——见 `BirdDetector` 的注释，那个写法会让进程 abort。

## 数据翻倍之后：为什么没有直接换掉线上模型（2026-09-14）

`data2`（51 种 × 320 + background）训出的 `out4` 与前代 `out2` 在同一张 **1612 张冻结测试集**上：

| 指标 | out2（6.4k 训练图） | out4（13.3k 训练图） |
| --- | --- | --- |
| top-1 准确率 | 0.605 | **0.616** |
| 宏平均召回（排除 background） | 0.6138 | 0.6206 |
| 门限 0.60 的覆盖率 | **47.7%** | 47.1% |
| 门限 0.60 已命名者正确率 | 88.2% | **88.9%** |
| 门限 0.60 清单外鸟被取名 | **4/66 = 6.1%** | 14/66 = **21.2%** |

结论是**没有赢家**：准确率 +1.1pp，但覆盖率持平甚至略跌，而「清单外鸟被戴上已知种名」的误报
翻了三倍。对「对着随机一只鸟拍」的用法来说，最后一列恰恰是最刺眼的失败——宁可显示「鸟类」，
不要自信地报错名字。逐类看也是涨跌互现（`Turdus mandarinus` +20.7pp、`Phoenicurus auroreus`
+18.8pp，而 `Streptopelia orientalis` −28.1pp、`Corvus macrorhynchos` −21.9pp）。

**机制**：目标类照片翻倍，background 预算没跟着涨（train 372 → 502 张），于是 background 占比
5.8% → 3.8%，目标:背景 16.4:1 → 25.5:1。`class_weights` 是反频率的，本应补偿，但补偿后的**有效**
强调度比也一起漂了：

```
data : bg 权重 0.334 / 目标 1.036 = 2.75×
data2: bg 权重 0.510 / 目标 0.999 = 1.96×   ← 背景被相对弱化了 29%
```

所以下一步不是「再抓更多目标数据」，而是**把背景的份量与多样性补回来**，两个选择：
抓更多 DISTRACTOR_BIRDS 照片（让 train 里 background ≈ 6%，按 12803 目标图需 ~780 张），
或在训练侧把 background 的权重乘回 ~1.4×。前者更彻底（反频率权重补不了多样性不足），后者免费可试。

在此之前，线上仍用 `out2`。

## 类别顺序

输出向量是 `[51 个目标鸟种…, background]`，`background` 固定在最后一个索引。
训练时 `background` 类由两类样本填充：**清单外的鸟**（`DISTRACTOR_BIRDS`）和
**非鸟生物**（`NON_BIRD_TAXA`）。前者是关键——没有它，模型学到的是"永远从 51 个里挑一个"，
而这正是野外识别最糟糕的失败模式。

## 许可

默认只使用 `cc0` / `cc-by` 两种照片许可。**`cc-by-sa` 被刻意排除**：CC BY-SA 4.0 只允许
演绎作品升级到 BY-SA 4.0 或 GPL-3.0 这类兼容许可，不能升到 CC BY /
Apache-2.0，所以语料里一旦混入 BY-SA 照片，"权重按 CC BY 4.0 发布"就站不住。
详见 [MODEL_LICENSES.md](../MODEL_LICENSES.md)。

`--licenses` 可以放开，但发布前需要重新评估权重许可。每张图的来源、许可与署名都落在
`data/manifest.jsonl`，可逐张追溯。
