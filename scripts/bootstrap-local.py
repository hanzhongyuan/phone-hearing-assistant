"""Install official build tools inside this checkout (Windows)."""
from pathlib import Path
import urllib.request, zipfile, concurrent.futures, hashlib
root=Path(__file__).resolve().parents[1]/'.tools'
root.mkdir(exist_ok=True)
items=[('https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip','android-tools.zip','sdk'),('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip','gradle.zip','')]
def get(item):
    url,name,dest=item
    path=root/name
    if not path.exists():
        print('Downloading '+name,flush=True)
        urllib.request.urlretrieve(url,path)
    if name=='gradle.zip':
        digest=urllib.request.urlopen(url+'.sha256').read().decode().strip()
        assert hashlib.sha256(path.read_bytes()).hexdigest()==digest, 'Gradle SHA-256 mismatch'
    if not (root/dest/('cmdline-tools' if name=='android-tools.zip' else 'gradle-8.11.1')).exists():
        with zipfile.ZipFile(path) as archive: archive.extractall(root/dest)
    print('Ready '+name,flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: list(pool.map(get,items))
