"""Compare actual Surefire XML with the clean baseline; never turns inherited failures green."""
import argparse
from collections import Counter
import json
import pathlib
import xml.etree.ElementTree as ET
import zipfile


def suite(module,content):
    root=ET.fromstring(content)
    value={'module':module,'suite':root.get('name'),**{k:int(root.get(k,0)) for k in ('tests','failures','errors','skipped')},'failedCases':[]}
    occurrences=Counter()
    for case in root.findall('testcase'):
        for kind in ('failure','error'):
            for failed in case.findall(kind):
                identity=case.get('classname','')+'.'+case.get('name','')
                occurrences[(identity,kind)]+=1
                value['failedCases'].append({'id':identity,'occurrence':occurrences[(identity,kind)],
                    'kind':kind,'failureType':failed.get('type',''),
                    'message':failed.get('message','').splitlines()[0][:240] if failed.get('message') else ''})
    return value


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--baseline',default='target/shared-input/baseline-full-reports.zip')
    p.add_argument('--archive',default='target/shared-input/final-full-reports.zip')
    p.add_argument('--output',default='docs/shared-input-20261019-backend-comparison.json')
    args=p.parse_args()
    with zipfile.ZipFile(args.baseline) as z:
        before=[suite(n.split('/')[0],z.read(n)) for n in z.namelist() if n.endswith('.xml')]
    modules={s['module'] for s in before}
    after=[]
    with zipfile.ZipFile(args.archive,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for module in sorted(modules):
            for path in sorted(pathlib.Path(module).glob('target/surefire-reports/TEST-*.xml')):
                content=path.read_bytes();after.append(suite(module,content));archive.writestr(path.as_posix(),content)
    def totals(values):return {key:sum(s[key] for s in values) for key in ('tests','failures','errors','skipped')}
    def failures(values):return {(s['module'],c['id'],c['kind'],c['occurrence']):c for s in values for c in s['failedCases']}
    old,new=failures(before),failures(after)
    report={'baselineCommit':'1fae5a58','baselineTotals':totals(before),'currentTotals':totals(after),
        'introducedFailures':[{'module':key[0],**new[key]} for key in sorted(new.keys()-old.keys())],
        'resolvedFailures':[{'module':key[0],**old[key]} for key in sorted(old.keys()-new.keys())],
        'historicalFailureTypes':dict(Counter(c['failureType'] for c in old.values())),
        'baselineSuites':before,'currentSuites':after,
        'scope':'Actual full reactor JVM tests; inherited failures retained. Docker gates, real services and model acceptance are separate.',
        'mavenTestFailureIgnoreUsed':True}
    pathlib.Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:report[k] for k in ('baselineTotals','currentTotals','historicalFailureTypes')}))
    print(json.dumps({'introducedFailures':len(report['introducedFailures']),'resolvedFailures':len(report['resolvedFailures'])}))
    if report['introducedFailures']:raise SystemExit(1)


if __name__=='__main__':main()
