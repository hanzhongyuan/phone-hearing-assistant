# 离线模型、集成契约与验证

核验日期：2026-11-10。应用使用 sherpa-onnx **2.0.1** 的真实 Android JNI / ONNX Runtime，模型直接打包在 APK 内。录音、识别和合成无需云服务、账号或系统语音引擎；应用不向网络发送音频。这里的模型清单是实际交付内容，不把研究报告中的候选模型当作已经集成。

## 打包模型

| 功能 | 实际权重 | 文件体积（约） | 许可与用途 |
|---|---|---:|---|
| 低算力语音增强 | GTCRN `gtcrn_simple.onnx` | 0.54 MB | MIT；16 kHz 因果流式增强 |
| 较强语音增强 | DPDFNet2 `dpdfnet2.onnx` | 10.25 MB | Apache-2.0；16 kHz 流式增强，最大噪声衰减设为 24 dB |
| 默认中文字幕 | Zipformer small CTC zh int8，2025-04-01 | 26.34 MB | 原始作者模型 Apache-2.0；中文流式识别 |
| 中英混合字幕 | Zipformer bilingual zh-en，2023-02-20，int8 三模型 | 198.21 MB | Apache-2.0；较大且较旧，适合中英混合对照测试 |
| 中文 / 英文语音重述 | MeloTTS zh_en VITS int8 | 53.52 MB + 6.84 MB 词典 | MIT；先生成语音 PCM，再重采样、频率映射及限幅 |
| 推理运行时 | sherpa-onnx 2.0.1 AAR | 49.44 MB，包含多个 ABI | Apache-2.0；最终 APK 可筛选 ABI |

所有资产和 AAR 总计约 **345.44 MB**，其中模型资产约 296 MB。安装后需要额外空间和推理内存。选择中英混合识别时内存明显高于默认中文识别。表中体积为下载原始文件体积，最终 APK 体积以交付构建为准。

这里没有声称任一模型在所有噪声、语言、听损人群上都是最佳。中文小 CTC 的发布时间较双语模型新；保留双语模型是为了语言覆盖和离线可运行性。不同噪声场景可能适合不同增强模型，需用实际手机测量负载、发热、延迟和理解率。

## 原始来源与精确版本

- [sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)，AAR SHA-256：`a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`。
- [GTCRN 和 DPDFNet ONNX 发布](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models)。GTCRN 校验值：`e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534`；DPDFNet2 校验值：`ce35d6025fc71df0ef10d1540e1b7916837bbfe5f6896deb744508d2cad487a9`。
- [中文小 CTC 转换模型](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01)，固定 revision `a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da`。[原作者 checkpoint 与 Apache-2.0 标识](https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01)。
- [中英 Zipformer 转换模型](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20)，固定 revision `98590b7ed6443e77b714204da2757d75e1a642f4`。
- [MeloTTS 中英 ONNX 与 MIT LICENSE](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en)，固定 revision `a0d5c6a264c0ef92d70d8661d8cc502d79627cd6`。模型 LICENSE 同时随 APK 放入 `models/tts-melo/LICENSE`。其余运行时、GTCRN、DPDFNet、ONNX Runtime 许可证正文及 ASR 作者模型卡保存在 `models/licenses/`，下载脚本使用固定源码 revision 并记录哈希。
- [sherpa-onnx 2.0 TTS API](https://k2-fsa.github.io/sherpa/onnx/c-api/html/tts.html)：2.0 不再使用 `data_dir` 内的 espeak-ng 词典。本应用直接使用 Melo 的 `lexicon.txt`、`tokens.txt` 和日期/数字/电话号码/多音字 FST，不依赖弃用的 data_dir。

## 可重复下载

在项目根目录运行：

```powershell
python scripts/fetch-models.py
python scripts/fetch-models.py --verify-only
```

脚本只使用 Python 标准库，并行下载到 `.part` 后检查 SHA-256，通过后原子替换。ONNX 权重使用 Hugging Face LFS 或 GitHub 公布的校验值；精确路径、不可变 revision、所有实际下载内容的 SHA-256 和字节数保存在 `app/src/main/assets/models/manifest.json`。GTCRN 的旧 release 未发布官方 digest，使用本次取得内容的固定 SHA-256，来源限制见清单。请保留 manifest 到版本控制；模型大文件可通过脚本重新取得，不必提交进 Git 历史。

## Kotlin 集成接口

源文件：`app/src/main/java/org/example/phonehearingassistant/ml/SpeechModels.kt`。

```kotlin
val models = SpeechModels(context)
// 这些初始化必须在后台线程执行。
models.loadDenoiser("gtcrn") // "dpdfnet" 或 "off"
models.loadAsr("zh")        // 或 "bilingual"
models.loadTts()

// 一个串行音频/识别工作线程：16 kHz、单声道、[-1,1] 浮点 PCM。
val enhanced: FloatArray = models.denoise(input)
val update: Recognition? = models.accept(input)
// update.text 是识别结果，update.isFinal 表示断句后结果。

// 单独的串行合成线程：避免阻塞实时录音和字幕。
val generated = models.synthesize("你好，这是助听测试。", speed = 1f)
val pcm16k = SpeechModels.resample16k(generated)
// pcm16k 必须再经过听力 DSP、频率映射和限幅，不能直接裸播。
models.close() // 等待正在进行的 JNI 调用完成再释放。
```

### 必须遵守的流式细节

1. **增强输出长度不是输入长度。** GTCRN 首个 256-sample 输入返回空，后续返回 256；DPDFNet2 的 frame shift 是 160，256-sample 输入会返回 0/160/320 等长度。将所有返回样本入 FIFO，仅在累计足够 256 时交给听力 DSP；启动空输出不能用原声替代，否则会错位。两者均为 16 kHz 模型。
2. 同一增强器、ASR stream 或 TTS 对象不能并行进入 JNI。本封装为三类独立锁，TTS 不阻塞 ASR 锁；`close()` 先禁止新工作，等待已有工作结束后释放原生资源。
3. 增强切换和路由变化应停止会话并重新初始化，清空音频 FIFO、OLA 和字幕状态。不要把旧模型余音插入新模型时间线。
4. ASR 在尾部静音 0.8 秒（已有语音时）、纯静音 2.4 秒或最长 12 秒时断句，返回 final 后重置流式解码状态。未说话时不会为了刷新界面伪造字幕。
5. TTS 是**句子级处理**。只将 final 结果入有限长度的重述队列，避免反复朗读 partial。播放时应阻断本机 TTS 回灌到 ASR，结束后重置 ASR 并留出蓝牙尾音时间。
6. `synthesize` 最多接受 300 个字符；调用方应按句分割并给队列设置上限。生成失败应明确显示错误，不应切换到未告知用户的云服务。
7. Melo 输出采样率是 **44.1 kHz**。内置窗口化 sinc 重采样器带抗混叠低通，转为 16 kHz 后才交给统一听力链路。音频最终限幅由 DSP 负责；任何 dBFS 限幅都不等于校准的耳内声压上限。

## 已完成的实际模型验证

验证环境：Windows x64，Python 3.13，sherpa-onnx **2.0.1**，CPU。使用同一套 APK 模型文件，真实载入 ONNX 并执行推理，而非 mock。下面的耗时仅是本机单次 smoke test，**不能作为 Android 手机、蓝牙往返延迟或临床有效性的证据**。

| 测试 | 输入 / 输出 | 结果 |
|---|---|---|
| GTCRN 流式 | 32,000 样本，256 分块；flush 后输出 32,000 | 全部有限数，首次长度 `[0,256,256,256,256,256]`；计算 0.147 s，桌面 RTF 0.073 |
| DPDFNet2 流式 | 同上；flush 后输出 32,000 | 全部有限数，首次长度 `[0,320,160,320,320,160]`；计算 0.374 s，桌面 RTF 0.187 |
| 中文 CTC | 原作者 test_wavs/0.wav，16 kHz，5.6115 s | 8 次 partial 更新，识别非空，endpoint=true；计算 0.211 s |
| 中英 Zipformer | 原作者 test_wavs/0.wav，16 kHz，10.0531 s | 16 次 partial 更新，识别非空，endpoint=true；计算 0.669 s |
| MeloTTS | “你好，这是骨传导助听测试。请保持舒适的音量。” | 176,580 个有限 PCM 样本，44.1 kHz，4.004 s 语音；生成耗时 **7.46 s** |
| Android AAR 结构 | 检查下载 AAR 内 classes.jar | `OnlineSpeechDenoiser`、`OnlineRecognizer`、`OfflineTts` 和配置类存在，版本与源码 API 一致 |
| 16 KB 内存页 | 检查 ARM64 四个原生库的 ELF PT_LOAD | 所有 segment 的 p_align 均为 16384；最终 APK 仍需 zipalign 检查 |

中文识别样例实际输出：“对我做了介绍那么我想说的是呢大家如果对我的研究感兴趣呢”。中英样例实际输出：“昨天是 MONDAY TODAY IS LIBR THE DAY AFTER TOMORROW是星期三”。后者包含识别错误，明确说明模型需要真实场景评估，不能把字幕或重述当作可靠的事实来源。

TTS 的上述耗时高于生成语音时长：模式 3 应显示“正在生成重述”、保持原始字幕可见并限制待播放队列。它不是低延迟声学助听模式的等价替代。真正实时的声学模式使用模式 1/2 的增强与 DSP；语音重述是另一条带较高延迟的辅助链路。

尚需真机完成：各手机 ARM64 推理 RTF、持续 30 分钟温升和耗电、骨传导耳机实际往返延迟、佩戴压力与接触位置重复性、中文方言/多人声/噪声条件下理解率、TTS 自反馈隔离。模型 smoke test 不替代这些项目。


## 重跑 native 与 Android 集成测试

桌面测试可重复执行，首次取得测试 WAV 后可完全断网：

```powershell
python -m pip install --target .model-validation sherpa-onnx==2.0.1 numpy
python scripts/verify-models-desktop.py
python scripts/verify-models-desktop.py --offline
```

参考音频固定到模型仓库 revision，并检查明确的 SHA-256。结果写入 `build/model-validation/desktop-results.json`；生成语音仅写 WAV 文件，不自动播放。该脚本运行增强、流式识别和中文生成的真实 native 模型，不进行模拟器或真机性能推断。

Android instrumentation 类为 `ModelsInstrumentedTest`，直接使用安装的 APK assets 和 sherpa JNI，测试五项：GTCRN、DPDFNet、两种流式识别、中文 TTS→16 kHz 抗混叠转换→模式 3 听力 DSP 数字限幅、关闭资源后禁止再推理。测试不会创建录音或播放对象，不使用扬声器。运行方式：

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.example.phonehearingassistant.ModelsInstrumentedTest
adb logcat -s ModelSmoke
```

`ModelSmoke` 日志标注实际 Android 环境和推理耗时；模拟器结果不能代表 ARM 手机性能或蓝牙实际延迟。是否完成以及最终结果以交付验证记录为准。
