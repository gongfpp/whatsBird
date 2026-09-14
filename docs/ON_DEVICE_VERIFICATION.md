# 真机验证记录

本文件为历史开发记录，最新结论以 [整改复验报告](ACCEPTANCE_RECHECK_2026-09-14.md) 为准。本文提及的照片、截图和原始设备日志仅保留本地，未公开。

本文件记录 whatsBird 在真实设备上的验证证据。`docs/MODEL_CARD.md` 的数字来自离线测试集；
本文件回答的是另一个问题：**同一份模型和代码，在手机上是否真的成立**。

## 设备与连接

| 项目 | 值 |
| --- | --- |
| 机型 | Redmi Note 8 Pro（代号 begonia） |
| 系统 | Android 10（API 29），MIUI |
| ABI | arm64-v8a |
| 连接 | `adb connect <手机IP>:5555`（地址以当前设备为准，未记录实际局域网地址） |
| 调试包名 | `com.whatsbird.debug`（debug 有 `applicationIdSuffix`） |

Android 10 没有「无线调试」（Android 11 才引入），因此 TCP 模式由 `adb tcpip 5555` 开启，
**不会通过 mDNS 广播**——`adb mDNS services` 扫不到属正常，必须手动 `adb connect`。

MIUI 会为每次 `adb install` 弹出「USB安装提示」确认框，10 秒不点即视为「用户取消」并报
`INSTALL_FAILED_USER_RESTRICTED`，看起来像权限问题。`tools/accept-usb-install.py` 会驱动这个弹窗，
让安装可无人值守进行。

## 随包内容

| 资产 | 内容 | 体积 |
| --- | --- | --- |
| `models/bird_detector.tflite` | MediaPipe EfficientDet，COCO `bird` 类过滤（在 Kotlin 侧按 `categoryName()` 过滤，见下文） | 13.8 MB |
| `models/bird_classifier.tflite` | 自训 MobileNetV3-Small，52 类，float16 | 2.0 MB |
| `models/species.json` | 51 个鸟种的学名/英文名/中文名与类索引 | 6 KB |

启动时管道状态（`BirdPipeline` 日志）：

```
pipeline ready status=READY detector=true classifier=true dictionary=true
classifier ready: input=UINT8 224px, classes=52
```

## 仪器化测试（`app/src/androidTest`）

`ClassifierOnDeviceTest` 把 **36 张留出照片**（5 个鸟种 × 6 张 + 6 张清单外/非鸟）打进测试包，
用 App 自己的 `SpeciesClassifier` 与 `BirdDetector` 在设备上跑，因此不需要有人对着真鸟就能验证主链路。

运行方式（测试包已安装时）：

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class com.whatsbird.ClassifierOnDeviceTest \
  com.whatsbird.debug.test/androidx.test.runner.AndroidJUnitRunner
```

结果：

| 测试 | 结果 |
| --- | --- |
| `detectorFindsBirdsInHeldOutPhotos` | 通过 |
| `shippedClassifierIdentifiesHeldOutPhotos` | 通过 |

| 指标 | 实测 |
| --- | --- |
| 检测：30 张留出图中出框的 | 22/30 = **73.3%** |
| 分类：5 个鸟种 30 张的 top-1 | 14/30 = **46.7%** |
| 清单外 6 张被报成已知种名（p≥0.60） | 0/6 = **0%** |
| 单张裁剪的分类耗时 | **18.6 ms**（n=45，float16） |

分类准确率与离线结果同量级（同一模型、同一批来源），说明 **Android 侧没有额外精度损失**——
量化、中心方图裁剪与 LiteRT 运行时都没有引入偏差。测试里 30% 的下限用于抓「导出损坏」
（类序错乱、量化错、预处理不匹配，都会掉到 1/52 ≈ 2%），不用于给准确率背书；
真实准确率以 `MODEL_CARD.md` 为准。

## 训练数据切分完整性（2026-09-14）

**此前记录的 val 0.778 是泄漏堆出来的假数字，不是模型能力。** 已定位、已修复，并加了训练前强制门槛。

`fetch_inat.py` 可续跑（`download()` 跳过已存在文件），而 `train.py` 用
`image_dataset_from_directory` 读**目录**。`data2` 第一次抓取在 background 阶段被杀，续跑时每条种
活下来的照片数变了、切分边界随之移动，上一轮的文件就留在原处：

| 目录 | 磁盘文件 | 其中 manifest 判给别的 split |
| --- | --- | --- |
| `train/` | 16632 | **2283**（test 1121、val 1162） |
| `val/` | 3225 | **1366**（train 1217、test 149） |
| `test/` | 3165 | **1287**（train 1157、val 130） |

后果：模型**训练时见过 1121 张测试图**，而 val 里有 **1217 张训练图**。症状很干净——第 1 个 epoch
就到 0.778，之后 14 个 epoch 一次没涨过。清理后在同一台机器上重训（`out4`）：**val 0.6288**。

修复分三层：

1. **`ml/dataset_integrity.py`（新增）** — 以 manifest 为唯一权威**双向**审计目录归属，冲突文件**移入**
   `<data>/quarantine/` 而不是删除。可逆，且不会撞上批量删除护栏；上次正是因为护栏，
   抓取报了成功而目录一直脏着。`--apply` 同时把 manifest 改写成「恰好列出磁盘上存在的文件」——
   单向修完会让按 manifest 取图的 `export_assets.py` / `sweep_threshold.py` 直接
   `NotFoundError` 崩掉（实测：96 条记录指向已被移走的文件），数据集从「数字虚高」变成「跑不起来」。
2. **`train.py` 训练前强制审计** — 不一致直接 `SystemExit` 并打印修复命令；要强行跑必须显式
   `--allow-split-mismatch`。这道闸不放在 flag 后面，因为代价只有一次目录遍历。
3. **`fetch_inat.py` 全局 photo_id 去重** — 近缘种会互相 cross-list 照片（如 *Turdus naumanni* 与
   *Turdus eunomus*），同一张图此前会被写两次：一次当 A 的训练图、一次当 B 的测试图。实测 `data2`
   有 **92 张跨 split**、另有 **209 张同 split 双标签**（同一张图在 `train/` 里挂着两个种名）。
   现在一张照片只属于一个 (物种, split)。

另：`dataset_integrity.py` 同时报「文件数」和「唯一文件名数」。先前两者不一致（keras 报 13268、
工具报 13059）正是被 209 个同名重复掩盖的信号——一个对不上的数字本身就是发现。

## 运行期发现

**XNNPACK 拒绝全 int8 图，且真机与 Python 运行时表现一致。** 两者都报
`failed to create XNNPACK runtime / Node number 119 (TfLiteXNNPACKDelegate) failed to prepare`。
`SpeciesClassifier.createInterpreter()` 因此会先试 XNNPACK，失败则回退到普通 CPU kernel 并打日志：

```
W/SpeciesClassifier: XNNPACK delegate rejected the classifier; retrying on plain CPU kernels
I/SpeciesClassifier: interpreter ready (xnnpack=false)
```

没有这个回退，App 会**静默退化成「只有框、没有鸟名」**，而不是慢一点。这条回退加上按实测选择的
量化档（见模型卡：int8 准确率比 float16 低 **24 个百分点**——1612 张留出图上 0.3778 vs 0.6160，
且用不上加速，因此随包发 float16），使 int8 的两个缺点都不再落到用户身上。

## 相机链路（早期冒烟测试，同一台设备）

| 项目 | 结果 |
| --- | --- |
| 权限流程 / 预览起流 | 正常（MTK HAL，约 14.5 fps） |
| 拍照分辨率 | 修复前 6936×9248（64MP，17MB）→ 修复后 1920×2560（2.1MB） |
| 拍照端到端耗时 | >10 s → 1.5 s → **1.14 s**（分段计时见下文） |
| 落盘 | `Pictures/识鸟/`，原图 + `_labeled.jpg` |
| 成功提示 | 正常弹出，2.6 s 后按设计消失 |

界面元素、变焦胶囊、相册缩略图、设置面板均经控件树核实。截图在 `docs/smoke/`。

## 全链路注入验证（`BirdPipelineOnDeviceTest`）

`submitFrame` 这条全链路此前没有被覆盖：`ClassifierOnDeviceTest` 分别测检测和分类，而崩溃恰好发生在
两者**之间**——一旦检测产生 track，管道才开始构造快照对象，空取景器永远不会走到那段代码。

`BirdPipelineOnDeviceTest` 把一张留出鸟照（放大到 1280×720）按 250 ms 间隔喂进 `submitFrame`，断言
overlay 非空**且**标签不再全是 `IDENTIFYING`（后者才证明分类真的跑过）。实测：

```
classify track=1 88ms top=0:0.830
classify track=1 20ms top=0:0.830
source=samples/img02.jpg frames=7 overlay items=1 trackedBirds=1
track=1 box=[0.4078,0.2958][0.6242,0.7139] label=TrackLabel(kind=CONFIRMED, classIndex=0, score=0.8295)
```

`img02` 是白头鹎（`Pycnonotus sinensis`），分类器给的就是 `classIndex=0`，即白头鹎本身。

**这里踩过两个坑，都记下来：**

1. **原测试硬编码「第一张样本」，换检测模型就会误报。** 换成 EfficientDet-Lite2 后 `img00`/`img01`
   恰好是它检不出的 5 张中的 2 张，测试失败，但检测本身是变好的（整体召回 22/30 → 25/30）。
   现在由 `openDetectableBirdFrame()` 遍历样本、取**第一个真被检出**的来驱动链路——检测质量由
   `ClassifierOnDeviceTest` 在 30 张上量化，这个测试只需要证明「有一只鸟走完了全链路」。

2. **原测试固定喂 12 帧，是在测机器速度而不是测链路。** `detectAsync` 在上一帧还没回来时**拒收**，
   而 CPU 委托下 Lite2 单帧 1280×720 要 ~0.8 s：12 × 250 ms 里实际只放行约 3 帧，标签完全可能
   还在飞。失败现场就是 `label=IDENTIFYING score=0.0`，看着像「分类器坏了」，实际是只收到 1 票
   （`LabelStabilizer.minSamples = 2` 的早退路径）。现在改成**持续喂帧直到标签离开 IDENTIFYING**
   （上限 30 s），断言的是链路契约而非帧预算。实测 7 帧、约 5 s 内落地。

顺带修掉一个真实体验问题：新 track 的第一票之后，原本要等 `RECLASSIFY_INTERVAL_MS = 1500 ms`
才会追加第二票，也就是**一个鸟名最快也要 1.5 s 才可能出现**。现在 `handleDetections` 会把
「票数还不到 `minSamples`」的 track 当作高优先级立即重排（`stabilizer.voteCount(id)`），
第二个名字候选在两帧内就到位；`TrackMemory.enqueued` 仍然保证每只鸟最多一个在途任务，不会堆积。

注意样本必须放大：`BitmapOps.crop` 有 24 px 最小边长，240 px 宽的样本会把鸟框压成 12 px 高而被直接
拒掉，分类器根本不会被调用。

## 识别质量的定位手段（照了一张照片说「识别不准」之后加的）

「识别不准」不是一件事，下面四种情况的**界面表现完全一样**，都是「鸟类」：

1. 检测器根本没找到鸟；
2. 裁剪被 `BitmapOps.crop` 的 24 px 下限拒掉，分类器从未运行；
3. 分类器 top-1 就是对的种，但分数低于显示门限；
4. 分数过了门限，但和第二名的差距小于 `STILL_MARGIN`，名字被主动扣下。

只看保存下来的 `_labeled.jpg` 分不出这四种，而这四种的修法完全不同：3 和 4 是**操作点**问题（调门限），
1 和 2 是**管道缺陷**，top-1 判错才是**模型**问题（只能重训）。因此补了两条取证路径：

- **App 侧**：`BirdPipeline.identifyStill` 现在把每个框的 `verdict=` 与前三名候选 `index:score` 一起打进
  logcat。以后任何一次「识别不准」的现场都能直接从日志读出它属于上面哪一类。
- **离线**：`ml/diagnose_photo.py` 用随包的两个模型按 App 的完全相同预处理跑任意照片，打印每个框的
  top-5 与 App 会给出的判定；`PhotoDiagnosisOnDeviceTest` 是它在真机上的等价物（macOS 的 MediaPipe
  wheel 一加载检测模型就 aborts 在 `TensorsToDetectionsCalculator` 的 Metal 图服务上，headless 进程
  拿不到，所以这台机器上只能让设备来跑）。照片走 App 自己的外部私有目录，不需要任何权限：

```
adb push photo.jpg /sdcard/Android/data/com.whatsbird.debug/files/diagnose/
adb shell am instrument -w -e class com.whatsbird.PhotoDiagnosisOnDeviceTest \
  com.whatsbird.debug.test/androidx.test.runner.AndroidJUnitRunner
```

没有照片时该测试自行跳过，不污染常规套件。

### 实测：一张「全是鸟类」的照片，真实原因是什么

用户对着笔记本屏幕上的鸟类搜索结果页拍照（这是最恶劣的场景：屏幕像素 + 摩尔纹 + 缩略图里鸟很小 +
搜到的鸟未必在清单内）。两台设备上的诊断结果：

| 框 | 检测分 | top-1 | App 判定 | 真实原因 |
| --- | --- | --- | --- | --- |
| 飞行中的红嘴蓝鹊 | 0.72 | background 0.344 | 鸟类 | 清单内种但飞行姿态在 iNat 数据里极少 → **模型** |
| 黄鹡鸰（两只） | 0.29 | background 0.651 | 鸟类 | 不在 51 类清单内 → **清单** |
| 鹭（站水中） | 0.39 | 白鹡鸰 0.154 | 鸟类 | 相似种混淆 → **模型** |

同一批里另一张横屏照片（搜索结果是斑鸠）则完全不同：

| 框 | top-1 | top-2 | App 判定 |
| --- | --- | --- | --- |
| 1 | 珠颈斑鸠 0.667 | 山斑鸠 0.269 | **珠颈斑鸠** |
| 2 | 山斑鸠 0.964 | 珠颈斑鸠 0.012 | **山斑鸠** |
| 3 | 山斑鸠 0.987 | 麻雀 0.003 | **山斑鸠** |
| 4 | 山斑鸠 0.579 | background 0.136 | 鸟类（0.579 < 门限 0.60，**差 0.021**） |
| 5 | 珠颈斑鸠 0.433 | 山斑鸠 0.354 | 鸟类（分数与差距都没过） |

同一个模型，五个框里有四个 top-1 是对的，只显示出两个。所以「识别效果很差」这句话里，
**至少有一半是操作点，不是模型**。

这两组框裁出来拼成的对照图在 `docs/diagnosis/2026-09-14-detected-crops-and-verdicts.png`
（上排「全是鸟类」的那张，下排斑鸠那张），每格下面标了 top-1 与 App 实际给出的判定。

### 门限与差距门限的实测（`sweep_threshold.py --grid`）

`sweep_threshold.py` 此前只扫分数门限，`BirdPipeline.STILL_MARGIN = 0.15` 从未被任何测试集量化过——
模型卡里公布的所有数字都是 margin = 0 时的。现在 `collect()` 同时记下第二名分数，
`--rows-in/--rows-out` 缓存逐图结果避免重复推理，`--grid` 打印完整二维面。829 张冻结测试集上：

```
coverage / precision at every (threshold, margin):
  thr          0.00          0.05          0.10          0.15          0.20
 0.30  75.3%/ 59.9%  72.3%/ 60.9%  68.0%/ 61.9%  63.1%/ 64.4%  57.6%/ 66.0%
 0.40  61.3%/ 66.1%  60.5%/ 66.1%  59.1%/ 66.6%  58.0%/ 66.9%  55.2%/ 67.3%
 0.50  48.0%/ 70.6%  47.8%/ 70.8%  47.6%/ 70.9%  47.5%/ 71.1%  46.9%/ 71.6%
 0.55  42.2%/ 75.9%  42.2%/ 75.9%  42.2%/ 75.9%  42.2%/ 75.9%  42.0%/ 76.0%
 0.60  36.9%/ 79.8%  36.9%/ 79.8%  36.9%/ 79.8%  36.9%/ 79.8%  36.9%/ 79.8%
 0.65  33.3%/ 82.7%  33.3%/ 82.7%  33.3%/ 82.7%  33.3%/ 82.7%  33.3%/ 82.7%
```

**结论：在门限 ≥ 0.55 时差距门限完全不生效**（52 类里能过 0.55 的 top-1，第一名早就甩开第二名 0.2 以上），
它只在门限 ≤ 0.50 时才起作用，把覆盖率换成精确率。所以在出厂门限 0.60 下它是**零成本的安全网**，
不该删——设置里的滑块可以滑到 0.25，它对激进设置仍然有保护作用。它只是以前没人量过，现在量了。

出厂门限 0.60 本身也值得注意：扫描脚本在「精确率 ≥ 80% 且清单外误报 ≤ 15%」的约束下推荐 **0.65**，
而模型卡公布的数字对应 0.60。两者都没错，差别是政策而不是事实——0.60 比推荐值多给 3.6 个百分点的
覆盖率，代价是已显示种名的正确率从 82.7% 降到 79.8%。这条差异现在写在设置面板的文案里，用户自己可以选。

## 运行期缺陷修复记录

### Android 10 上 `PointF` 的拷贝构造器不存在（闪退）

```
FATAL EXCEPTION: wb-frames
java.lang.NoSuchMethodError: No direct method <init>(Landroid/graphics/PointF;)V in class Landroid/graphics/PointF;
  at com.whatsbird.track.BirdTracker.currentSnapshot(BirdTracker.kt:116)
  at com.whatsbird.pipeline.BirdPipeline.updateStats(BirdPipeline.kt:290)
  at com.whatsbird.pipeline.BirdPipeline.submitFrame(BirdPipeline.kt:134)
```

`android.graphics.PointF(PointF)` 是 **API 35** 才新增的构造器（`RectF(RectF)` 则是 API 1，不要混淆）。
`currentSnapshot()` 里 `states.map { ... }` 在 `states` 为空时不执行 lambda，所以**只有画面里出现鸟
之后才会崩**。已改为 `PointF(it.velocity.x, it.velocity.y)`。

**防复发**：`compileSdk` 高于 `minSdk` 会掩盖这类问题。新增代码后跑 `./gradlew :app:lintDebug`，
并在报告里检查 `Call requires API level`。

### 检测框未归一化（框画错位置、分类一直在看整帧）

`BirdDetector` 原先把 MediaPipe 的像素坐标直接包进 `RawDetection`，而 `BitmapOps.crop`、
`OverlayTransform`、`MIN_CLASSIFY_AREA`、`tooSmall` 提示全部按 0..1 编写。真机日志证据：
`box=[101.0,75.0][133.0,84.0]` —— 归一化坐标不可能大于 1。后果是裁剪里的 `coerceIn(0f,1f)` 把框
截成整帧，框也会被画到屏幕外。已在 `BirdDetector` 出口统一归一化，LIVE_STREAM 与 `detectSync`
两条路径都覆盖。

### 关闭管道时的 `RejectedExecutionException`

`close()` 关闭 `classifyExecutor` 之后，MediaPipe 仍可能有在途的检测结果回调，`drainQueue` 的提交
被线程池拒绝，异常抛在 MediaPipe 自己的线程上。已把 `classifyExecutor` 换成 `ThreadPoolExecutor` +
`DiscardPolicy`，终止后静默丢弃。

### GPU 委托 + `setCategoryAllowlist` 触发 MediaPipe 原生 abort（启动即闪退）

```
F/libc: Fatal signal 6 (SIGABRT) in tid 28897 (drishti_gl_runn), pid 28796 (whatsbird.debug)
Abort message: 'tensors_to_detections_calculator.cc:1187] Check failed:
  class_index_set_.values.size() == IsClassIndexAllowed(0) ? num_classes_ : num_classes_ - 1
  (1 vs. 89) Only all classes >= class 0 or >= class 1'
```

`setCategoryAllowlist(listOf("bird"))` 看起来是最正当的写法，实际会让
`TensorsToDetectionsCalculator` 的 `CHECK` 失败：该算子按 SSD 约定假设 class 0 是背景类，
于是要求类别索引集合要么是全部类别、要么是「除 0 以外的全部」；EfficientDet 有 90 类且没有背景类，
一个只有 1 个元素的 allowlist 就撞上 `(1 vs. 89)`。这是**原生 `CHECK` → `abort()`，不是异常**，
`runCatching` 拦不住，进程直接死。

两个让这个坑特别难查的性质：

- **只在 GPU 委托下触发**。同一份代码在 CPU 委托上跑到用户拍完好几张照片都没事，所以「闪退」看起来
  是随机发生的，而实际上它 100% 复现。
- **设置是持久化的**。用户一旦打开过「使用 GPU」，之后每次启动都在模型构建阶段 abort，
  App 再也起不来——用户没有任何恢复手段。

已改为**取回检测结果后在 Kotlin 侧按 `categoryName()` 过滤**（模型把标签表存在 TFLite metadata 里，
所以这个名字是真的可用的）。`MAX_RESULTS` 同时从 8 提到 25：过滤移到图外之后，图返回的是全部类别的
top-N，一帧里有十几个人也得分得出位置留给后面的鸟。

验证（设备上保留 `prefer_gpu=true` 的持久化设置直接启动）：

| | 修复前 | 修复后 |
| --- | --- | --- |
| 进程 | 启动后数秒内死亡 | 存活，`pipeline ready status=READY` |
| 崩溃缓冲 | `SIGABRT`（每次启动） | 空 |
| GPU 是否真的在用 | — | 日志有 `gl_context.cc: GL version 3.2 ... renderer: Mali-G76 MC4` |

### 启动守卫：不让一个设置开关把 App 锁死

上面那条之所以致命，是因为「坏选择被持久化了」——用户点了一下开关，App 就永久起不来。
`util/BootGuard.kt` 为此存在：**在构建 GPU 模型前**写一个标记文件，在**收到第一份检测结果后**
（或 15 s 窗口到期后）清除。下次启动若发现标记还在，就说明上一轮在模型拉起阶段死于原生失败，
于是这一轮强制走 CPU，并把 `preferGpu` 写回 `false`。

窗口到期清标记是必要的：连续两次 `abort` 间隔只有十几秒，但「相机被挡住、用户又很快退出」
同样会留下标记，那不该被误判成崩溃。

### 检测模型从 EfficientDet-Lite0@320 换到 Lite2@448

原随包的是 `efficientdet_lite0/float32`（与官方发布文件字节数一致：13,836,895 B），输入 320×320。
它对小目标天然吃亏，而用户的典型场景恰恰是「图集里每只鸟都很小」。同条件替换实验：

| 检测模型 | 留出照片出框率 | CPU 单帧 | GPU 单帧（同步） |
| --- | --- | --- | --- |
| efficientdet_lite0 / float32 @320 | 22/30 = 73.3% | 354 ms | — |
| **efficientdet_lite2 / float16 @448** | **25/30 = 83.3%** | 782 ms | 221 ms |

换成 Lite2 的额外好处是文件更小（12.1 MB vs 13.8 MB）。代价是 CPU 上慢一倍多，所以**同时把
`preferGpu` 的默认值改成 true**——这不是偏好，是实测结论：在真实管道里（MediaPipe 会把相邻帧流水化）
Lite2 的单帧延迟是 **126 ms**，落在 8 次/秒的节流预算内；而 CPU 委托下 782 ms 意味着检测率掉到
约 1 帧/秒，节流值再大也没用。

```
I/BirdPipeline: live detectFps=21.0 detectMs=126 classifyMs=0 dropped=18 birds=0
```

`BirdPipeline` 现在每 5 秒打一行这样的滚动状态。「框更新得慢」究竟是节流还是检测器本身跟不上，
这两者要修的地方完全不同，日志里能直接分开。



### 拍照慢：1.5 秒花在了一个没有缓冲的输出流上

`PhotoCapture` 现在把每个阶段都计时并打成一行日志，实测（Redmi Note 8 Pro，1920×2560 帧）：

| 阶段 | 优化前 | 优化后 |
| --- | --- | --- |
| CameraX 快门 | 629 ms | 577 ms |
| 读 JPEG 缓冲 | 3 ms | 2 ms |
| **写原图** | **1568 ms** | 与下面并发（收尾仅 19 ms） |
| 解码 + 旋转 | 119 ms | 116 ms |
| 检测（GPU） | 247 ms | 185 ms |
| 绘制标注 | 23 ms | 18 ms |
| 编码 + 写标注图 | 347 ms | 225 ms |
| **合计** | **2953 ms** | **1142 ms** |

那只 1.5 秒的大头不是 MediaStore 本身。给 `MediaStoreSaver.saveJpeg` 加上分段计时后：

```
saved whatsbird_20260914_105027_353.jpg 1945KB insert=21ms write=13ms finish=34ms
```

`insert` 21 ms、`finish` 34 ms 都正常，而 `write` 从 1568 ms 掉到 13 ms——差别只是包了一层
`BufferedOutputStream`。未缓冲时 1.9 MB 会按小块穿过 MediaStore 的 FUSE 层，一块一次往返。

同时把「写原图」交给 IO 线程池与「检测 / 分类 / 绘制」并行（两者互不依赖），并把标注副本的
JPEG 质量从 95 降到 88：源图本身已经是 JPEG，95 花时间在源图没有的细节上。

### 横屏取景（Activity 锁死竖屏）

`android:screenOrientation="portrait"` 之下，用户把手机横过来取景时，整个界面（包括标签文字）
相对人眼转了 90°，也就是「字还是竖版的」。改为 `fullSensor`：相机必须跟随设备，
哪怕用户的自动旋转是关的——实测这台设备正是 `accelerometer_rotation=0`，
所以默认的 `unspecified` 对这位用户等于永远竖屏。

横屏下的几何没有新增代码：`FrameAnalyzer` 先按 `rotationDegrees` 把帧转正再交给管道，
`OverlayTransform` 直接按「帧宽高比 + 视图尺寸」计算，本来就与方向无关。
`OverlayTransformTest` 新增一条横屏用例把这件事钉住（4:3 帧在 2.17:1 视图里左右铺满、上下裁剪）。

界面本身用一个临时锁定横屏的构建截图核实过：快门、相册、变焦胶囊、设置按钮都在底部水平排开，
文字水平、无裁切。验证后已改回 `fullSensor`。


## 仍未验证

**真实相机帧的几何表现**——预览里的框是否正好落在鸟身上，以及横屏取景时同样成立。
链路已由 `BirdPipelineOnDeviceTest` 覆盖，但注入的是无旋转位图；相机帧的旋转、预览宽高比与
`OverlayTransform` 的配合，以及横屏下的实际观感，仍需有人对着取景器看一眼——把手机对准显示器上
的鸟类照片即可，横着再拍一张，不必等真鸟。
