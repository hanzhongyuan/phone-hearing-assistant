# 助听应用：研究证据、选型与实现边界

调研日期：2026-11-10（Asia/Shanghai）。对象：Android 手机 + 蓝牙耳机，当前目标设备 vivo X100 Ultra + Shokz；工程最低兼容目标 Android 8.0 / API 26。此文将已公开的论文、官方实现、模型权重与本项目采用的工程方案分别记录。检索不是系统综述；“最新”“最先进”应在任务、算力、时延、听力类型和测试集相同的条件下比较，不能由论文年份或 GitHub 星数推导。

## 1. 选型结论

本轮选择 **sherpa-onnx 2.0.1 + GTCRN / DPDFNet + 本地流式 Zipformer ASR + 本地中文 TTS**，搭配可独立测试的经典 DSP：分频增益、WDRC、数字输出限幅、啸叫检测/衰减、实验性频率映射。让神经网络负责语音任务，让输出约束独立于模型工作。最终 APK 是否已经封装各个模型、运行测试是否通过，以构建产物、模型清单和验证记录为准；此文不把“选择了集成路线”写成“已完成真机验证”。

sherpa-onnx 官方 **v2.0.1 发布于 2026-10-26**，提供 Android AAR、Java/Kotlin 接口；runtime 为 Apache-2.0。官方 AAR SHA-256：`a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`。模型权重的许可必须逐个核对，不能继承 runtime 的许可。[官方 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)、[runtime 许可](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)

## 2. 语音增强：经典基线、顶会/顶刊和近期候选

| 方案 | 核实的出版/更新时间 | 开源及端侧依据 | 本项目定位 |
|---|---|---|---|
| GTCRN | ICASSP 2024；2025-03 接入 sherpa-onnx；2026-01 有社区 Linux 实时插件 | 官方修正后 48.2K 参数、33 MMAC/s；MIT；有流式状态 | 本轮低算力神经降噪选择。16 kHz 语音输入；不能把计算量当手机实测耗电 |
| DPDFNet | arXiv 2025-12-18，2026-06-16 修订 | 官方 Apache-2.0；代码与预训练模型；8/16/48 kHz，ONNX/TFLite；sherpa 有在线接口 | 本轮另一增强选择。本文未确认顶会接收，不标成顶会获奖模型 |
| UL-UNAS | IEEE TASLP 2026，卷 34，1085–1096；2026-02-04 作者宣布接收，02-13 提供流式 ONNX | 官方 MIT、checkpoint 和流式代码 | 近期强候选；未接入本 APK。需要比 GTCRN 的真机延迟、能耗、失真后再替换 |
| DeepFilterNet 系列 | DFN：ICASSP 2022；DFN2：IWAENC 2022；DFN3：Interspeech 2023 | 48 kHz、Rust 实时实现；当前官方 README 明确代码和仓库模型权重 MIT / Apache-2.0 双许可 | 全带宽基准候选；本轮未接入。不要混淆 DFN2、DFN3 与 DPDFNet |
| RNNoise | MMSP 2018；官方训练说明含 2025-01 数据更新 | C / 48 kHz，代码 BSD-3-Clause；独立下载的新权重许可存在尚未关闭的作者询问 issue | 成熟低资源比较基线；本轮未集成，不宣称所有当前模型已完成许可核验 |
| DTLN | Interspeech 2020 | 作者 MIT；官方提供 SavedModel、TFLite、ONNX，Raspberry Pi 实时示例 | 工程成熟的比较基线；未集成，不能称 2026 最先进 |
| PercepNet / Personalized PercepNet | Interspeech 2020 / 2021 相关工作 | 感知频带、周期性和目标说话人思路有价值；本轮未核实可直接分发的作者 Android 包/完整权重许可 | 文献参考；不把第三方复现当官方模型 |
| ClearerVoice-Studio | Interspeech 2025；arXiv 2025-06 | 官方 Apache-2.0 工具箱，增强/分离/目标提取等多模型 | 离线质量对照候选。不能将离线高分等同于低时延手机助听 |

表格依据：[GTCRN 官方](https://github.com/Xiaobin-Rong/gtcrn)、[DPDFNet 官方](https://github.com/ceva-ip/DPDFNet)、[DPDFNet 论文](https://arxiv.org/abs/2512.16420)、[UL-UNAS 官方](https://github.com/Xiaobin-Rong/ul-unas)、[UL-UNAS 论文](https://arxiv.org/abs/2503.00340)、[DFN 论文及许可](https://github.com/Rikorose/DeepFilterNet)、[RNNoise 官方](https://github.com/xiph/rnnoise)、[RNNoise 权重问题 #284](https://github.com/xiph/rnnoise/issues/284)、[DTLN 官方](https://github.com/breizhn/DTLN)、[PercepNet 原论文](https://www.isca-archive.org/interspeech_2020/valin20_interspeech.pdf)、[ClearerVoice 原论文](https://www.isca-archive.org/interspeech_2025/zhao25f_interspeech.pdf)、[ClearerVoice 官方](https://github.com/modelscope/ClearerVoice-Studio)。

DPDFNet 官方流式实现需积累首个约 20 ms 窗，常规 hop 约 10 ms；这只是模型的缓冲机制，不是手机到耳机总延迟。sherpa 文档还明确：DPDFNet 的 `attenuation_limit_db` 当前仅离线实现，在线配置不能依靠它限制增强强度。本项目的最终输出限制需要在独立 DSP 中实施。[DPDFNet 流式说明](https://github.com/ceva-ip/DPDFNet#streaming-real-time-api)、[sherpa DPDFNet 参数](https://k2-fsa.github.io/sherpa/onnx/speech-enhancement/dpdfnet.html)

另有近期前沿值得跟踪，但没有因此宣称已经可安装到本手机：

- **Ultra-Low Latency Speech Enhancement – A Comprehensive Study，ICASSP 2025**：在受控条件研究小于约 5 ms 的助听增强延迟。它说明助听与普通会议降噪的约束不同，不能拿本 APK 的蓝牙链路冒充此延迟等级。[作者/微软原文](https://www.microsoft.com/en-us/research/wp-content/uploads/2025/03/Microsoft___LowLatencySE___ICASSP_2025.pdf)
- **SpikeAid，2026-10-17 arXiv**：联合噪声抑制与 WDRC 的脉冲网络和 FPGA 研究。论文结果不等于已有可分发 Android 引擎；本轮没有确认对应公开可用权重与可复现手机推理包。[原论文](https://arxiv.org/abs/2610.24696)
- **CHiME-9 ECHI，2026-09**：面向听力损失的会话增强挑战，含眼镜/助听器麦克风数据。需要多传感器条件；不能认为单个普通手机麦克风能复现多通道结果。[挑战论文](https://arxiv.org/abs/2609.26306)

## 3. ASR、字幕与中文重述

Zipformer 是本轮流式识别主线：保留增量状态、输出临时字幕与句终字幕，避免每块音频重复完整推理。官方 Android 示例、预训练目录和论文均可核查。大模型更新不一定适合所有手机：2025-06-30 中文大模型的 int8 encoder 单文件约 154 MB，且本轮没有将其源 checkpoint 的权利条件自动视为开放。具体选用的小模型与词表以工程模型清单为准。[Zipformer 论文](https://arxiv.org/abs/2310.11230)、[官方模型目录](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)、[Android 官方构建](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/android/build-sherpa-onnx.rst)

SenseVoiceSmall 可作中文、多语言句级识别候选；官方将其在线演示称为“模拟流式”，本轮不将其误称为原生逐块流式。Kokoro、多说话人 VITS、Matcha 等可作中文 TTS 对照，但必须单独检查每组权重、词典、音素工具及语音数据权利。无需为助听引入克隆个人声音功能。[SenseVoice 官方模型说明](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/sense-voice/pretrained.rst)、[TTS 模型目录](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html)

sherpa-onnx **2.0.0 起 VITS Piper 的 `data_dir` 已弃用且忽略，不再内置使用 espeak-ng**。选择相应模型时需完整词典或预音素化输入，不能照抄 1.x 的配置后只验证“编译成功”。[官方 C API](https://k2-fsa.github.io/sherpa/onnx/c-api/html/tts.html)

模式 3 的含义是“识别后重新表达”。最终字幕可能出错，不能保证忠实还原原话。重述采用短句、有限排队、可关闭播放；播报音频再经过与模式 2 一致的频率映射和独立限幅。调低 TTS 的 pitch 或语速本身不等价于把全部频谱搬入可听区。TTS 播放期间应避免麦克风把合成语音反复识别；若采用半双工暂停收音，必须告知会漏掉同时发生的说话。它具有句终等待、ASR 与合成延迟，应与实时环境聆听模式分开衡量。

## 3.1 本轮选定的准确模型和许可核验

| 用途 | 模型/制品 | 核验结果 |
|---|---|---|
| 低算力增强 | `gtcrn_simple.onnx`，sherpa `speech-enhancement-models` release | 上游 GTCRN MIT；保留 Rong Xiaobin 版权和上游来源 |
| 另一增强选择 | `dpdfnet2.onnx`，同一 release | Ceva 官方模型卡 Apache-2.0；16 kHz；不是表中的48k版本 |
| 默认中文流式字幕 | `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01` | 转换页指向作者原始 checkpoint；原始模型卡现已标 Apache-2.0 |
| 可选中英字幕 | `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` 的 int8 encoder/joiner 与 decoder | 转换页及 pfluo 原始 checkpoint 均标 Apache-2.0 |
| 中文/中英混合重述 | `csukuangfj/vits-melo-tts-zh_en/model.int8.onnx` 和词典/FST | 转换仓库自带 MIT LICENSE，Copyright 2024 MyShell.ai；原始 MeloTTS-Chinese 模型卡亦标 MIT |

直接证据：[小 CTC 转换页](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01)、[小 CTC 作者原始模型许可](https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01/blob/main/README.md)、[双语转换页](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20)、[双语原始权重](https://huggingface.co/pfluo/k2fsa-zipformer-chinese-english-mixed)、[Melo 转换包许可文件](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/blob/main/LICENSE)、[原始 Melo 模型卡](https://huggingface.co/myshell-ai/MeloTTS-Chinese)、[DPDFNet 权重模型卡](https://huggingface.co/Ceva-IP/DPDFNet)。

这一步特意沿转换来源追溯权重许可，未把缺失 YAML 标签误认作“无许可”或“随 runtime 许可”。构建归档还应记录每个下载文件的 URL、SHA-256、实际大小，并包含许可文本。量化版本、词表和模型类型必须匹配；模型缺失/初始化失败不能在界面显示已启用。Melo 是本地 TTS 工程选型，未宣称它是最新 TTS 排行第一。
## 4. 听力图、拟合与确定性的 DSP

**设备相对听感图和临床听力图是两类数据。** 手机纯音的 dBFS 是数字幅度，不能直接显示成 dB HL 或 dB SPL。不同手机音量、蓝牙绝对音量、耳机 EQ、佩戴压力和频响均会改变结果。Shokz 型号尚需记录；骨传导会跨耳传播，左右声道并不能隔离左右耳蜗，未做临床掩蔽不能得到独立双耳骨导阈值。ASHA 指南也明确未掩蔽骨导阈值通常无法归属某一耳蜗。[ASHA 符号和掩蔽说明](https://www.asha.org/policy/GL1990-00006/)、[ASHA 骨导测量](https://www.asha.org/practice-portal/clinical-topics/hearing-loss)

相对测试保存频率、设备/声道、数字幅度、系统音量、未听到/未测试标记、测试时间；不将“最大允许幅度仍没听见”记成确切阈值。重复测试时保持同一条硬件链路。测听时停用降噪、WDRC、均衡和 TTS。听到音调与感到振动必须区别记录。临床听力图应由专业检查获得，通过单独入口录入，并保留其单位和来源。

| 功能 | 本轮工程路线 | 不能声称的内容 |
|---|---|---|
| 个体化增益 | 根据测得的设备相对听感差异或明确输入的听力资料生成有上限的实验性频段曲线 | 不能把半增益/启发式算法称为已授权 NAL-NL2 或 DSL |
| WDRC | 频段包络、压缩拐点、压缩比、攻击/释放平滑；验证静音、阶跃、饱和及连续状态 | 数字拐点不能直接解释为耳内声压 |
| 输出限幅 | 独立于所有神经网络的样本约束、非有限值归零、渐入/急停 | dBFS 限幅不能保证特定耳机的安全声压或骨导振动力 |
| 反馈管理 | 窄带持续峰值检测和衰减/陷波；受控测试误抑制纯音和乐音 | 啸叫抑制不等同于已辨识闭环声路径的自适应反馈抵消 AFC |
| 频率映射 | 可调整目标频带的实验映射，保留独立停止与保守增益 | 有限带宽映射不能无损保持全部声音的信息或保证更清晰 |

openMHA 是完整助听研究参考，包括校准、WDRC、反馈抵消；当前 README 发布版本为 4.18.1（2026-06-06）。其 NAL-NL2/DSLmio5 商用处方库要向原厂单独取得，不能从“openMHA 开源”推导该处方也可以任意复制。本轮没有将 openMHA 引擎编进 APK。[官方说明](https://github.com/HoerTech-gGmbH/openMHA)、[校准手册](https://www.openmha.org/docs/openMHA_calibration_manual.pdf)

频率降低是否改善理解必须对人验证。近期严重/极重度听损管理指南建议先尝试常规频率增益，再按实际需要启用频率降低，用能达到可听度的最小强度，并做主观、客观验证；它不是“搬到能听见的频率就一定听懂”的证据。[2026 更新指南](https://pmc.ncbi.nlm.nih.gov/articles/PMC13709693/)

## 5. 安卓与蓝牙架构

建议链路：手机麦克风 → 单路采集及幅度检查 → 可选神经降噪 → 个体化频段 DSP / 频率映射 → 输出限制 → 已确认的耳机媒体输出。字幕使用独立工作队列；神经模型、TTS 不在音频回调中装载。关闭助听、路由改变、耳机拔除、音频焦点丢失、读写错误应停止输出并清理资源。参数切换需要清空状态/渐变，避免爆音。

首选验证“手机麦克风 + 蓝牙媒体播放”。若切到经典蓝牙耳机麦克风，可能改用双向通信配置并改变带宽、音质和时延；LE Audio 的输入输出能力依赖手机、耳机与系统共同支持。Android 官方说明 LE Audio 可支持 32 kHz 双向音频，但这不是对当前 vivo + 未指定 Shokz 型号的兼容保证。Android 8 不具备较新 LE Audio API，必须使用降级分支。[Android BLE Audio](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)、[录音路由](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)

开放式耳机保留直达声，处理声延迟后叠加会产生回声/梳状失真。不能只报告模型耗时；需分别测算法时间、采播排队、端到端延迟及长时间漂移。在未进行声学/机械测量前不展示猜测的“实际延迟 10 ms”。

## 6. 验证与后续可比较的实验

1. **离线正确性**：纯音扫频、静音、冲击、最大输入、非有限值、长音频；检查限幅、频谱变化、重建连续性及队列上限。
2. **模型回归**：同一中文句子/相同噪声以 GTCRN、DPDFNet、无降噪处理；记录误删弱辅音、音乐失真及 ASR 字错率，不能只选听起来最安静的版本。
3. **手机工程**：本机实测 p50/p95 推理时间、启动耗时、音频 underrun、内存、发热、耗电与后台稳定性；每种路由分别记录。不同模型重复相同负载。
4. **可懂度**：以不同句表对照无处理/模式1/模式2，记录正确词数、主观舒适、听觉努力，不复用同一材料制造学习效应。模式3额外统计字幕错误、句终到首字/播报的时间和重述遗漏。
5. **专业评估后才能使用临床结论**：HASPI/HASQI 可作为离线指标；Clarity 工具明确需要参考干净信号、听力图及 dBFS 到声压的 level reference。无校准数据时不能把默认参考值当真实测量。[Clarity 基线及评估输入](https://claritychallenge.org/docs/cec2/software/cec2_core_software)、[开源工具](https://github.com/claritychallenge/clarity)

首次在人身上使用的具体步骤见 [受监督技术试用协议](CLINICAL_TEST_PROTOCOL.md)。本文的研究候选没有自动变成已启用功能，构建成功也不等于临床有效或当前手机已测试。


## 7. 相近开源应用与研究硬件

| 项目 | 可借鉴内容 | 本项目采用方式与边界 |
|---|---|---|
| [OpenHearing](https://github.com/HMAKT99/OpenHearing) | Android 相对测听、专业结果录入、声道档案、WDRC、啸叫防护、停止/路由保护、可分享图表；GPLv3 | 研究产品流程与测试策略，没有复制该代码。其 README 明确 alpha 且尚需真实硬件验证，不能将其“完整功能”当成疗效证据 |
| [HARK](https://github.com/thivyan-studios/hark-app) | Oboe/C++ 音频回调、前台服务、ASHA 优先的路由、sherpa-onnx Zipformer 字幕；BSD-3-Clause | 参考采播与字幕分线程及设备恢复设计，未将其固定高频增益当个体处方。当前 README 最低 API28，本项目需独立处理 API26兼容；其低延迟宣传不能替代 vivo+Shokz实测 |
| [openMHA](https://github.com/HoerTech-gGmbH/openMHA) | 研究级校准、压缩、反馈抵消、波束形成、参考配置 | 用于算法和校准对照；AGPLv3，商业处方库另取许可。当前 APK 并未打包其引擎 |
| [Tympan Library](https://github.com/Tympan/Tympan_Library) | Teensy/Arduino 专用音频硬件、浮点 WDRC、反馈消除示例 | 可作为将来硬件低时延路线，不能直接当安卓库。仓库当前 MIT，各源文件仍需保留自身声明和来源 |

OpenHearing 的设备测试文档区分了“模拟器可运行”和“真实音质、声压与声反馈尚未验证”；这是值得沿用的报告方法。[设备验证记录](https://github.com/HMAKT99/OpenHearing/blob/main/docs/DEVICE_TESTING.md)

HARK 当前 GitHub 默认分支 README 已写本地 sherpa-onnx，部分 raw 页面搜索缓存仍出现旧 Whisper 文字，本轮通过 GitHub Contents API 复核了当前 README，避免混用不同版本。[当前 README](https://github.com/thivyan-studios/hark-app#readme)

Tympan 存在明确的 `AudioEffectCompWDRC_F32` 实现和 `09-FeedbackCancellation` 示例；工程思路可以比较，硬件平台、采样时钟与输入输出拓扑不能与手机蓝牙直接等同。[WDRC 源文件](https://github.com/Tympan/Tympan_Library/blob/main/src/AudioEffectCompWDRC_F32.h)、[反馈示例](https://github.com/Tympan/Tympan_Library/tree/main/examples/09-FeedbackCancellation)、[仓库许可](https://github.com/Tympan/Tympan_Library/blob/main/LICENSE)

单侧测试后再双侧联调的流程可保留：先记录左输出声道的响应，再记录右输出声道，再以双声道实际佩戴条件复查舒适和可懂度；双侧记录单独保存，不用左右阈值平均值伪造。对 Shokz 骨传导，这三个条件是设备刺激/主观反应条件，不等于隔离左耳、隔离右耳和已校准的双耳测听。
