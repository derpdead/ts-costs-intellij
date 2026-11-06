#!/usr/bin/env python3
"""Build against a local JetBrains IDE SDK without modifying the installation."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--ide', type=Path, default=Path(os.environ.get('IDE_HOME', '/Applications/IntelliJ IDEA.app/Contents')))
parser.add_argument('--test-profile', type=Path)
args = parser.parse_args()
ide = args.ide
if (ide / 'Contents').is_dir():
    ide /= 'Contents'
suffix = '.exe' if os.name == 'nt' else ''
candidates = [ide / 'jbr/Contents/Home/bin', ide / 'jbr/bin']
bin_dir = next((directory for directory in candidates if (directory / ('javac' + suffix)).exists()), None)
if bin_dir is None:
    parser.error('The IDE SDK must include a JBR with javac (or set --ide / IDE_HOME to one).')
javac, java = bin_dir / ('javac' + suffix), bin_dir / ('java' + suffix)
version = ET.parse(root / 'src/main/resources/META-INF/plugin.xml').findtext('version')
classes = root / 'build/classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)
classpath = os.pathsep.join(str(file) for file in (ide / 'lib').glob('*.jar'))
sources = sorted((root / 'src/main/java').rglob('*.java'))
subprocess.run([str(javac), '--release', '21', '-encoding', 'UTF-8', '-classpath', classpath,
                '-d', str(classes), *map(str, sources)], check=True)
if args.test_profile:
    tests = root / 'build/test-classes'
    tests.mkdir(parents=True, exist_ok=True)
    test_cp = str(classes) + os.pathsep + classpath
    subprocess.run([str(javac), '--release', '21', '-encoding', 'UTF-8', '-classpath', test_cp,
                    '-d', str(tests), *map(str, (root / 'test/java').rglob('*.java'))], check=True)
    subprocess.run([str(java), '-ea', '-classpath', str(tests) + os.pathsep + test_cp,
                    'local.tscosts.ProfileDataTest', str(args.test_profile.resolve())], check=True)
jar = root / 'build/ts-costs.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.write(root / 'LICENSE', 'META-INF/LICENSE')
    for directory in [classes, root / 'src/main/resources']:
        for file in sorted(directory.rglob('*')):
            if file.is_file():
                archive.write(file, file.relative_to(directory).as_posix())
    for file in sorted((root / 'bridge').iterdir()):
        if file.is_file():
            archive.write(file, 'bridge/' + file.name)
dist = root / 'dist'
dist.mkdir(exist_ok=True)
with zipfile.ZipFile(dist / f'ts-costs-{version}.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.write(jar, 'ts-costs/lib/ts-costs.jar')
with zipfile.ZipFile(dist / f'ts-costs-{version}-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in ['src', 'bridge', 'test', 'scripts', '.github']:
        for file in sorted((root / name).rglob('*')):
            if file.is_file() and '__pycache__' not in file.parts:
                archive.write(file, file.relative_to(root).as_posix())
    for name in ['build.py', 'package.json', 'package-lock.json', 'README.md', 'LICENSE', 'CONTRIBUTING.md', 'CHANGELOG.md', '.gitignore']:
        if (root / name).exists():
            archive.write(root / name, name)
print(dist / f'ts-costs-{version}.zip')
