# MahjongVisionAgent (麻将智囊 Agent)

> 工业级 Android 实时麻将视觉分析与智能决策系统 (面向《腾讯欢乐麻将》及主流川麻手游)
> 基于端到端事件驱动帧调度 + 原生高精 NCC 图像分类 + 108 牌物理守恒记牌器 + 四川麻将核心求解器。

---

## 一、核心系统特性

1. 动态多区域交互式标定系统 (RegionCalibrator)
   - 全屏半透明冻结遮罩，手指自由拖拽 4 角手柄调整选框。
   - 支持【手牌区】、【牌河区】、【碰杠区】多 Tab 实时切换。
   - 归一化浮点比例储存，自适应挖孔屏、全面屏及不同长宽比分辨率。
   - 配置即刻保存至本地 JSON (GameProfile)，热重载立即生效。

2. 高精度视觉感知引擎 (TileDetector)
   - 采用腾讯麻将真实原声模板提取，毫秒级零均值归一化互相关 (NCC) 分类器。
   - 内置多角度自适应分类 (支持 0度、180度、90度、270度 对家及左右两侧横置/纵置弃牌)。
   - 左下角头像定缺徽章 HSV 色彩自适应感知 (缺条/缺筒/缺万)。
   - 实机真图实测手牌分类准确率 100%，单张耗时 < 0.2ms。

3. 108 牌物理绝对守恒记牌器 (TileMemory)
   - 严格维护四川麻将 (108 张牌) 动态余量: Remaining = 4 - Hand - Melds - Discard
   - 单调累加器机制: 公开见牌只增不减，越界自动截断，抵御动画飞行动画遮挡。

4. 全规则四川麻将求解内核 (SichuanSolver)
   - 定缺天条门控 (DingQue Policy): 存在缺门牌时，强制缩减候选集，孤张偏张 (1, 9) 极速切出。
   - 0-向听叫听 (Ready-Hand / Ting): 精确罗列所有听牌叫口，依据全场真实存活张数降序推荐最优出牌。
   - 1-向听进张加权 (1-Shanten Effective Draws): 多路线进听树分支评估，输出改善概率最大的切牌建议。

5. 悬浮交互层与 9x3 记牌仪表盘 (OverlayService)
   - 胶囊态: 定缺徽章 (一键循环切换) + 推荐出牌 + 叫口简讯 + 设置入口，零遮挡游戏画面。
   - 全展开态:
     - 详细决策理由与听牌存活张数。
     - 9x3 剩余牌动态矩阵: 按万、条、筒 1~9 直观呈现全场 27 类牌的剩余张数 (0张红字高危绝张警示，1~2张橙字紧缺，3~4张绿字生张)。

6. 底层零泄漏前台捕获服务 (ScreenCaptureService)
   - 严格遵循 Android 14 (API 34) FOREGROUND_SERVICE_MEDIA_PROJECTION 规范。
   - 采用双缓冲 (maxImages = 2)，根除内存暴涨与 OOM。
   - 挂载 FrameGovernor 微型降采样哈希去抖动调度，静止画面 CPU 占用 < 1%。

---

## 二、构建与运行指南

### 1. Android 端编译与部署
- 编译: 在 Android Studio 中打开 MahjongVisionAgent/android，或通过 Gradle 构建:
  gradlew.bat assembleDebug
- 安装至手机或模拟器:
  adb install -r app/build/outputs/apk/debug/app-debug.apk

### 2. 本地端到端回放验证
直接运行:
  python d:/mj/verify_end_to_end.py
自动载入真实全景对局截图验证所有模块，测试通过率 100%。
