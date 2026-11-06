#!/usr/bin/env python3
"""Download a pinned official JetBrains SDK and check its published SHA-256."""
import hashlib
from pathlib import Path
import tarfile
import urllib.request

base = Path('build/sdk')
base.mkdir(parents=True, exist_ok=True)
url = 'https://download.jetbrains.com/idea/idea-263.7307.44.tar.gz'
archive = base / 'idea.tar.gz'
urllib.request.urlretrieve(url, archive)
expected = urllib.request.urlopen(url + '.sha256').read().decode().split()[0]
digest = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()
if digest != expected:
    raise SystemExit('JetBrains SDK checksum mismatch')
with tarfile.open(archive) as sdk:
    sdk.extractall(base, filter='data')
archive.unlink()
contents = next(base.glob('idea-*/product-info.json')).parent
Path('build/sdk-path.txt').write_text(str(contents.resolve()))
print(contents)
