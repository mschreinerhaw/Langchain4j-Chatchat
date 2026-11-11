"""Align isolated official CLI request settings with the Native test profile.

Official `cline auth` must run first. Only the mounted task-local providers.json
is adjusted. No shared model/provider config, official code or input is changed.
The mapping settings.maxTokens -> ProviderConfig.maxOutputTokens is official
v4.1.26 sdk/packages/core/src/services/llms/provider-settings.ts:265. This does
NOT prove the final request contains the limit: the headless CLI's subsequent
handler construction can overwrite it with unset config.maxTokensPerTurn.
Real wire receipts, not this configuration write, decide comparison eligibility.
"""
import json,sys
from pathlib import Path
path=Path(sys.argv[1]);document=json.loads(path.read_text())
record=document['providers']['openai-compatible'];settings=record['settings']
if settings.get('provider')!='openai-compatible':raise RuntimeError('Unexpected official provider profile')
policy=sys.argv[2] if len(sys.argv)>2 else '8192'
if policy=='8192':settings['maxTokens']=8192
elif policy=='provider-default':settings.pop('maxTokens',None)
else:raise ValueError('Unknown frozen output token policy')
settings['reasoning']={'enabled':True,'effort':'low'}
path.write_text(json.dumps(document,indent=2))
print(json.dumps({'scope':'isolated task configuration','outputTokenPolicy':policy,'requestedMaxTokens':settings.get('maxTokens'),'reasoning':settings['reasoning']}))
