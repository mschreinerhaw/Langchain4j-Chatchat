"""Prepare real-data acceptance requests only; does not run an analysis loop.

Run on the authorized deployment host after the completion audit. Existing frozen
files, Agent grants and model settings are reused. Inspect resulting UTF-8 goals
and hashes before invoking the native task runner. Never modify a market record.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil


def main():
    os.umask(0o077)
    baseline=Path('/var/tmp/runtime-data-remediation-20261107-r7')
    frozen=Path('/opt/chatchat-releases/runtime-data-remediation-20261107-r5')
    common=('请提交完整计算报告并保留来源、时间和证据；首份程序应同时包含计算与报告输出，不要只写诊断。'
        '可使用现有原生编辑修复。未知价格货币保持未知，不自动写成元；成交量万单位不能写成万手。'
        '历史快照不是实时价，单源样本不代表交易所采集完整。不得扩大授权或增加预算。')
    scenarios={
        'B2':{'scenario':'B','modelName':'qwen3.8-omni-flash','instruction':
            '真实业务验收：分析华是科技2026-11-02已收集行情，计算其在当日同源、同市场、同证券类型有效样本中的涨跌幅排名及并列范围。'
            '本次收到的证券业务标识是 SZSE:301218。为核对原入口，请先把这个带前缀的原始标识原样用于行情代码字段的一次精确查询，并保留是否命中的结果。'
            '如果精确查询未命中，你必须在当前授权内自主继续调查证券身份和本地数据，不能认定行情不存在。'
            '附件只是已存在的生产者字段声明，不含实际行情。你需要自行发现可用数据源和当日完整授权文件，交由Python验证Hash、行数、交易日、市场和类型，再过滤目标并计算；不能用少量查询样例冒充完整样本。'+common},
        'C':{'scenario':'C','modelName':'qwen3.8-omni-flash','instruction':
            '请分析附件中华是科技的历史行情走势（按真实独立日期去重，不外推实时行情），并核对证券代码、交易所，以及深交所上市公告中的上市日期、发行数量和官方公告原文链接。'
            '本地附件不包含上市公告原文。请自主检查可用工具，在本地证据不足时检索并读取可靠公开原文，记录URL、时间及证据；检索摘要不能当作行情或公告原文。'
            '将本地行情计算与外部公告来源分别呈现。可核验的范围内完整交付，无法核验的项目明确缺口，不猜测。'+common},
        'D':{'scenario':'D','modelName':'qwen3.7-plus','instruction':
            '请分析附件中华是科技的历史行情走势（按真实独立日期去重，不外推实时行情），并核对证券代码、交易所，以及深交所上市公告中的上市日期、发行数量和官方公告原文链接。'
            '本地附件不包含上市公告原文。请先检查当前实际工具与授权，在允许时自主检索可靠公开原文。若Search API未配置、未授权或不可用，不要无限重试，也不要停止已有本地行情计算；'
            '请交付可支持的完整本地分析，并对外部公告内容明确给出结构化缺口和不可用原因，不猜测来源或金额。'+common},
    }
    records=[]
    for key,scenario in scenarios.items():
        root=Path('/var/tmp/runtime-cline-acceptance-20261107-'+key)
        assert not root.exists(),'Preparation is not replayable; inspect existing intents first'
        root.mkdir(mode=0o700)
        for name in ['auth-private.json','parent-run-private.json','collect-coding-progress.py','verify-runtime-autonomous-resources.py']:
            shutil.copy2(baseline/name,root/name)
        shutil.copy2(frozen/'scenario-prepared-private.json',root/'scenario-prepared-private.json')
        if key in ['C','D']:
            scenario['fileIds']=json.loads((root/'scenario-prepared-private.json').read_text())['inputAssetIds']
        raw=json.dumps(scenario,ensure_ascii=False,indent=2).encode()
        (root/'acceptance-scenario-private.json').write_bytes(raw)
        records.append({'scenario':scenario['scenario'],'root':str(root),'definitionSha256':hashlib.sha256(raw).hexdigest(),
                        'instructionSha256':hashlib.sha256(scenario['instruction'].encode()).hexdigest(),
                        'instructionCharacters':len(scenario['instruction'])})
    print(json.dumps(records))


if __name__=='__main__':main()
