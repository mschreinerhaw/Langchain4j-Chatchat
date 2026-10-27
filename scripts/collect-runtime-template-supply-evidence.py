"""Read an existing server task and export control evidence without business rows or secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def records(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from records(child)
    elif isinstance(value, list):
        for child in value:
            yield from records(child)
    elif isinstance(value, str) and value.lstrip().startswith(('{', '[')):
        try:
            yield from records(json.loads(value))
        except (ValueError, RecursionError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--task-id', required=True)
    parser.add_argument('--database', default='runtime_os_acceptance_api')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9-]{1,128}', args.task_id)
    os.umask(0o077)

    def sql(query):
        return subprocess.check_output(['docker', 'exec', '-u', 'postgres', 'postgres', 'psql',
            '-At', '-v', 'ON_ERROR_STOP=1', '-d', args.database, '-c', query], text=True).strip()

    task = json.loads(sql("select jsonb_build_object('taskId',task_id,'state',canonical_state,"
        "'runId',execution_attempt_id,'parentAttemptId',parent_attempt_id,'rootExecutionId',root_execution_id,"
        "'createdAt',create_time,'updatedAt',update_time) from agent_task_latest where task_id='"
        + args.task_id + "'"))
    run_id = task['runId']
    assert re.fullmatch(r'[A-Za-z0-9-]{1,128}', run_id)
    run = json.loads(sql("select run_json from agent_runtime_run where run_id='" + run_id + "'"))
    result = run.get('result') or {}
    answer = result.get('answer') or ''
    keys = set('deterministicBoundTemplatePlan runtimeTemplateCandidateCount runtimeTemplateSelectedCount '
        'runtimeSelectedTemplateIds runtimeTemplateSelectionApplied parameterBindingMode parameterBindingCount '
        'runtimeModelParameterProtocolApplied analysisExecutionMode datasetAnalysisMode '
        'currentBusinessDataAcquired javaEvidenceExplorationSkipped javaEvidenceAugmentationSkipped '
        'javaDataExplorationSkipped javaAnalysisRefinementSkipped javaPostCodingExplorationSkipped '
        'primaryCodingDispatchCount primaryCodingDurationMs codingOwnedAnalysisActive '
        'recordAnalysisReturnedRecordCount recordAnalysisProcessedRecordCount '
        'recordAnalysisDatasetCount analysisResultDatasetReference mandatoryWorkflowCompleted '
        'missingMandatoryTools analysisSemanticReviewPolicy finalReportSemanticReviewPolicy '
        'javaAnalysisModelCalls candidateDecisionOwner candidateSelectionStatus candidateSelectionErrorCode '
        'mcpDiscoverySucceeded toolResultReviewModelCalls businessDataAvailable recordType selectionStatus '
        'parameterBindingMode modelSelectedTemplateIds'.split())
    facts = {}
    protocols = []
    candidates = set()
    for record in records(run):
        for key in keys & record.keys():
            encoded = json.dumps(record[key], sort_keys=True, ensure_ascii=False)
            facts.setdefault(key, {})[encoded] = record[key]
        if isinstance(record.get('templates'), list):
            for candidate in record['templates']:
                if isinstance(candidate, dict) and isinstance(candidate.get('templateId'), str):
                    candidates.add(candidate['templateId'])
        if record.get('template_id') and isinstance(record.get('arguments'), dict):
            projection = {'templateId': record['template_id'],
                'argumentNames': sorted(record['arguments']),
                'generationMode': record.get('generation_mode'),
                'unresolvedParameterNames': record.get('unresolved_parameters', [])}
            if projection not in protocols:
                protocols.append(projection)
    traces = [{key: trace.get(key) for key in ['toolName', 'success', 'durationMs']}
              for trace in result.get('toolTraces', [])]
    observations = []
    for observation in run.get('observations', []):
        metadata = observation.get('metadata') or {}
        if metadata.get('interpretationPlanActionType') == 'mcp_tool':
            projection = {key: metadata.get(key) for key in ['interpretationPlanStepId', 'toolName', 'success', 'durationMs']}
            if projection not in observations:
                observations.append(projection)
    receipt = (result.get('metadata') or {}).get('codingReceipt') or (result.get('metadata') or {}).get('primaryCodingReceipt') or {}
    report = {**task, 'modelName': (run.get('request') or {}).get('modelName'),
        'runStatus': run.get('status'), 'startedAt': run.get('startedAt'), 'finishedAt': run.get('finishedAt'),
        'errorMessage': run.get('errorMessage'), 'toolTraces': traces, 'toolObservations': observations,
        'returnedTemplateIds': sorted(candidates), 'parameterProtocols': protocols,
        'facts': {key: list(values.values()) for key, values in sorted(facts.items())},
        'codingReceipt': {key: receipt.get(key) for key in ['taskId', 'status', 'evidenceRefs'] if key in receipt},
        'answerCharacters': len(answer), 'answerSha256': hashlib.sha256(answer.encode()).hexdigest(),
        'businessRowsExported': False, 'credentialsExported': False}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({'taskId': args.task_id, 'state': task['state'], 'runId': run_id,
        'answerCharacters': len(answer), 'output': str(args.output)}))


if __name__ == '__main__':
    main()
