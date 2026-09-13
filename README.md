# FlashAlarm (小米手环 6 伴侣) - 多模态 REM 监测与腕部黄金触梦系统

基于 **Android (Kotlin + Jetpack Compose)** 构建的高品质独立伴侣 App。
通过低功耗蓝牙（BLE）与**小米手环 6（Mi Band 6）**深度协同，融合手腕体动微积分（Actigraphy）、实时心率与变异度（PPG HR & HRV），并结合手机端夜间呼吸声学分析，构建高精度的多模态做梦期（REM）智能监测与腕部黄金触梦提醒系统。

UI 交互全面对标 **FlashAlarm / Oura 黑曜石暗黑极简设计**。

---

## 核心特性与架构

### 1. BLE 华米协议深度对接 (`data/ble`)
- **AES-128 认证握手 (Huami Auth)**：
  - Auth Service: `0000fee1-0000-1000-8000-00805f9b34fb`，Characteristic: `00000009-0000-3512-2118-0009af100700`。
  - 用户填入 16 字节 Auth Key（32 位十六进制字符串），发送 `[0x02, 0x08]` 请求随机 Challenge，使用 `AES/ECB/NoPadding` 加密后回传 `[0x03, 0x08] + cipher` 完成握手。
- **实时心率流采样**：
  - 标准 BLE Heart Rate Service `0x180D` / `0x2A37`，下发持续心率采样指令 `[0x15, 0x01, 0x01]`，维持 12s 心跳 Ping 保活。
- **高频体动 (Actigraphy) 微积分采样**：
  - 订阅华米传感器通道 `00000002-0000-3512-2118-0009af100700`，获取三轴加速度原始或微积分向量模差 \(VM = \sqrt{x^2+y^2+z^2}\)。
- **腕部专属微震下发**：
  - Immediate Alert Service (`0x1802` / `0x2A06`) 下发脉冲节奏序列。

### 2. 多模态 REM 分层融合算法 (`domain/algorithm`)
- **以手环生理指标为主干（75%）**：
  - **骨骼肌瘫痪（Muscle Atonia）**：检测手腕动量连续处于极低水平（< 0.02g）。
  - **抬手/翻身一票否决**：一旦检测到身体明显动作，立即否决 REM 判定。
  - **自主神经风暴（PPG HR & HRV）**：深睡期处于基线最低且 CV 极低；REM 阶段心率较基线突增 10%~25% 且变异系数（CV）剧烈离散跳变。
- **手机声学呼吸交叉校验（25%）**：
  - 麦克风在后半夜采集呼吸音频，提取呼吸频率（BPM）与节律不规则度。
  - 双重印证：手腕完全静止 + 心率突增紊乱 + 呼吸变浅变乱时，置信度达 95%+，立即触发微震。
  - 平滑降级：环境嘈杂（信噪比低）或关闭麦克风时，系统无缝降级为纯手环双通道模式。
- **睡眠周期时间窗硬约束**：
  - 入睡（Sleep Onset）满 70~90 分钟后（首个 NREM 深睡周期结束）且主要在后半夜开放触发，彻底杜绝前半夜深睡误击发。

### 3. 触梦提醒执行 (Lucid Dream Cueing)
- 预设敲击微震节拍：
  - `双击-停顿-长震` (200ms - 200ms - 200ms - 600ms - 800ms)
  - `三重微脉冲`
  - `渐进平滑微震`
  - `律动心跳震感`
- 触觉安全保护：防频繁惊醒冷却时间（15~45 分钟可调），单次最长震动保护。

### 4. UI 与交互设计 (FlashAlarm / Obsidian 风格)
- **纯黑 OLED 沉浸主题 (`0xFF000000`)**。
- **主页**：手环连接卡片、实时心率/体动指示、触梦参数配置卡片、底部常驻沉浸式胶囊按钮 `[ 🌙 开始手环睡眠守护 ]`。
- **睡眠分析 Tab**：
  - **大字睡眠战报卡片**：净睡眠时长、在床时间、睡眠效率、右侧固定圆形评分徽章（**强制单行排版，数字与“分”水平对齐，严禁折行**）。
  - **四层催眠图谱 (Hypnogram) 阶梯图**：清醒、REM、浅睡、深睡阶梯图 + 叠加实时心率折线 + 亮金星标 ✨ 精准标记触梦时刻。
  - **流畅手指滑动 (Scrubbing)**：垂直微光准心线与高亮圆点实时跟手移动。
  - **极简阶段时间展示**：滑动时顶部仅显示当前阶段区间（如：`深睡 · 00:30 ~ 01:50`，严格剔除冗余时长文案）并配合显示该时刻心率（如 `68 bpm`）。
  - **历史多记录切换**：按唯一自增 `sessionId` 绑定，支持单日内多段睡眠（午休+夜间）自由切换。
- **极暗床头屏保 (`SleepModeActivity`)**：
  - OLED 纯黑背景 + 柔和小尺寸防烧屏数字时钟 + 底部阻尼防误触滑动滑块（`向右滑动结束守护`）。

---

## 目录工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/flashalarm/miband/
│   ├── FlashAlarmApp.kt               # 全局应用入口，初始化各核心单例
│   ├── MainActivity.kt               # 主容器与底部导航（守护 / 睡眠分析）
│   ├── data/
│   │   ├── ble/
│   │   │   ├── BleConstants.kt       # 华米与标准 BLE 服务 UUID 及命令集
│   │   │   ├── HuamiAuthHandler.kt   # AES-128-ECB 握手加密与状态机
│   │   │   ├── MiBandBleManager.kt   # 扫描、连接、特征流订阅与震动下发
│   │   │   └── VibrationCadence.kt   # 触梦节拍序列定义
│   │   ├── audio/
│   │   │   └── BreathingAudioAnalyzer.kt # PCM 16bit 后台呼吸声学与信噪比分析
│   │   ├── db/
│   │   │   ├── SleepEntities.kt      # 会话、Epoch 时序与触梦记录实体
│   │   │   ├── SleepDaos.kt          # Room 响应式查询与写入 DAO
│   │   │   └── SleepDatabase.kt      # SQLite Room 数据库
│   │   └── repository/
│   │       ├── SleepRepository.kt    # 聚合存储与模拟数据生成
│   │       └── UserPreferencesRepository.kt # 偏好与设备凭据存储
│   ├── domain/
│   │   ├── model/
│   │   │   ├── SleepStage.kt         # 清醒 / REM / 浅睡 / 深睡 枚举
│   │   │   ├── BleState.kt           # BLE 连接与指标状态
│   │   │   ├── DreamCueConfig.kt     # 触梦参数配置
│   │   │   └── RemStagingResult.kt   # 融合分期评定结果
│   │   └── algorithm/
│   │       └── MultiModalRemEngine.kt # 75%手环+25%声学分层融合算法引擎
│   ├── service/
│   │   └── SleepGuardService.kt      # 前台保活服务、WakeLock 与调度中枢
│   └── ui/
│       ├── theme/                    # Obsidian 纯黑高对比度配色与字体
│       ├── components/
│       │   ├── SleepScoreBadge.kt    # 强制单行水平对齐无折行评分徽章
│       │   ├── HypnogramChart.kt     # 阶梯催眠图谱、心率折线与拖拽准心
│       │   └── SlideToStopSlider.kt  # 带阻尼手感与触觉的滑动结束条
│       ├── home/
│       │   └── HomeScreen.kt         # 主页与手环配置
│       ├── sleep/
│       │   ├── SleepAnalysisScreen.kt # 睡眠分析报表页
│       │   └── SleepViewModel.kt     # 睡眠报表数据流控制
│       └── bedside/
│           └── SleepModeActivity.kt  # 极暗 OLED 床头屏保与实时监控
```

---

## 如何获取小米手环 6 Auth Key

由于小米手环 5/6/7 升级了华米加密协议，连接前手环会验证绑定的 16 字节 Auth Key。您可以通过以下任意方式获取您的手环 Key：
1. **Gadgetbridge / FreeMyBand**：通过官方小米账号登录网页版获取设备绑定的 Auth Key（32 个十六进制字符）。
2. **Zepp Life (小米运动) 数据库导出**：在手机已 Root 或备份数据中，查看 `/data/data/com.xiaomi.hm.health/databases/origin_db` 中的 `AUTH_KEY` 字段。
3. 打开 FlashAlarm 主页右上角设置图标，将 MAC 地址与 32 位 Auth Key 粘贴填入并保存即可直接连接。

---

## 单元测试覆盖

项目在 `app/src/test/` 中提供了完整的核心算法与协议测试：
- `MultiModalRemEngineTest.kt`：
  - 验证翻身动作一票否决规则（Movement > Threshold 立即置零 REM 置信度）。
  - 验证入睡首个 70~90 分钟保护窗约束。
  - 验证深睡基线建立与 REM 自主神经风暴（心率突增与离散跳变）。
  - 验证声学不规则度与手环生理双重印证触发。
  - 验证嘈杂或无麦克风时的平滑降级模式。
  - 验证防止惊醒肉体的触梦冷却间隔约束。
- `HuamiAuthHandlerTest.kt`：
  - 验证 AES-128-ECB 对 Challenge 随机数的加密运算及协议帧格式。
- `SleepMetricsTest.kt`：
  - 验证在床时间、净睡眠时长、效率与评分公式。
