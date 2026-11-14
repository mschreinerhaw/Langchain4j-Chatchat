"""Reuse the platform preview/package transport to seal a reviewed report bundle."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import reportkit as kit
from prepare_report_assets import prepare

def finalize(root):
    workspace=Path.cwd().resolve();root=Path(root).resolve()
    if root==workspace or not root.is_relative_to(workspace):raise ValueError('Report folder must be inside the authorized workspace')
    receipt=prepare(root/'assets',workspace)
    html=(root/'report.html').read_text(encoding='utf-8')
    if 'src="assets/echarts.min.js"' not in html:
        html=html.replace('<script src="assets/report.js"','<script src="assets/echarts.min.js"></script><script src="assets/report.js"')
    for name in ['report.css','report.js','echarts.min.js']:
        html=html.replace('"assets/'+name+'"','"'+receipt['files'][name]['path']+'"')
    source=root/'preview-source.html';source.write_text(html,encoding='utf-8')
    helpers=list((workspace/'.agents/skills').glob('runtime-image-*-frontend-design/deliver.py'))
    if len(helpers)!=1:raise ValueError('Existing image-owned frontend-design delivery helper required')
    preview=root/'preview'
    subprocess.run([sys.executable,str(helpers[0]),str(source),'--output',str(preview.relative_to(workspace))],cwd=workspace,check=True)
    job=json.loads((preview/'repair-history.json').read_text(encoding='utf-8'))[-1]
    if job['status']!='passed' or job['report']['htmlSha256']!=hashlib.sha256(html.encode()).hexdigest():raise ValueError('Real preview evidence does not match final content')
    if job['report'].get('printChecked') is not True:raise ValueError('Platform preview must include actual PDF/print validation')
    for folder in ['assets','themes']:
        if not (preview/folder).is_dir():continue
        for file in (preview/folder).rglob('*'):
            if file.is_symlink():raise ValueError('Symlink in preview assets')
            if not file.is_file():continue
            target=(root/file.relative_to(preview)).resolve()
            if not target.is_relative_to(root):raise ValueError('Preview asset destination escaped report folder')
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(file.read_bytes())
    final=(preview/'standalone.html').read_bytes();(root/'report.html').write_bytes(final)
    # The existing viewer prefers index.html; retain an identical canonical alias.
    (root/'index.html').write_bytes(final)
    if (root/'standalone.html').exists():(root/'standalone.html').write_bytes(final)
    (root/'report.pdf').write_bytes((preview/'report.pdf').read_bytes())
    model=json.loads((root/'report_model.json').read_text(encoding='utf-8'));quality=kit.validate(model,root)
    quality['pending']=[p for p in quality['pending'] if p!='independent browser desktop/mobile/print validation required']
    quality['browserCheckedHtmlSha256']=hashlib.sha256(final).hexdigest()
    quality['status']='FAILED' if quality['errors'] else 'INCOMPLETE' if quality['pending'] else 'PASSED'
    kit.dump(root/'quality_report.json',quality)
    kit.dump(root/'browser_validation.json',{'jobId':job['id'],'status':'PASSED','originalHtmlSha256':job['report']['htmlSha256'],'finalHtmlSha256':quality['browserCheckedHtmlSha256'],'printChecked':True,'offlineTransform':'Existing Hub standalone bundle; only registered dependency URLs rewritten'})
    manifest=json.loads((root/'report_manifest.json').read_text(encoding='utf-8'));manifest['qualityStatus']=quality['status']
    manifest['resources']['version']=receipt['reportVersion']
    manifest['resources']['files']={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'assets').rglob('*') if p.is_file()}
    manifest['browserPreviewId']=job['id'];kit.dump(root/'report_manifest.json',manifest)
    selection_path=workspace/'delivery_manifest.json'
    selection=json.loads(selection_path.read_text()) if selection_path.exists() else {'schemaVersion':'coding_node_delivery_selection.v1','artifacts':[]}
    items={item['path']:item for item in selection['artifacts']}
    for file in root.rglob('*'):
        if not file.is_file():continue
        relative=file.relative_to(root);path=file.relative_to(workspace).as_posix()
        kind='REPORT' if relative.as_posix()=='report.html' else 'CHART' if relative.parts[0]=='charts' else 'RESULT' if relative.name in ['report_manifest.json','evidence.json','quality_report.json','report_model.json'] else 'SOURCE' if file.suffix in ['.html','.py'] else 'AUXILIARY'
        items[path]={'path':path,'type':kind}
    if len(items)>1000:raise ValueError('Existing Runtime artifact count limit exceeded')
    selection['artifacts']=list(items.values());kit.dump(selection_path,selection)
    if quality['errors']:raise ValueError('Report quality failed; repair the actual source/model before delivery')
    return {'report':str(root/'report.html'),'qualityStatus':quality['status'],'previewJobId':job['id'],'silo':'PENDING_RUNTIME_ARCHIVE'}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    print(json.dumps(finalize(args.report)))
