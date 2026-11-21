from hashlib import sha256
import io
import json
from pathlib import Path
from urllib.request import Request, urlopen
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root/'build-support/engine-sha256.json').read_text())
apk_url = 'https://github.com/yinyangeqlab/yinyang-android/releases/download/android-20261121-0162/YinYang-Companion.apk'

with urlopen(apk_url, timeout=45) as response:
    apk = ZipFile(io.BytesIO(response.read(15_000_001)))
for relative, expected in manifest.items():
    output = root/relative
    name = output.name
    if output.is_file():
        data = output.read_bytes()
    elif name in {'autoeq-wasm.mjs','autoeq-wasm.wasm'}:
        request = Request('https://yinyangeq.com/vendor/peqdb-autoeq/'+name, headers={'User-Agent':'YinYangEQ-Lab-Build/1.0'})
        with urlopen(request, timeout=30) as response:
            data = response.read(15_000_001)
    else:
        data = apk.read('assets/'+name)
    if sha256(data).hexdigest() != expected:
        raise SystemExit('Unexpected SHA-256 for '+name)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
print('Verified the six pinned public offline EQ build inputs.')
