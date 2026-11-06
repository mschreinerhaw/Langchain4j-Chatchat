"""Read-only proof of the native schema binding actually mounted for each trial.

This verifies delivery, not whether generated code used the correct mapping.
"""
from pathlib import Path
import hashlib
import json
import re
import sys


def main():
    root=Path(sys.argv[1]).resolve()
    cases=json.loads((root/'real-scenario-results.json').read_text())['cases']
    shared=Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').resolve()
    def content(file):
        assert re.fullmatch('[a-f0-9]{64}',file['fileId'])
        assert '/' not in file['fileName'] and '\\' not in file['fileName']
        matches=list(shared.rglob(file['fileId']+'/'+file['fileName']))
        assert len(matches)==1 and not matches[0].is_symlink() and matches[0].resolve().is_relative_to(shared)
        body=matches[0].read_bytes();assert hashlib.sha256(body).hexdigest()==file['sha256']
        return body
    proof=[]
    for case in cases:
        journal=root/(case['case']+'-journal-private.json')
        if not journal.exists():
            assert '--partial' in sys.argv,'Every trial must have actual admission evidence'
            continue
        task=json.loads(journal.read_text())
        snapshots=next(v for k,v in task['privateState'].items() if k.startswith('inputs:'))
        files={f['fileId']:f for s in snapshots for f in [s]+s.get('chunks',[])}
        schemas=[f for f in files.values() if f['fileName']=='schema.json'];assert len(schemas)==1
        schema=json.loads(content(schemas[0]));assert schema['columnMappingScope']=='MANIFEST_PARTS_ONLY'
        native=next(n for n in schema['nativeFileSchemas'] if n.get('format')=='PARQUET')
        assert native['columnMappingScope']=='NATIVE_FILE'
        binding=native['sourceManifest'];assert binding['status']=='BOUND' and binding['targetFileId']==native['fileId']
        part=files[native['fileId']];content(part)
        assert binding['partSha256']==part['sha256'] and native['rowCount']==binding['rowCount']==2943
        original=files[binding['fileId']];assert original['sha256']==binding['sha256']
        manifest=json.loads(content(original))
        mapping={c['storageName']:c['name'] for c in binding['columns']}
        assert mapping=={c['storageName']:c['name'] for c in manifest['columns']}
        assert any(p['sha256']==part['sha256'] and p['rowCount']==2943 for p in manifest['parts'])
        proof.append({'case':case['case'],'taskId':task['contract']['taskId'],'schemaSha256':schemas[0]['sha256'],
            'nativeFileId':native['fileId'],'partSha256':part['sha256'],'manifestSha256':original['sha256'],
            'bindingStatus':'BOUND','rowCount':2943,'columnCount':len(mapping),
            'keyColumns':{k:v for k,v in mapping.items() if v in ['quote_code','quote_name','trade_date','change_pct']},
            'allReferencedFilesHashVerified':True})
    result={'readOnly':True,'caseCount':len(proof),'interpretation':'Correct mapping delivery does not certify generated parser or business conclusions','cases':proof}
    (root/'native-schema-delivery-proof.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({'caseCount':len(proof),'nativeSchemaBindings':'VERIFIED'}))


if __name__=='__main__':main()
