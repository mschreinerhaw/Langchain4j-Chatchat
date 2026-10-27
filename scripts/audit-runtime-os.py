"""Inventory existing Runtime components and summarize exported control telemetry; no business data output."""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import re
import statistics
import subprocess


RECOMMENDATIONS = {
    'AnalysisCoverageCoordinator': ('MERGE_RESPONSIBILITIES', 'Keep Coding dispatch and result handling; evidence projection and technical receipts belong to AnalysisEvidenceCoordinator.'),
    'AnalysisEvidenceCoordinator': ('KEEP', 'Authorized datasets, provenance and immutable handles; no business relevance decisions.'),
    'MandatoryWorkflowResultReviewer': ('KEEP', 'Deterministic workflow boundary checks; contains no model call.'),
    'LocalToolResultReviewer': ('KEEP', 'Deterministic protocol facts; candidate cardinality never authorizes selection.'),
    'SemanticCandidateAdmissionPolicy': ('KEEP', 'Candidate membership validation only; no Top1/survivor inference.'),
    'TemplateWorkflowPluginRegistry': ('KEEP', 'Deterministic protocol adapters, including ambiguity rejection and SPI compatibility.'),
    'AnswerCriticRepairer': ('QA_ONLY', 'Keep QA repair; Coding-authored output bypasses independent business review.'),
    'AgentPlanner': ('KEEP_FALLBACK', 'Fixed publication uses compiled workflow; unbound/complex requests retain model planning.'),
    'PublishedDataAcquisitionPlanner': ('KEEP', 'Compiles publisher-owned bindings; does not choose business templates.'),
    'SchemaDrivenTemplateParameterBinder': ('KEEP', 'One model-owned selection/binding stage with one bounded protocol correction; no cross-template parameter inference.'),
    'CodingAnalysisService': ('KEEP', 'Existing task contract, leased dispatch, immutable input/output and bounded cancellation; not a replacement Coding loop.'),
    'CodingInputSnapshotService': ('KEEP', 'Existing manifest/data snapshot and authorization boundary; reuse frozen versions on resume.'),
    'CodingInputFilePublisher': ('KEEP', 'Existing Parquet/CSV/schema publication and hash-bound Silo/shared-directory delivery.'),
    'DurableCodingTaskService': ('KEEP', 'CAS, leases, tenant isolation, idempotency and unknown-operation reconciliation.'),
    'ClineRuntimeModelBridge': ('KEEP', 'Authorized model transport and provider receipts; no business template selection.'),
    'CodingSkillReader': ('KEEP', 'Task-scoped on-demand Skill resource reading with version/revocation checks.'),
    'DataSupplyStatus': ('KEEP', 'Five delivery outcomes plus NOT_REQUESTED; does not authorize execution or retries.'),
}


def inventory(root):
    files = sorted(p for p in root.glob('chatchat-*/src/main/java/**/*.java') if p.is_file())
    components, calls = [], []
    sources = {p: p.read_text(encoding='utf-8') for p in files}
    for path in files:
        source = sources[path]
        relative = path.relative_to(root).as_posix()
        sites = [i for i, line in enumerate(source.splitlines(), 1) if re.search(r'\.chat\s*\(', line)]
        calls.extend({'path': relative, 'line': line} for line in sites)
        if path.stem not in RECOMMENDATIONS and not re.search(r'(Coordinator|Review|Planner|WorkflowPlugin|AdmissionPolicy|Adapter|Gateway|StateMachine|TaskOutcome|ExecutionService)', path.stem):
            continue
        decision, rationale = RECOMMENDATIONS.get(path.stem, (
            'REVIEW_REQUIRED', 'Lexical inventory only. Confirm Runtime scope, actual callers, Spring/SPI/configuration and recovery consumers before retain/merge/delete.'))
        references = [p.relative_to(root).as_posix() for p, text in sources.items() if p != path and re.search(r'\b' + re.escape(path.stem) + r'\b', text)]
        call_evidence = [{'path': p.relative_to(root).as_posix(), 'line': number,
                          'code': line.strip()[:300]} for p, text in sources.items()
                         if p != path and p.relative_to(root).as_posix() in references
                         for number, line in enumerate(text.splitlines(), 1)
                         if re.search(r'\b' + re.escape(path.stem) + r'\b', line)]
        components.append({'productionReferenceFiles': references, 'referenceEvidence': call_evidence,
                           'stateDeclarations': [{'line': i, 'code': line.strip()} for i, line in enumerate(source.splitlines(), 1)
                                                 if re.search(r'\benum\b|\btransition\w*\s*\(', line)],
                           'component': path.stem, 'path': relative, 'lines': len(source.splitlines()),
                           'directChatCallSites': sites, 'recommendation': decision, 'rationale': rationale})
    deleted = subprocess.run(['git', 'diff', '--name-only', '--diff-filter=D', 'HEAD'],
                             cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()
    return {'components': components, 'javaChatCallSites': calls, 'deletedFiles': deleted,
            'measurementBoundary': 'Static call sites are potential execution sites, never runtime invocation counts.'}


def summarize(runs):
    """Input is sanitized run/phase telemetry, not prompts, responses, datasets or estimates."""
    phases = defaultdict(list)
    duplicates = []
    identities = set()
    for run in runs:
        run_id = run['runId']
        for call in run.get('modelCalls', []):
            identity = (run_id, call['phase'], call['operationId'])
            if identity in identities:
                duplicates.append({'runId': run_id, 'phase': call['phase'], 'operationId': call['operationId']})
                continue
            identities.add(identity)
            phases[call['phase']].append(call)
    result = {}
    for phase, calls in sorted(phases.items()):
        durations = [c['durationMs'] for c in calls if isinstance(c.get('durationMs'), (int, float))
                     and c['durationMs'] >= 0]
        tokens = {}
        for field in ('inputTokens', 'outputTokens'):
            known = [c[field] for c in calls if isinstance(c.get(field), int) and c[field] >= 0]
            tokens[field] = {'observedTotal': sum(known) if known else None,
                             'knownCalls': len(known), 'unknownCalls': len(calls) - len(known)}
        result[phase] = {'observedCalls': len(calls), 'timedCalls': len(durations),
                         'failedCalls': sum(c.get('status') == 'FAILED' for c in calls),
                         'p50Ms': statistics.median(durations) if durations else None,
                         'p95Ms': sorted(durations)[math.ceil(.95 * len(durations)) - 1]
                         if len(durations) >= 20 else None,
                         'p95MinimumSamples': 20, 'usage': tokens}
    successful = [r for r in runs if r.get('status') == 'SUCCEEDED']
    timings = [r['durationMs'] for r in successful if isinstance(r.get('durationMs'), (int, float))]
    comparable = defaultdict(list)
    for run in runs:
        if run.get('caseId') and run.get('snapshotSha256'):
            comparable[(run['caseId'], run['snapshotSha256'])].append(run)
    comparisons = []
    for (case_id, snapshot), pair in comparable.items():
        before = [r for r in pair if r.get('variant') == 'before' and r.get('status') == 'SUCCEEDED']
        after = [r for r in pair if r.get('variant') == 'after' and r.get('status') == 'SUCCEEDED']
        if before and after:
            comparisons.append({'caseId': case_id, 'snapshotSha256': snapshot,
                                'beforeSamples': len(before), 'afterSamples': len(after)})
    return {'phases': result, 'duplicateObservations': duplicates,
            'runs': len(runs), 'successfulRuns': len(successful),
            'successRate': len(successful) / len(runs) if runs else None,
            'successfulRunP50Ms': statistics.median(timings) if timings else None,
            'comparableFixedSnapshots': comparisons,
            'measurementBoundary': 'Unknown provider usage stays unknown. Cancelled/failed runs never count as successful speedups. '
                                   'Only the same case and immutable snapshot can qualify for before/after comparison.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--telemetry', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    result = inventory(root)
    if args.telemetry:
        payload = json.loads(args.telemetry.read_text(encoding='utf-8'))
        result['runtimeMeasurements'] = summarize(payload['runs'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'components': len(result['components']), 'chatCallSites': len(result['javaChatCallSites']),
                      'output': str(args.output)}))


if __name__ == '__main__':
    main()
