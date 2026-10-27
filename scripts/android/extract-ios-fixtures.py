#!/usr/bin/env python3
"""Extract synthetic iOS XCTest attachments without silently replacing frozen references."""
from pathlib import Path
import hashlib
import json
import sys
source, destination = map(Path, sys.argv[1:])
destination.mkdir(parents=True, exist_ok=False)
files = []
for attachment in sorted(source.glob('*.json')):
    value = json.loads(attachment.read_text())
    if not isinstance(value, dict) or ('iosInitialPayload' not in value and 'androidPerformance' not in value):
        continue
    target = destination / ('performance.json' if 'androidPerformance' in value else value['scenario'] + '-scoring.json' if 'scenario' in value else value['mode'] + '-pitch-seed.json')
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    files.append({'path': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                  'mode': value['mode'], 'steps': len(value.get('steps',value.get('commands',[])))})
assert files, 'No exported iOS fixtures found'
scoring = all(item['path'].endswith('-scoring.json') for item in files)
history = scoring and all(item['path'].startswith('history-') for item in files)
performance=any(item['path']=='performance.json' for item in files)
catalog=scoring and all(item['path'].startswith('catalog-') for item in files)
remaining=scoring and all(item['path'].startswith('remaining-') for item in files)
if not scoring and not performance:
    assert {item['mode'] for item in files} == {'standard', 'coachPitch', 'slowPitch'}
(destination / 'manifest.json').write_text(json.dumps({'schema': 2 if scoring else 1, 'baseline': '40df3d2',
    'generator': 'BaseballMasterTests/GameStoreTests/' + ('testAndroidCatalogExport' if catalog else 'testAndroidPerformanceExport' if performance else 'testAndroidRemainingRulesExport' if remaining else 'testAndroidHistoryParityExport' if history else 'testAndroidScoringParityExport' if scoring else 'testAndroidParitySeedExport'),
    'reference': 'actual iOS XCTest result, synthetic data only', 'files': files}, ensure_ascii=False, indent=2) + '\n')
