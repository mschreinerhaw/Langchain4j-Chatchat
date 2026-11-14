"""Refresh/check the byte-pinned skill resources and Hub mirror from one source."""
import hashlib
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1];skills=ROOT/'coding-agent-node/skills';catalog=skills/'catalog.json'
check='--check' in sys.argv
cards=json.loads(catalog.read_text(encoding='utf-8'))
version=json.loads((skills/'report-designer/assets/version.json').read_text())['version']
for card in cards:
    if not card.get('professionalReport'):continue
    if check and card['version']!=version:raise SystemExit('Stale report skill version')
    card['version']=version
    folder=skills/card['name']
    resources=[dict(path=f.relative_to(folder).as_posix(),sha256=hashlib.sha256(f.read_bytes()).hexdigest(),size=f.stat().st_size) for f in sorted(folder.rglob('*')) if f.is_file() and f.name!='SKILL.md' and '__pycache__' not in str(f)]
    if check and card.get('resources')!=resources:raise SystemExit('Stale report resource manifest: '+card['name'])
    card['resources']=resources
for source in sorted((skills/'report-designer/assets').glob('*')):
    target=ROOT/'frontend-resource-hub/report-assets'/source.name
    if check:
        if not target.is_file() or source.read_bytes()!=target.read_bytes():raise SystemExit('Stale Hub mirror: '+source.name)
    else:target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes())
if not check:catalog.write_text(json.dumps(cards,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Professional report resource integrity OK')
