# FlashAlarm - 基于穿戴生理信号的 REM（做梦期）模型训练与安卓代码生成工具

本目录包含用于训练 **1Hz 心率 + 体动计** 睡眠分期（重点识别 REM 快速眼动做梦期）的完整端到端 Python 脚本及配套文档。

---

## 目录文件结构

```text
rem_ml_trainer/
├── train_rem_model.py        # 核心训练流水线脚本（数据清洗、11维特征工程、GroupKFold评估、Java代码导出）
├── requirements.txt          # Python 依赖清单 (numpy, pandas, scikit-learn, lightgbm, m2cgen)
└── README.md                 # 完整使用说明文档（本文件）
```

---

## 一、 数据集来源（黄金标准对照）

睡眠分期（尤其是 REM 期识别）的医学“金标准”是 **PSG（多导睡眠监测，含脑电 EEG + 眼电 EOG + 肌电 EMG）**。我们直接使用密歇根大学公开的穿戴设备临床对照数据集：

* **数据集名称**：PhysioNet Sleep-Accel (v1.0.0)
* **论文出处**：*Walch et al., "Sleep stage prediction with raw acceleration and photoplethysmography heart rate data derived from a consumer wearable device", SLEEP (2019)*
* **免费下载地址**：[https://physionet.org/content/sleep-accel/1.0.0/](https://physionet.org/content/sleep-accel/1.0.0/)
* **下载步骤**：
  1. 打开上述网页，在 **Files** 区域点击 **Download the ZIP file (550.1 MB)** 下载。
  2. 解压到本地任意目录（例如 `rem_ml_trainer/sleep-accel/`）。

---

## 二、 11 维生理特征与 5 分钟延时设计

本方案严格遵守用户设定的 **准实时在线判断延时 $\le 5$ 分钟（10 个 30 秒 Epoch）** 约束，以 21 个 Epoch（约 10.5 分钟）为滑动上下文，对第 10 个 Epoch（即 5 分钟前的时刻）做出高可信判决：

| 索引 | 特征名 | 生理学意义 |
| :--- | :--- | :--- |
| `[0]` | `hr_mean_curr` | 当前 30s 平均心率（bpm） |
| `[1]` | `hr_std_curr` | 当前 30s 心率微观波动标准差（REM 期自主神经离散跳变） |
| `[2]` | `motion_mean_curr` | 当前 30s 加速度向量模差均值 $\|VM - 1g\|$ |
| `[3]` | `motion_max_curr` | 当前 30s 峰值运动冲击（排查翻身、微觉醒） |
| `[4]` | `hr_surge_baseline` | 相对于夜间深睡最低基线的心率突增比率：$(HR - Base) / Base$ |
| `[5]` | `hr_mean_past_5m` | 过去 5 分钟（10 个 Epoch）的平均心率 |
| `[6]` | `hr_mean_future_5m` | **未来 5 分钟的平均心率（用于滞后确认）** |
| `[7]` | `hr_std_context` | 包含过去与未来的 10 分钟中心窗口总体心率离散度 |
| `[8]` | `motion_mean_past_5m` | 过去 5 分钟手腕体动均值 |
| `[9]` | `motion_mean_future_5m` | **未来 5 分钟手腕体动均值（确认骨骼肌持续瘫痪 Atonia）** |
| `[10]` | `time_since_start_min` | 入睡流逝时间（利用前 70~90 分钟极少出现 REM 的超昼夜节律先验） |

---

## 三、 安装与训练步骤

### 1. 安装依赖
在终端中进入本目录：
```powershell
pip install -r requirements.txt
```

### 2. 启动训练流水线
```powershell
# 训练整夜全量数据集，并自动输出到安卓工程：
python train_rem_model.py --data-dir "你的解压路径/sleep-accel"
```

> **快速测试模式（尝鲜验证）**：
> 如果还没下载完 550MB 压缩包，可以运行：
> ```powershell
> python train_rem_model.py --download-sample --sample-count 1
> ```
> 脚本会自动从 PhysioNet 下载 1 位受试者的样本进行全流程跑通。

---

## 四、 自动化输出物

训练完成后，脚本会自动向 Android 工程目录：
`app/src/main/java/com/flashalarm/miband/domain/algorithm/`
输出以下两个生产就绪文件：

1. **`RemClassifierModel.java`**
   * 由 `m2cgen` 导出的纯 Java 决策树模型。
   * 零第三方依赖、体积仅 ~20KB（远离 JVM 64KB 方法上限），每次推理耗时纳秒级。
2. **`RemFeatureExtractor.kt`**
   * 安卓端专用滑动窗口特征提取器。
   * 负责维护 21 个 Epoch 环形缓冲区，特征计算公式与 Python 训练代码 100% 对齐。

---

## 五、 安卓端调用示例

```kotlin
import com.flashalarm.miband.domain.algorithm.RemClassifierModel
import com.flashalarm.miband.domain.algorithm.RemFeatureExtractor

// 1. 初始化提取器
val extractor = RemFeatureExtractor()

// 2. 每当手环产生一个 30s 统计数据时调用
val features = extractor.pushEpoch(
    epochIndex = currentEpochIndex,
    meanHr = epoch30sAvgHr,
    stdHr = epoch30sStdHr,
    meanMotion = epoch30sAvgMotion,
    peakMotion = epoch30sPeakMotion
)

// 3. 缓冲区满 5 分钟后，features 返回 11 维 DoubleArray
if (features != null) {
    val probs = RemClassifierModel.score(features)
    val remProbability = probs[1] // probs[0]: 非REM, probs[1]: REM做梦期概率

    if (remProbability > 0.60) {
        // 成功捕获 REM 期！触发手环触梦微震或计入 Hypnogram
    }
}
```
