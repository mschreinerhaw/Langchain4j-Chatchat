import json
from pathlib import Path
import subprocess
import sys
for image in json.loads(Path('images.lock.json').read_text()):
    found=subprocess.run(['docker','image','inspect',image['tag']],capture_output=True,text=True)
    if found.returncode and '--before-load' in sys.argv:continue
    if found.returncode:raise SystemExit('Required image missing: '+image['tag'])
    actual=json.loads(found.stdout)[0]['Id']
    if actual!=image['id']:raise SystemExit('Local image identity differs: '+image['tag'])
print('All release image identities verified')
