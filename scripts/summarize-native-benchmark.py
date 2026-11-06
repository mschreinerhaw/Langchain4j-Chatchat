"""Describe paired synthetic measurements without converting missing usage to zero."""
import argparse
import json
import math
from pathlib import Path


def percentile(values, probability):
    values = sorted(values)
    if not values:
        return None
    offset = (len(values) - 1) * probability
    low, high = math.floor(offset), math.ceil(offset)
    return values[low] + (values[high] - values[low]) * (offset - low)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--metrics', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    rows = json.loads(a.metrics.read_text())['rows']
    clock_file = a.metrics.with_name('executor-clocks-public.json')
    if clock_file.exists():
        clocks = {(r['variant'],r['case']):r for r in json.loads(clock_file.read_text())['rows']}
        rows = [{**r,'firstExecutorStartedMs':clocks.get((r['variant'],r['case']),{}).get('firstExecutorStartedMs')} for r in rows]
    keys = ['firstProposalMs','firstCodeMs','firstNewCodeMs','firstPythonMs','firstResultMs','httpDurationMs',
        'firstExecutorStartedMs',
        'modelCalls','inputTokensKnown','outputTokensKnown','knownTokens','pythonExecutions',
        'preCodeModelCalls','preCodeInputTokensKnown','preCodeMaxRequestBytes','maxSkillContextBytes']
    variants = {}
    for variant in ['baseline','candidate']:
        selected = [r for r in rows if r['variant'] == variant]
        metrics = {}
        for key in keys:
            values = [r[key] for r in selected if isinstance(r.get(key),(int,float))]
            metrics[key] = {'observed':len(values),'missing':len(selected)-len(values),
                'p50':percentile(values,.5),'p90':percentile(values,.9),'sum':sum(values) if values else None}
        variants[variant] = {'tasks':len(selected),'technicalSuccesses':sum(bool(r.get('successfulExecution')) for r in selected),
            'knownUsageCalls':sum(r.get('knownUsageCalls',0) for r in selected),
            'unknownUsageCalls':sum(r.get('modelCalls',0)-r.get('knownUsageCalls',0) for r in selected),
            'modelErrors':sum(len(r.get('modelErrors',[])) for r in selected),
            'pythonStopUnconfirmed':sum(r.get('pythonStopUnconfirmed',0) for r in selected),'metrics':metrics}
        by_class = {}
        for name in sorted({r['case'].rsplit('-',1)[0] for r in selected}):
            group = [r for r in selected if r['case'].rsplit('-',1)[0] == name]
            measured = {}
            for key in keys:
                values = [r[key] for r in group if isinstance(r.get(key),(int,float))]
                measured[key] = {'observed':len(values),'missing':len(group)-len(values),
                    'p50':percentile(values,.5),'p90':percentile(values,.9)}
            by_class[name] = {'tasks':len(group),'metrics':measured}
        variants[variant]['byClass'] = by_class
    pairs = []
    for case in sorted({r['case'] for r in rows}):
        pair = {r['variant']:r for r in rows if r['case']==case}
        entry = {'case':case,'baselineStatus':pair.get('baseline',{}).get('status','NOT_TESTED'),
            'candidateStatus':pair.get('candidate',{}).get('status','NOT_TESTED'),'deltaCandidateMinusBaseline':{}}
        for key in keys:
            old,new = pair.get('baseline',{}).get(key),pair.get('candidate',{}).get(key)
            entry['deltaCandidateMinusBaseline'][key] = new-old if isinstance(old,(int,float)) and isinstance(new,(int,float)) else None
        pairs.append(entry)
    result = {'method':'Six synthetic classes, two repetitions each, concurrency 2, sequential variants. P50/P90 use linear interpolation (type 7), n=12 per variant and n=2 per class. Descriptive only; missing and failed samples retained.',
        'model':'qwen3.7-plus','providerModelVersion':'UNKNOWN','providerBilledCost':'UNKNOWN','reasoningTokens':'UNKNOWN',
        'limitations':['Configured model name does not pin Provider weights or routing.','Sequential order and Provider latency confound causality.',
            'firstPythonMs is Node tool dispatch; firstExecutorStartedMs is the owner-verified MCP stopped-execution receipt clock, not an OS process-spawn probe.',
            'Token sums cover reported usage only; they do not certify billed cost.','Technical completion is separate from numerical, narrative and chart delivery quality.'],
        'variants':variants,'pairs':pairs}
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'variants':{v:{k:x[k] for k in ['tasks','technicalSuccesses','knownUsageCalls','unknownUsageCalls']} for v,x in variants.items()}}))


if __name__ == '__main__':
    main()
