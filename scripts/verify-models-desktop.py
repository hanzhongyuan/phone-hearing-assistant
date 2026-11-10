#!/usr/bin/env python3
"""Run actual bundled speech models on desktop sherpa-onnx 2.0.1; no audio playback.

Install a project-local validation runtime first:
  python -m pip install --target .model-validation sherpa-onnx==2.0.1 numpy
Then:
  python scripts/verify-models-desktop.py
Use --offline after reference WAVs have been cached. This is an integration smoke
check, not an intelligibility/clinical study or Android/Bluetooth benchmark.
"""
from pathlib import Path
import argparse
import hashlib
import json
import platform
import sys
import time
import urllib.request
import wave

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--offline', action='store_true')
args = parser.parse_args()
root = args.root.resolve()
cache = root / '.model-validation'
if cache.is_dir():
    sys.path.insert(0, str(cache))
try:
    import numpy as np
    import sherpa_onnx as sherpa
except ImportError as exc:
    raise SystemExit('Install: python -m pip install --target .model-validation sherpa-onnx==2.0.1 numpy') from exc
if sherpa.__version__ != '2.0.1':
    raise SystemExit('Expected sherpa-onnx 2.0.1, got ' + sherpa.__version__)
assets = root / 'app/src/main/assets/models'
out = root / 'build/model-validation'
out.mkdir(parents=True, exist_ok=True)
cache.mkdir(parents=True, exist_ok=True)
results = {
    'environment': {'platform': platform.platform(), 'python': platform.python_version(),
                    'runtime': sherpa.__version__, 'backend': 'CPU', 'kind': 'desktop smoke test'},
    'limitations': 'Single-run desktop compute times. No Android, Bluetooth, acoustic calibration or clinical claim.',
    'models': {}
}

def measured(name, data):
    results['models'][name] = data
    print(name, json.dumps(data, ensure_ascii=False), flush=True)

# Streaming speech enhancement: intentionally preserve variable output lengths.
for kind, filename in [('gtcrn', 'gtcrn.onnx'), ('dpdfnet', 'dpdfnet2.onnx')]:
    kwargs = {'num_threads': 1}
    model_file = str(assets / 'denoise' / filename)
    if kind == 'gtcrn':
        kwargs['gtcrn'] = sherpa.OfflineSpeechDenoiserGtcrnModelConfig(model=model_file)
    else:
        kwargs['dpdfnet'] = sherpa.OfflineSpeechDenoiserDpdfNetModelConfig(model=model_file, attenuation_limit_db=24)
    start = time.perf_counter()
    denoiser = sherpa.OnlineSpeechDenoiser(sherpa.OnlineSpeechDenoiserConfig(
        model=sherpa.OfflineSpeechDenoiserModelConfig(**kwargs)))
    load_s = time.perf_counter() - start
    assert denoiser.sample_rate == 16000
    signal = (np.random.default_rng(11).standard_normal(32000) * .02).astype('float32')
    start = time.perf_counter()
    chunks, lengths = [], []
    for cursor in range(0, len(signal), 256):
        block = denoiser.run(signal[cursor:cursor+256], 16000).samples
        chunks.append(block)
        lengths.append(len(block))
    chunks.append(denoiser.flush().samples)
    compute_s = time.perf_counter() - start
    output = np.concatenate(chunks)
    assert len(output) == len(signal) and np.isfinite(output).all()
    measured(kind, {'load_seconds': load_s, 'input_samples': len(signal), 'output_samples': len(output),
                    'frame_shift': denoiser.frame_shift_in_samples, 'first_output_lengths': lengths[:8],
                    'compute_seconds': compute_s, 'desktop_rtf': compute_s/2})
    del denoiser

# Fixed upstream sample revisions make the test inputs reproducible.
references = [
    ('zh', 'csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01', 'a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da'),
    ('bilingual', 'csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20', '98590b7ed6443e77b714204da2757d75e1a642f4')
]
for kind, repo, revision in references:
    wav_file = cache / (kind + '.wav')
    url = f'https://huggingface.co/{repo}/resolve/{revision}/test_wavs/0.wav'
    if not wav_file.exists():
        if args.offline:
            raise SystemExit(f'Reference WAV is not cached: {wav_file}. Run once without --offline.')
        request = urllib.request.Request(url, headers={'User-Agent': 'hearing-assistant-validation/1.0'})
        with urllib.request.urlopen(request, timeout=90) as response:
            wav_file.write_bytes(response.read())
    expected_hash = {'zh': '668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250', 'bilingual': '7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b'}[kind]
    assert hashlib.sha256(wav_file.read_bytes()).hexdigest() == expected_hash, 'Reference WAV checksum mismatch'
    with wave.open(str(wav_file)) as wav:
        assert wav.getsampwidth() == 2 and wav.getnchannels() == 1
        sample_rate = wav.getframerate()
        audio = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype('float32') / 32768
    directory = assets / ('asr-' + kind)
    start = time.perf_counter()
    common = {'tokens': str(directory / 'tokens.txt'), 'num_threads': 2,
              'enable_endpoint_detection': True, 'rule2_min_trailing_silence': .8}
    if kind == 'zh':
        recognizer = sherpa.OnlineRecognizer.from_zipformer2_ctc(
            model=str(directory / 'model.int8.onnx'), **common)
    else:
        recognizer = sherpa.OnlineRecognizer.from_transducer(
            encoder=str(directory / 'encoder-epoch-99-avg-1.int8.onnx'),
            decoder=str(directory / 'decoder-epoch-99-avg-1.int8.onnx'),
            joiner=str(directory / 'joiner-epoch-99-avg-1.int8.onnx'), model_type='zipformer', **common)
    load_s = time.perf_counter() - start
    stream = recognizer.create_stream()
    start = time.perf_counter()
    partials = []
    padded = np.concatenate([audio, np.zeros(int(sample_rate * 2.5), dtype='float32')])
    for cursor in range(0, len(padded), 256):
        stream.accept_waveform(sample_rate, padded[cursor:cursor+256])
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        text = recognizer.get_result(stream)
        if text and (not partials or partials[-1] != text):
            partials.append(text)
    text = recognizer.get_result(stream)
    assert text.strip() and len(partials) > 1
    assert recognizer.is_endpoint(stream)
    measured('asr_' + kind, {'load_seconds': load_s, 'source_url': url,
              'reference_sha256': hashlib.sha256(wav_file.read_bytes()).hexdigest(),
              'duration_seconds': len(audio)/sample_rate, 'output': text, 'partial_updates': len(partials),
              'endpoint': True, 'compute_seconds': time.perf_counter()-start})
    del stream, recognizer

# TTS outputs raw PCM into a file only. The Android test additionally covers DSP.
directory = assets / 'tts-melo'
config = sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
    vits=sherpa.OfflineTtsVitsModelConfig(model=str(directory/'model.int8.onnx'),
        lexicon=str(directory/'lexicon.txt'), tokens=str(directory/'tokens.txt')), num_threads=2),
    rule_fsts=','.join(str(directory/(name+'.fst')) for name in ['date','number','phone','new_heteronym']),
    max_num_sentences=1)
start = time.perf_counter()
tts = sherpa.OfflineTts(config)
load_s = time.perf_counter() - start
start = time.perf_counter()
text = '你好，这是骨传导助听测试。请保持舒适的音量。'
audio = tts.generate(text, sid=0, speed=1.0)
compute_s = time.perf_counter() - start
samples = np.asarray(audio.samples)
assert samples.size > audio.sample_rate / 2 and np.isfinite(samples).all()
assert np.max(np.abs(samples)) > 1e-5
with wave.open(str(out/'tts-smoke.wav'), 'wb') as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(audio.sample_rate)
    wav.writeframes((np.clip(samples, -1, 1)*32767).astype('<i2').tobytes())
measured('tts', {'load_seconds': load_s, 'text': text, 'samples': int(samples.size),
                'sample_rate': audio.sample_rate, 'compute_seconds': compute_s,
                'desktop_rtf': compute_s/(samples.size/audio.sample_rate)})
(out/'desktop-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print('PASS: All bundled models executed. Report:', out/'desktop-results.json')

