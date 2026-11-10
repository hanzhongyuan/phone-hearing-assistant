# 听桥 · HearBridge

面向 **Android 8.0+、vivo X100 Ultra 和 Shokz 蓝牙骨传导耳机**的离线听力辅助技术试用应用。原型已重写。它是可安装的研究软件，**不是经过临床验证的助听器或测听仪**。

## 安装

在仓库 **Releases** 下载 `HearBridge-1.0.0-arm64-v8a.apk`（vivo X100 Ultra 选这个）。其他设备可用 `universal` 包。所有模型已内置，安装后不需要联网下载；安装包约 350 MB，建议预留至少 1.5 GB。

1. 将 APK 下载到手机，允许该浏览器或文件管理器安装应用。
2. 在手机系统设置配对 Shokz，打开媒体音频；使用耳机麦克风时也需打开通话音频。
3. 音量先设为较低的非零档位，应用要求不超过对应系统最大音量档位的三分之一。它只是操作保护，**不能保证实际声学安全**。
4. 启动应用，授权麦克风和蓝牙连接；通知权限可拒绝。
5. “档案”新建使用者代号；在固定佩戴和固定系统音量下测听。
6. 先左侧输出、再右侧输出，最后独立双侧联调。保存舒适参考，在“微调”调整平衡并记录评价。
7. 回到“助听”选择模式与算法，点击开始。任何不舒适、耳鸣或头晕立即停止。

耳机、媒体/通话路由或系统音量改变后，请新建档案重测。蓝牙通话模式是单声道，左右按钮均测试同一输出，不能得到独立左右结果。vivo 后台省电策略若中断音频，可在系统应用设置中允许后台运行。

## 实际实现

| 功能 | 实现与边界 |
|---|---|
| 个体相对响应曲线 | 125/250/500/1000/2000/3000/4000/6000 Hz；左右输出与双侧各8点；10 dB下降/5 dB上升阶梯、转折估计、静音试次、无响应标记、舒适参考。单位dBFS，**不是dB HL，也不估计听力损失程度** |
| 模式1 | 逐输出通道8频带相对拟合、手动增益、WDRC动态压缩、数字限幅 |
| 模式2 | 全频段线性压缩至目标区间，或保留低频并压缩搬移高频；有损改变音色，不保证提高语言理解 |
| 模式3 | 离线流式字幕 → 离线Melo语音合成 → 抗混叠重采样 → 同一频率映射/压缩/限幅；支持编辑文字后重述 |
| 增强算法 | **GTCRN、DPDFNet2** 两种真实流式神经模型；另有传统Wiener和关闭降噪作为对照 |
| 识别模型 | 2025中文小Zipformer CTC；2023中英Zipformer Transducer，可选择 |
| 输出保护 | 渐入渐出测试音、默认−24dBFS峰值上限、更保守的舒适参考限制、窄带啸叫检测衰减、路由/音量改变停止、音频焦点丢失停止、通知栏停止、20分钟会话时限 |
| 输入设备 | 手机麦克风 + 蓝牙媒体；耳机麦克风 + SCO/LE通话。显示实际输入/输出设备 |
| 档案 | 多人独立曲线和参数、本地持久化、JSON导出、双侧试听评价；字幕仅在当前进程保留，导出须主动操作 |
| 隐私 | 不申请Internet权限，不上传、不存录音、不依赖云识别；导出的JSON可能包含字幕，请自行保管 |

不能声称已实现的内容：临床NAL-NL2/DSL处方、校准dBHL/声压输出、完整自适应反馈抵消、骨传导真正隔离分耳测听、低于5ms的蓝牙往返、所有论文的最新模型、临床有效性。

模式3是**句子级重述**。合成和播放期间可能产生延迟；播放时暂停识别并丢弃麦克风输入以避免自转写，因此会漏掉同时讲话。繁忙队列的句子保留字幕并计数，不无限堆积。重要内容须核对字幕。

## 验证与文档

- [研究选型：论文、开源工程、许可证及已集成/候选区分](docs/RESEARCH.md)
- [模型清单、来源、SHA-256与真实推理验证](docs/MODELS.md)
- [DSP算法和数值测试](docs/DSP.md)
- [首次安装与安静环境技术试用协议](docs/CLINICAL_TEST_PROTOCOL.md)
- [本次交付验证结果与待真机测试项](docs/VALIDATION.md)

桌面与模拟器测试不能替代 vivo X100 Ultra + **具体 Shokz 型号**的蓝牙路由、延迟、听感、温升与实际声学测量。普通消费骨传导耳机也不能等同于医疗骨导设备。

## 构建

需要 JDK 17或21、Python3、Android SDK平台35/build-tools35和Gradle8.11.1。已锁定AGP8.9.1、Kotlin2.1.20、sherpa-onnx2.0.1。模型与AAR不提交Git，但下载清单、许可证、哈希和脚本均保留。

```powershell
python scripts/fetch-models.py
python scripts/fetch-models.py --verify-only
# 在local.properties设置sdk.dir，或设置ANDROID_HOME
.\gradlew.bat :app:assembleRelease :app:lintDebug
```

Windows本地可用 `python scripts/bootstrap-local.py` 安装项目内Gradle和SDK命令行工具，再运行 `scripts/build.ps1 -InstallSdk` 安装SDK组件及构建。SDK许可需要用户接受，脚本会提示。日常构建运行 `scripts/build.ps1`。

```powershell
powershell -ExecutionPolicy Bypass -File tests/dsp/run.ps1
.\gradlew.bat connectedDebugAndroidTest
python scripts/verify-models-desktop.py
```

Release产物位于 `app/build/outputs/apk/release/`。当前是**测试签名**，不是面向应用商店的正式发行签名；不得用该签名作为生产发布承诺。

## 架构

```text
手机/耳机麦克风 → 16kHz单声道采集 → GTCRN/DPDFNet/Wiener/关闭
  ├ 模式1 → 8频带拟合 + WDRC → 限幅 → 耳机
  ├ 模式2 → 频率映射 + 拟合/WDRC → 限幅 → 耳机
  └ 模式3 → Zipformer字幕 → Melo TTS → 16kHz重采样
                          → 频率映射 + 拟合/WDRC → 限幅 → 耳机
```

媒体输出保留左右通道；蓝牙通话输出为单声道。蓝牙缓冲不受应用完全控制。主循环计时包含播放写入等待，不能当作端到端延迟。
