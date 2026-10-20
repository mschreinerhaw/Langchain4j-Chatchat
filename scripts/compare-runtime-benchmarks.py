"""Compare real same-workload benchmark groups without pooling failed samples."""
import argparse
import json
from pathlib import Path


FIELDS = (
    ('端到端耗时', 'endToEndP50Ms', 'endToEndP95Ms', 'ms'),
    ('首批模板前规划耗时', 'planningBeforeFirstBatchMs', None, 'ms'),
    ('受控模板批处理耗时', 'templateBatchDurationMs', None, 'ms'),
    ('Java 模型调用', 'javaModelCalls', None, '次'),
    ('Java 模型耗时', 'javaModelDurationMs', None, 'ms'),
    ('Coding 模型调用', 'codingModelCalls', None, '次'),
    ('Coding 模型耗时', 'codingModelDurationMs', None, 'ms'),
    ('Python 执行', 'pythonExecutions', None, '次'),
    ('Python 往返耗时', 'pythonRoundtripMs', None, 'ms'),
    ('Python 容器进程耗时', 'pythonProcessMs', None, 'ms'),
    ('Coding 输入 Token', 'inputTokens', None, 'Token'),
    ('Coding 输出 Token', 'outputTokens', None, 'Token'),
)


def fmt(value):
    if value is None: return '未观测'
    if isinstance(value, float) and not value.is_integer(): return f'{value:.1f}'
    return str(round(value))


def metric(group, key, tail):
    if tail: return group.get(key)
    return group.get('successfulSampleP50P95', {}).get(key, {}).get('p50')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    before, after = (json.loads(path.read_text()) for path in (args.before,args.after))
    if not before.get('workloadSha256') or before['workloadSha256'] != after.get('workloadSha256'):
        raise ValueError('Different workload identities cannot be compared as before/after')
    if before['label'] == after['label']:
        raise ValueError('Deployment labels must differ')
    lines = ['# Runtime OS 真实分析性能对比', '',
        f"工作负载 SHA-256：`{before['workloadSha256']}`。原问题、Agent、模型和任务参数保持相同；样本分别由各版本真实部署运行。", '',
        '| 指标 | 改造前 | 改造后 | 单位 |', '|---|---:|---:|---|']
    for title, key, tail, unit in FIELDS:
        a, b = metric(before,key,tail), metric(after,key,tail)
        suffix=' P50/P95' if tail else ' 成功样本 P50'
        if tail:
            a=(fmt(a),fmt(before.get(tail)))
            b=(fmt(b),fmt(after.get(tail)))
            lines.append(f'| {title}{suffix} | {" / ".join(a)} | {" / ".join(b)} | {unit} |')
        else:
            lines.append(f'| {title}{suffix} | {fmt(a)} | {fmt(b)} | {unit} |')
    lines += ['',
        f"样本：改造前 {before['completed']}/{before['planned']}，成功 {before['succeeded']}，部分完成 {before.get('partial',0)}，失败 {before.get('failed',0)}；"
        f"改造后 {after['completed']}/{after['planned']}，成功 {after['succeeded']}，部分完成 {after.get('partial',0)}，失败 {after.get('failed',0)}。", '',
        f"程序首次执行产出结果：改造前 {fmt(before.get('firstExecutionSuccessRate'))}（可判定 {before.get('firstExecutionObserved',0)} 轮），"
        f"改造后 {fmt(after.get('firstExecutionSuccessRate'))}（可判定 {after.get('firstExecutionObserved',0)} 轮）。", '',
        '业务正确率按 Skills、模板声明口径与原始数据离线逐项核验；执行成功和结构校验不代替该结论。',
        'P50/P95 仅对成功样本按线性插值计算。小样本 P95 是观测描述，不是生产尾延迟保证。',
        '模型调用耗时可与模板、Python 阶段交叠，不能将表内各项直接相加。缓存命中仅在提供方回执给出时统计；未记录保持未知。', '']
    args.output.write_text('\n'.join(lines),encoding='utf-8')
    print(args.output)


if __name__ == '__main__':
    main()
