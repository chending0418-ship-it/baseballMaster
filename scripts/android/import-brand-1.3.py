#!/usr/bin/env python3
"""Render the four frozen brand 1.3 product lockups for Android (macOS sips)."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
spec = repo.parents[1] / "brand/specifications/1.3"
manifest = json.loads((spec / "manifest.json").read_text())
dest = repo / "android/app/src/main/res/drawable-nodpi"
with tempfile.TemporaryDirectory() as tmp:
    for variant in ("horizontal", "horizontal-reverse", "compact", "compact-reverse"):
        name = f"scorekeeper-{variant}.svg"
        source = (spec / "assets" / name).read_bytes()
        if hashlib.sha256(source).hexdigest() != manifest["assets"][name]:
            raise ValueError(f"Unexpected SHA-256 for {name}")
        svg = source.decode()
        w, h = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg).groups()
        scaled = Path(tmp) / name
        scaled.write_text(svg.replace("<svg ", f'<svg width="{float(w)*3:g}" height="{float(h)*3:g}" ', 1))
        target = dest / f"product_brand_{variant.replace('-', '_')}.png"
        subprocess.run(["sips", "-s", "format", "png", str(scaled), "--out", str(target)], check=True)
