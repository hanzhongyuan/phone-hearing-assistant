package org.example.phonehearingassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var running by remember { mutableStateOf(false) }
                var volume by remember { mutableFloatStateOf(0.15f) }
                var toneIndex by remember { mutableIntStateOf(0) }
                var heard by remember { mutableStateOf<Boolean?>(null) }
                var status by remember { mutableStateOf("尚未开始") }
                val frequencies = listOf(250, 500, 1000, 2000, 4000, 8000)
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        AudioEngine.start(this, volume)
                        running = true
                        status = "监听已启动。请保持低音量并佩戴有线耳机。"
                    } else status = "未获得麦克风权限。"
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("手机听力辅助 · 实验版", style = MaterialTheme.typography.headlineSmall)
                        Text("非医疗设备。请勿用于诊断或替代专业助听器；突然听力下降请及时就医。")

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("1 · 相对听音筛查", style = MaterialTheme.typography.titleMedium)
                                Text("${frequencies[toneIndex]} Hz · 自报是否听到。此测试未校准，不输出 dB HL 或听力图。")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        heard = null
                                        AudioEngine.playTestTone(this@MainActivity, frequencies[toneIndex], volume)
                                        status = "正在播放短测试音；请在安静环境、低音量下测试。"
                                    }) { Text("播放测试音") }
                                    OutlinedButton(onClick = { heard = true }) { Text("听到了") }
                                    OutlinedButton(onClick = { heard = false }) { Text("没听到") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { toneIndex = (toneIndex - 1).coerceAtLeast(0); heard = null }) { Text("上一频段") }
                                    OutlinedButton(onClick = { toneIndex = (toneIndex + 1).coerceAtMost(frequencies.lastIndex); heard = null }) { Text("下一频段") }
                                }
                                Text("本次记录：${heard?.let { if (it) "听到" else "没听到" } ?: "尚无回答"}（仅当前界面，未保存）")
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("2 · 助听模式 1：保守均衡", style = MaterialTheme.typography.titleMedium)
                                Text("当前为宽带音量演示；尚未实现基于临床听力图的多频段压缩或拟合。")
                                Text("音量上限：${(volume * 100).toInt()}%（软件值，不等于声压级）")
                                Slider(value = volume, onValueChange = { volume = it.coerceIn(0.05f, 0.30f) }, valueRange = 0.05f..0.30f)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        if (running) {
                                            AudioEngine.stop(); running = false; status = "监听已停止。"
                                        } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            AudioEngine.start(this@MainActivity, volume); running = true; status = "监听已启动。请使用有线耳机并保持低音量。"
                                        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }) { Text(if (running) "停止监听" else "启动监听") }
                                    OutlinedButton(onClick = { AudioEngine.stop(); running = false; status = "监听已停止。" }) { Text("紧急停止") }
                                }
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("3 · 助听模式 2：频率降低（规划中）", style = MaterialTheme.typography.titleMedium)
                                Text("频率压缩/移位需要个体化设置和言语可懂度验证；当前版本不改变频率，避免误称有效。")
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("4 · 助听模式 3：实时字幕（规划中）", style = MaterialTheme.typography.titleMedium)
                                Text("后续可接入设备端流式语音识别，并提供可选文字转语音。识别字幕和助听是互补功能；文字不能直接等同于频率映射。")
                            }
                        }

                        Text("状态：$status")
                        Text("请勿用扬声器监听；反馈啸叫、失真、不适时立即停止。不要在交通或需要环境警觉的场景使用。")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        AudioEngine.stop()
        super.onDestroy()
    }
}
