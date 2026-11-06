#!/usr/bin/env python3
"""Verifier can exit zero for descriptor/compatibility failures; inspect the reports too."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
verdicts = list(root.rglob('verification-verdict.txt'))
if not verdicts:
    raise SystemExit('No verifier verdict was produced')
for verdict in verdicts:
    text = verdict.read_text()
    print(text)
    if 'Compatible' not in text or 'Incompatible' in text:
        raise SystemExit('Plugin verification failed')
