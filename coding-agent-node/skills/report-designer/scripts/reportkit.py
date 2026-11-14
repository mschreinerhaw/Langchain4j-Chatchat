"""Reusable offline report components and deterministic checks; no orchestration/network."""
import hashlib
import html
import json
import math
import shutil
from datetime import datetime, timezone
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / 'assets'
VERSION = json.loads((ASSETS/'version.json').read_text(encoding='utf-8'))['version']

def dump(path, obj):
    Path(path).write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')

def equivalent(a, b):
    if isinstance(a,list) or isinstance(b,list):
        return isinstance(a,list) and isinstance(b,list) and len(a)==len(b) and all(equivalent(x,y) for x,y in zip(a,b))
    if a is None or b is None:return a is None and b is None
    if isinstance(a,(int,float)) and isinstance(b,(int,float)):
        return not isinstance(a,bool) and not isinstance(b,bool) and math.isclose(a,b,rel_tol=1e-8,abs_tol=1e-10)
    return a==b

def calculate(op, inputs):
    if not inputs or any(x is None or isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) for x in inputs):
        raise ValueError('Missing/nonfinite calculation input')
    if op == 'sum': return sum(inputs)
    if op == 'ratio': return inputs[0] / inputs[1]
    if op == 'return':
        if len(inputs)<2 or any(x<=0 for x in inputs): raise ValueError('Price return requires at least two positive prices')
        return inputs[-1] / inputs[0] - 1
    if op == 'rank':
        if any(x <= 0 for x in inputs): raise ValueError('PE ranks require positive comparable values')
        return sorted(range(len(inputs)), key=lambda i: inputs[i])
    raise ValueError('Unsupported check: ' + op)

def validate(model, root):
    root = Path(root)
    errors, pending = [], []
    sources = {s['id']: s for s in model.get('sources', [])}
    if len(sources) != len(model.get('sources', [])): errors.append('duplicate source id')
    def refs(item, label):
        ids = item.get('sourceIds', [])
        if not ids or any(i not in sources for i in ids): errors.append(label + ': missing source coverage')
    for s in sources.values():
        if any(not s.get(k) for k in ['file', 'dataset', 'fields', 'period', 'basis', 'sha256']):
            errors.append(s['id'] + ': incomplete provenance')
            continue
        path = (root / s['file']).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file(): pending.append(s['id'] + ': source snapshot unavailable')
        elif hashlib.sha256(path.read_bytes()).hexdigest() != s['sha256']: errors.append(s['id'] + ': source hash mismatch')
        if s.get('kind') == 'opinion' and (not s.get('url') or not s.get('publishedAt')): pending.append(s['id'] + ': publication metadata unknown')
    metrics = {m['id']: m for m in model.get('metrics', [])}
    for m in metrics.values():
        refs(m, m['id'])
        if not m.get('unit'): errors.append(m['id'] + ': unit missing')
        if m.get('value') is None and not m.get('limitation'): errors.append(m['id'] + ': missing value needs limitation')
        if m.get('check'):
            try:
                bindings = m['check'].get('sourceInputs', [])
                if not bindings: pending.append(m['id'] + ': calculation inputs not bound to source cells')
                else:
                    actual_inputs = []
                    for binding in bindings:
                        source = sources[binding['sourceId']]
                        file = (root/source['file']).resolve()
                        if not file.is_relative_to(root.resolve()): raise ValueError('source escapes bundle')
                        value = json.loads(file.read_text(encoding='utf-8'))
                        for key in binding['path']: value = value[key]
                        actual_inputs.append(value)
                    if actual_inputs != m['check']['inputs']: errors.append(m['id'] + ': calculation source cell mismatch')
                expected = calculate(m['check']['op'], m['check']['inputs'])
                actual = m.get('value')
                valid = actual == expected if isinstance(expected, list) else isinstance(actual, (int, float)) and math.isclose(actual, expected, rel_tol=1e-8, abs_tol=1e-10)
                if not valid: errors.append(m['id'] + ': calculation/ranking mismatch')
            except (ValueError, ZeroDivisionError, IndexError, TypeError, KeyError, OSError): errors.append(m['id'] + ': invalid calculation')
        elif m.get('formula') and m.get('value') is not None: pending.append(m['id'] + ': calculation not independently recomputed')
    for claim in model.get('claims', []):
        refs(claim, claim['id'])
        if claim.get('kind') not in ['fact', 'calculation', 'opinion', 'inference']: errors.append(claim['id'] + ': claim kind missing')
        if claim.get('metricId') and claim['metricId'] not in metrics: errors.append(claim['id'] + ': metric missing')
        if claim.get('horizon') == 'long-term' and claim.get('observations', 0) < 252: errors.append(claim['id'] + ': insufficient long-term evidence')
    chart_ids = set()
    for c in model.get('charts', []):
        refs(c, c['id'])
        if c['id'] in chart_ids: errors.append('duplicate chart id')
        chart_ids.add(c['id'])
        if any(not c.get(k) for k in ['purpose', 'period', 'unit', 'basis', 'series']): errors.append(c['id'] + ': chart metadata missing')
        if c.get('type') not in ['line', 'bar', 'combo', 'scatter', 'heatmap']: errors.append(c['id'] + ': chart type unsupported')
        for s in c.get('series', []):
            values = s.get('values', [])
            if not s.get('sourceInputs'): pending.append(c['id'] + ': chart values not bound to source cells')
            else:
                try:
                    expected = []
                    for binding in s['sourceInputs']:
                        if binding is None: expected.append(None);continue
                        file = (root/sources[binding['sourceId']]['file']).resolve()
                        if not file.is_relative_to(root.resolve()): raise ValueError('unsafe chart source')
                        value = json.loads(file.read_text(encoding='utf-8'))
                        for key in binding['path']: value = value[key]
                        expected.append(value)
                    if s.get('transform')=='rebased-100':
                        base = expected[0]
                        if base is None or base<=0: raise ValueError('invalid chart base')
                        expected = [None if v is None else v/base*100 for v in expected]
                    if not equivalent(expected,values): errors.append(c['id'] + ': chart differs from source cells')
                except (OSError,KeyError,ValueError,TypeError,IndexError): errors.append(c['id'] + ': invalid chart source bindings')
            if c.get('type') in ['line', 'bar', 'combo'] and len(values) != len(c.get('labels', [])): errors.append(c['id'] + ': chart data length mismatch')
            if c.get('type') in ['scatter', 'heatmap'] and any(not isinstance(v, list) or len(v) != (2 if c['type']=='scatter' else 3) for v in values): errors.append(c['id'] + ': coordinate shape mismatch')
    if model.get('charts') and not (root/'assets/echarts.min.js').is_file(): pending.append('local ECharts resource missing')
    pending.append('independent browser desktop/mobile/print validation required')
    return {'schemaVersion': 'professional-report-quality.v1', 'status': 'FAILED' if errors else 'INCOMPLETE' if pending else 'PASSED', 'errors': errors, 'pending': pending, 'automatedChecks': ['provenance', 'sourceHashes', 'calculations', 'rankings', 'coverage', 'chartShape', 'horizon'], 'semanticReview': 'REQUIRED'}

def build(model, output, echarts=None):
    """Caller chooses authorized output. Sources must already be copied there."""
    root = Path(output)
    (root/'assets').mkdir(parents=True, exist_ok=True)
    (root/'charts').mkdir(exist_ok=True)
    for name in ['report.css', 'report.js']: shutil.copy2(ASSETS/name, root/'assets'/name)
    if echarts and Path(echarts).resolve()!=(root/'assets/echarts.min.js').resolve(): shutil.copy2(echarts, root/'assets/echarts.min.js')
    esc = lambda x: html.escape(str(x), quote=True)
    refs = lambda ids: ' '.join('<a href="#source-'+esc(i)+'">['+esc(i)+']</a>' for i in ids)
    parts = ['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+esc(model['title'])+'</title><link rel="stylesheet" href="assets/report.css"><body data-theme="'+esc(model.get('theme','corporate-blue'))+'"><main><header><small>RUNTIME OS · 专业分析</small><h1>'+esc(model['title'])+'</h1><p class="metadata">'+esc(model['period'])+'</p></header>', '<section class="summary"><h2>执行摘要</h2><p>'+esc(model['summary'])+'</p></section><div class="kpis">']
    for m in model.get('metrics', []):
        value = m.get('value')
        display = '暂无数据' if value is None else f'{value:+.2%}' if m['unit']=='ratio' and isinstance(value,(int,float)) else str(value)
        parts.append('<div class="kpi">'+esc(m.get('label',m['id']))+'<strong>'+esc(display)+'</strong>'+esc('' if m['unit']=='ratio' else m['unit'])+' '+refs(m.get('sourceIds',[]))+'<p>'+esc(m.get('limitation',''))+'</p></div>')
    parts.append('</div>')
    for section in model.get('sections', []): parts.append('<section><h2>'+esc(section['title'])+'</h2><p>'+esc(section['text'])+'</p></section>')
    for i,c in enumerate(model.get('charts', [])):
        sid = 'chart-spec-'+str(i)
        parts.append('<section><h2>'+esc(c['purpose'])+'</h2><p class="metadata">'+esc(c['period']+' | '+c['unit']+' | '+c['basis'])+' '+refs(c.get('sourceIds',[]))+'</p><div class="chart" data-chart="'+sid+'"></div><img class="print-chart" alt="'+esc(c['purpose'])+'"><a class="download" download="chart.json" data-download="'+sid+'">下载图表数据</a><div class="table-wrap"><table><caption>图表数据</caption>')
        if c['type'] in ['line','bar','combo']:
            parts.append('<tr><th>日期 / 分类</th>'+''.join('<th>'+esc(s['name'])+'</th>' for s in c['series'])+'</tr>')
            for j,label in enumerate(c.get('labels',[])):
                cells=[]
                for s in c['series']:
                    value=s['values'][j] if j<len(s['values']) else None
                    cells.append('<td>'+esc('缺失' if value is None else f'{value:,.2f}' if isinstance(value,(int,float)) else value)+'</td>')
                parts.append('<tr><td>'+esc(label)+'</td>'+''.join(cells)+'</tr>')
        else:
            parts.append('<tr><th>系列</th><th>坐标 / 数值</th></tr>')
            for s in c['series']:
                for value in s['values']:parts.append('<tr><td>'+esc(s['name'])+'</td><td>'+esc(str(value))+'</td></tr>')
        parts.append('</table></div><script type="application/json" id="'+sid+'">'+json.dumps(c,ensure_ascii=False,allow_nan=False).replace('<','\\u003c')+'</script></section>')
        dump(root/'charts'/('chart-'+str(i)+'.json'),c)
    parts.append('<section><h2>分析结论与证据</h2>')
    for c in model.get('claims',[]): parts.append('<p><span class="tag">'+esc(c['kind'])+'</span> '+esc(c['text'])+' '+refs(c.get('sourceIds',[]))+'</p>')
    parts.append('</section><section><h2>数据来源及附录</h2>')
    for s in model.get('sources',[]):
        parts.append('<p id="source-'+esc(s['id'])+'"><strong>['+esc(s['id'])+'] '+esc(s['dataset'])+'</strong><br>'+esc(s['period'])+' · '+esc(s['basis'])+'<br><a href="'+esc(s['file'])+'">查看归档数据</a> · 字段：'+esc(', '.join(s['fields']))+'</p>')
        for url in s.get('urls',[]):
            if url.startswith(('https://','http://')):parts.append('<p><a href="'+esc(url)+'">'+esc(url)+'</a></p>')
        parts.append('<details><summary>数据完整性校验</summary><p>SHA-256: '+esc(s['sha256'])+'</p></details>')
    parts.append('</section><footer>缺失数据不补零。质量状态见 quality_report.json；计算证据见 evidence.json。</footer></main>')
    if (root/'assets/echarts.min.js').is_file(): parts.append('<script src="assets/echarts.min.js"></script>')
    parts.append('<script src="assets/report.js"></script></body></html>')
    (root/'report.html').write_text(''.join(parts),encoding='utf-8')
    dump(root/'evidence.json', {k:model.get(k,[]) for k in ['sources','metrics','claims']})
    dump(root/'report_model.json', model)
    quality = validate(model, root)
    dump(root/'quality_report.json', quality)
    dump(root/'report_manifest.json', {'schemaVersion':'professional-report.v1','reportType':model.get('reportType'),'dataRange':model['period'],'generatedAt':datetime.now(timezone.utc).isoformat(),'resources':{'version':VERSION,'files':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'assets').iterdir()}},'qualityStatus':quality['status']})
    return quality
