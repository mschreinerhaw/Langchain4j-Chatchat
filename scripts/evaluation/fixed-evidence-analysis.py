"""Offline generation matrix. Evidence is frozen; this never retrieves or grades live reports."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import random
import subprocess
import time


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--guided', type=Path, required=True)
    parser.add_argument('--models', nargs='+', required=True)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--seed', type=int, default=20261012)
    parser.add_argument('generator', nargs=argparse.REMAINDER,
                        help='Command reading one JSON request on stdin and returning JSON answer on stdout')
    args = parser.parse_args()
    command = args.generator[1:] if args.generator[:1] == ['--'] else args.generator
    if not command or args.repeats < 1 or args.workers < 1:
        parser.error('generator, positive repeats and workers are required')
    fixture = json.loads(args.fixture.read_text(encoding='utf-8-sig'))
    evidence = json.dumps(fixture['evidence'], ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    common = fixture.get('system', '分析固定材料，保留来源，不调用工具，不使用其他材料。')
    arms = {'baseline': args.baseline.read_text(encoding='utf-8-sig'),
            'guided': args.guided.read_text(encoding='utf-8-sig')}
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'question': fixture['question'], 'fixtureSha256': digest(args.fixture.read_text(encoding='utf-8-sig')),
                'evidenceSha256': digest(evidence), 'methodSha256': {k: digest(v) for k, v in arms.items()},
                'models': args.models, 'repeats': args.repeats, 'seed': args.seed,
                'scope': 'Fixed projected evidence; direct model analysis, not end-to-end Runtime or retrieval evaluation'}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    jobs = [(m, a, r) for m in args.models for a in arms for r in range(1, args.repeats + 1)]
    random.Random(args.seed).shuffle(jobs)

    def generate(job):
        model, arm, repeat = job
        identity = f'{model}-{arm}-{repeat}'
        request = {'model': model, 'messages': [
            {'role': 'system', 'content': common + '\n\n分析 Skill 方法：\n' + arms[arm]},
            {'role': 'user', 'content': fixture['question'] + '\n\n固定授权证据：\n' + evidence}]}
        # Persist prompts without credentials; no rubric or expected answer reaches generation.
        (args.output / (identity + '.request.json')).write_text(
            json.dumps(request, ensure_ascii=False, indent=2), encoding='utf-8')
        started = time.monotonic()
        result = {'id': identity, 'model': model, 'arm': arm, 'repeat': repeat,
                  'evidenceSha256': manifest['evidenceSha256']}
        try:
            process = subprocess.run(command, input=json.dumps(request, ensure_ascii=False),
                                     encoding='utf-8', capture_output=True, timeout=900, check=True)
            response = json.loads(process.stdout)
            answer = response['answer']
            if not isinstance(answer, str) or not answer.strip():
                raise ValueError('empty model answer')
            result.update(status='SUCCESS', response=response, answerSha256=digest(answer))
            (args.output / (identity + '.md')).write_text(answer, encoding='utf-8')
        except (subprocess.SubprocessError, ValueError, KeyError) as error:
            # Provider errors can contain request headers: do not persist or print stderr.
            result.update(status='ERROR', errorType=type(error).__name__)
        result['latencyMs'] = round((time.monotonic() - started) * 1000)
        (args.output / (identity + '.json')).write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f'{identity}: {result["status"]}, {result["latencyMs"]} ms', flush=True)
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(generate, jobs))
    # Blind paired packets omit model, arm, time and provider identity. Mapping stays separate.
    packets, mapping = [], {}
    rng = random.Random(args.seed + 1)
    for model in args.models:
        for repeat in range(1, args.repeats + 1):
            pair = [x for x in results if x['model'] == model and x['repeat'] == repeat and x['status'] == 'SUCCESS']
            if len(pair) != 2:
                continue
            rng.shuffle(pair)
            blind_id = f'pair-{len(packets) + 1:03d}'
            packets.append({'id': blind_id, 'question': fixture['question'], 'evidence': fixture['evidence'],
                            'candidateA': pair[0]['response']['answer'], 'candidateB': pair[1]['response']['answer']})
            mapping[blind_id] = {'A': pair[0]['id'], 'B': pair[1]['id']}
    for name, value in [('blind-pairs.json', packets), ('private-pair-mapping.json', mapping)]:
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    if any(x['status'] != 'SUCCESS' for x in results):
        raise SystemExit('Some generations failed; inspect status files. Failed generations are not quality scores.')


if __name__ == '__main__':
    main()
