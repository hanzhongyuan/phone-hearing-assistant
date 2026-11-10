#!/usr/bin/env python3
"""Fetch pinned, offline speech models and sherpa-onnx AAR (Python stdlib only).
Run: python scripts/fetch-models.py .  (optional --verify-only)
Artifacts are verified against upstream LFS/GitHub SHA256 when published;
all downloaded files are recorded in assets/models/manifest.json.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse, hashlib, json, os, urllib.request, time

P = argparse.ArgumentParser()
P.add_argument('root', nargs='?', default=str(Path(__file__).resolve().parents[1]))
P.add_argument('--verify-only', action='store_true')
args = P.parse_args()
root = Path(args.root).resolve()
assets = root / 'app/src/main/assets/models'
manifest_path = assets / 'manifest.json'
items = []
def add(path, url, sha=''):
    items.append({'path': path, 'url': url, 'expected_sha256': sha})
def hf(folder, repo, revision, names):
    for filename, sha in names:
        add(f'app/src/main/assets/models/{folder}/{filename}',
            f'https://huggingface.co/{repo}/resolve/{revision}/{filename}', sha)
add('app/libs/sherpa-onnx-2.0.1.aar', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar', 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7')
add('app/src/main/assets/models/denoise/gtcrn.onnx', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx', 'e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534')
add('app/src/main/assets/models/denoise/dpdfnet2.onnx', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/dpdfnet2.onnx', 'ce35d6025fc71df0ef10d1540e1b7916837bbfe5f6896deb744508d2cad487a9')
hf('asr-zh', 'csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01', 'a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da', [('model.int8.onnx', '68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a'), ('tokens.txt','')])
hf('asr-bilingual', 'csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20', '98590b7ed6443e77b714204da2757d75e1a642f4', [
('encoder-epoch-99-avg-1.int8.onnx','8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b'),
('decoder-epoch-99-avg-1.int8.onnx','1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa'),
('joiner-epoch-99-avg-1.int8.onnx','1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0'),('tokens.txt','')])
hf('tts-melo', 'csukuangfj/vits-melo-tts-zh_en', 'a0d5c6a264c0ef92d70d8661d8cc502d79627cd6', [
('model.int8.onnx','f085f5079e05f039b800aeb542f5253c26a303211b0c6465d0d9387977855a63'),
('tokens.txt',''),('lexicon.txt',''),('number.fst',''),('date.fst',''),('phone.fst',''),('new_heteronym.fst',''),('LICENSE','')])

# Preserve upstream license texts and authorship/model cards with the APK.
add('app/src/main/assets/models/licenses/sherpa-onnx-LICENSE', 'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.1/LICENSE')
add('app/src/main/assets/models/licenses/GTCRN-LICENSE', 'https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73/LICENSE')
add('app/src/main/assets/models/licenses/DPDFNet-LICENSE', 'https://raw.githubusercontent.com/ceva-ip/DPDFNet/9bd9844a227bb6aa57e55588d8d0e961fcff1c46/LICENSE')
add('app/src/main/assets/models/licenses/ONNXRuntime-LICENSE', 'https://raw.githubusercontent.com/microsoft/onnxruntime/c6fb03fedd2e231c7d142bff6217c73442c72c5b/LICENSE')
add('app/src/main/assets/models/licenses/ASR-zh-author-model-card.md', 'https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01/resolve/6768aa8b65bc68e128af2ee08a7142dd0abe4be4/README.md')
add('app/src/main/assets/models/licenses/ASR-bilingual-model-card.md', 'https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/98590b7ed6443e77b714204da2757d75e1a642f4/README.md')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024), b''): h.update(chunk)
    return h.hexdigest()
old = {}
if manifest_path.exists():
    old = {x['path']:x for x in json.loads(manifest_path.read_text('utf-8'))['files']}

def fetch(item):
    dst = root / item['path']
    expected = item['expected_sha256'] or old.get(item['path'],{}).get('sha256','')
    if args.verify_only:
        if not dst.exists() or not expected or digest(dst) != expected:
            raise RuntimeError(f'Missing or invalid artifact: {dst}')
    elif not (dst.exists() and (not expected or digest(dst)==expected)):
        dst.parent.mkdir(parents=True, exist_ok=True)
        part = dst.with_name(dst.name+'.part')
        for attempt in range(4):
            try:
                print('Downloading', item['path'], flush=True)
                req=urllib.request.Request(item['url'],headers={'User-Agent':'hearing-assistant-model-fetch/1.0'})
                with urllib.request.urlopen(req,timeout=90) as response, part.open('wb') as out:
                    while True:
                        chunk=response.read(1024*1024)
                        if not chunk: break
                        out.write(chunk)
                if expected and digest(part)!=expected: raise RuntimeError('SHA256 mismatch: '+item['path'])
                os.replace(part,dst)
                break
            except Exception:
                if attempt==3: raise
                time.sleep(2**attempt)
    sha=digest(dst)
    if expected and sha!=expected: raise RuntimeError('SHA256 mismatch: '+str(dst))
    print('Verified',item['path'],dst.stat().st_size,flush=True)
    return {**item,'sha256':sha,'bytes':dst.stat().st_size}
with ThreadPoolExecutor(max_workers=4) as pool:
    results=list(pool.map(fetch,items))
if not args.verify_only:
    assets.mkdir(parents=True,exist_ok=True)
    manifest_path.write_text(json.dumps({'schema':1,'runtime':'sherpa-onnx 2.0.1','files':results},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('All artifacts verified. Total bytes:',sum(x['bytes'] for x in results),flush=True)

