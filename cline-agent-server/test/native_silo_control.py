"""Java/Silo control fixture for real Native delivery tests; no Agent decisions.

The registered Java command and credentials are private fixture inputs. Runtime
selects its verifier; this utility only runs the checked Java control component
and the image's byte-transfer utility. Every container is retained for final
ownership-checked cleanup after acceptance, archival and Git push.
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess

class SiloControl:
    def __init__(self,root,fixture,case,image,register):
        self.root,self.fixture,self.case,self.image,self.register=Path(root),Path(fixture),case,image,register
        self.folder=self.root/'runs'/case
        specific=self.root/'private'/(self.fixture.name+'-java-command.json')
        self.base=json.loads((specific if specific.exists() else self.root/'private/java-driver-command.json').read_text())
        self.base[-1]=str(self.root/'private'/(self.fixture.name+'.json'))

    def java(self,action,version):
        command=[part for part in self.base if part!='--rm'];command[1]='create'
        name=f'unified-java-{self.case}-{version}-{action}'
        command[command.index('--name')+1]=name;command[-2]=action
        command[2:2]=['--label','runtime-os.coding.acceptance='+self.case]
        created=subprocess.run(command,text=True,capture_output=True,timeout=60)
        if created.returncode:raise RuntimeError('Java control container create failed; no uncertain operation replay')
        ident=created.stdout.strip();self.register(ident,name,'Java/Silo '+action+' '+version)
        logpath=self.root/'evidence'/(name+'.log')
        with logpath.open('w') as log:
            process=subprocess.run(['docker','start','-a',ident],stdout=log,stderr=subprocess.STDOUT,timeout=180)
        info=json.loads(subprocess.check_output(['docker','inspect',ident],timeout=20))[0]
        if process.returncode or info['State']['ExitCode']:raise RuntimeError('Java '+action+' failed; retained container/log; do not redispatch')
        state=json.loads((self.fixture/'java-state.private.json').read_text())
        snapshots=self.folder/'control-states';snapshots.mkdir(exist_ok=True)
        (snapshots/(version+'-'+action+'.private.json')).write_text(json.dumps(state,ensure_ascii=False,indent=2))
        database=self.fixture/'control-db/workspace.mv.db'
        if database.exists():
            # This fixture's SQL process has exited; preserve the closed file
            # alongside its response so lost durability can be investigated.
            proof={'size':database.stat().st_size,'sha256':hashlib.sha256(database.read_bytes()).hexdigest()}
            if proof['size']<=32*1024*1024:shutil.copyfile(database,snapshots/(version+'-'+action+'.mv.db'))
            else:proof['copyOmitted']='Fixture database exceeds 32 MiB forensic copy bound'
            (snapshots/(version+'-'+action+'-database.json')).write_text(json.dumps(proof))
        return state

    def upload(self,version):
        plan=self.folder/(version+'-upload-plan.private.json')
        plan.write_bytes((self.fixture/'upload-plan.private.json').read_bytes());os.chown(plan,10001,10001);plan.chmod(0o400)
        name=f'coding-node-output-{self.case}-{version}'
        command=['docker','create','--name',name,'--label','runtime-os.coding.acceptance='+self.case,
            '--network','host','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
            '--memory','512m','--pids-limit','64','--tmpfs','/tmp:rw,nosuid,nodev,size=64m',
            '-v',str(self.folder/'workspace')+':/workspace/task:ro','-v',str(plan)+':/run/transfer-plan.json:ro',
            self.image,'node','/poc/service/src/workspace-transfer.mjs','upload','/workspace/task','/run/transfer-plan.json']
        created=subprocess.run(command,text=True,capture_output=True,timeout=60)
        if created.returncode:raise RuntimeError('Silo transfer container create failed')
        ident=created.stdout.strip();self.register(ident,name,'Silo output transfer '+version+'; no Core')
        process=subprocess.run(['docker','start','-a',ident],text=True,capture_output=True,timeout=330)
        (self.folder/(version+'-upload.stdout.json')).write_text(process.stdout)
        (self.folder/(version+'-upload.stderr.log')).write_text(process.stderr)
        info=json.loads(subprocess.check_output(['docker','inspect',ident],timeout=20))[0]
        if process.returncode or info['State']['ExitCode']:raise RuntimeError('Silo output transfer failed; no formal acceptance')
        receipts=json.loads(process.stdout)
        (self.fixture/'upload-receipts.json').write_text(json.dumps(receipts,indent=2))

    def review(self,manifest,events,version):
        evidence=self.folder/'delivery-versions'/version;evidence.mkdir(parents=True,exist_ok=False)
        for target,value in [('delivery_manifest.json',manifest),('native-events.json',events)]:
            body=json.dumps(value,ensure_ascii=False,indent=2)
            (self.fixture/target).write_text(body);(evidence/target).write_text(body)
        self.java('plan',version);self.upload(version);self.java('commit',version)
        self.java('archive-native',version);state=self.java('verify-candidate',version)
        validation=json.loads((self.fixture/'business-validation.private.json').read_text())
        (evidence/'business-validation.private.json').write_text(json.dumps(validation,ensure_ascii=False,indent=2))
        (evidence/'java-state.private.json').write_text(json.dumps(state,ensure_ascii=False,indent=2))
        return state,validation

    def continue_after_receipt(self,version):
        state=self.java('continue-candidate',version)
        # Observe from a NEW JVM before allowing another model turn. A local
        # action's response alone is insufficient evidence of persisted state.
        observed=self.java('inspect',version+'-after-feedback')
        if observed!=state or observed['status']!='RUNNING':
            raise RuntimeError('Java feedback fence was not persisted across control process exit')
        return observed

    def finalize(self,manifest,events,container_observation,version):
        (self.fixture/'delivery_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
        (self.fixture/'native-events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2))
        (self.fixture/'container-final.private.json').write_text(json.dumps(container_observation,indent=2))
        return self.java('finalize-sealed',version)

def feedback_for(validation,version):
    faults=validation.get('facts',{}).get('faults',[])
    # Only genuine failed metric receipts can request further native work. Data,
    # verifier and scope faults are never hidden behind another model dispatch.
    if not faults or not all(str(f).startswith(('METRIC_','OUTPUT_CONTRACT_VIOLATION:','REPORT_SEMANTIC_')) for f in faults):return None
    fields=[str(f).removeprefix('METRIC_') for f in faults if str(f).startswith('METRIC_')]
    rules={
        'transactionQuantity':'所选交易来源的实际成交数量合计；数量不是委托记录数或实际成交记录数。按已声明业务口径从实际数量字段复算，并使用精确十进制字符串交付。',
        'assetRows':'资产来源的全部原始记录数。',
        'positionRows':'持仓来源的全部原始记录数。',
        'transactionRows':'所选交易来源的全部原始记录数，包含零成交量和撤单记录；实际成交记录数可以单独报告，不能替代来源原始记录数。',
    }
    descriptions='\n'.join(field+'：'+rules[field] for field in fields if field in rules)
    metric_checks=[{key:item[key] for key in ['field','expectedType','actualType','actualValuePreview','diagnostic'] if key in item} for item in validation.get('facts',{}).get('metricChecks',[]) if item.get('passed') is False]
    if metric_checks:descriptions+='\n以下为已交付结果的实际值、JSON 类型及既有验收类型要求（不包含验算答案）：'+json.dumps(metric_checks,ensure_ascii=False)
    output_errors=[str(f) for f in faults if str(f).startswith('OUTPUT_CONTRACT_VIOLATION:')]
    if output_errors:descriptions+='\n结构化输出真实错误：'+'; '.join(output_errors)
    source_rules={
        'REPORT_SEMANTIC_TRADE_COUNT_UNSUPPORTED':'正式报告中的成交证券数量或买卖成交记录数与原始实际成交记录不符。请按当前授权交易来源重新分组计数，区分证券去重数、记录笔数和成交数量；撤单或零成交记录不能计入实际成交。报告正文须使用真实计算结果，不得用静态文字代替计算。',
        'REPORT_SEMANTIC_DRYK_UNDECLARED_FLOATING':'来源对 DRYK 只声明“当日盈亏”，没有声明“浮动盈亏”或“仅反映浮动部分”；报告须保留已声明含义，不能自行缩窄口径。',
        'REPORT_SEMANTIC_LJYK_UNDECLARED_PERIOD':'来源对 LJYK 只声明“累计实现盈亏”，没有声明“自开户以来”或“所有已平仓”的完整历史范围，也未将累计限制为历史已平仓持仓；不得把未提供的范围写成来源已证明的事实。',
        'REPORT_SEMANTIC_INTRADAY_DIRECTION_UNSUPPORTED':'正式报告列举证券存在同品种日内买卖，但独立核对未证明这些证券均同时有买入和卖出实际成交；未成交或撤单不能作为已执行交易证据。请自行核对原始记录并修复业务分析与报告。',
        'REPORT_SEMANTIC_UNDECLARED_SNAPSHOT_CUTOFF':'来源提供了业务日期，但未声明快照为日终或收盘后状态。业务日期、文件采集时间或最后一笔委托时间不能自动证明快照截止时点；请保留来源已声明日期，明确未知时点，不得把未知时点写成已确认事实。',
        'REPORT_SEMANTIC_DRYK_UNDECLARED_COMPONENTS':'来源对 DRYK 仅声明“当日盈亏”，没有声明其包含资金利息、手续费等构成，也没有声明其仅反映证券市值变动；资产与持仓均须保留原始定义，未知组成不能作为已确认事实。',
    }
    descriptions+='\n'+'\n'.join(source_rules[f] for f in faults if f in source_rules)
    failed_claims=[{key:item[key] for key in ['path','claimType','securityCode','reportedCount','diagnostic'] if key in item} for item in validation.get('facts',{}).get('reportClaimChecks',[]) if item.get('passed') is False]
    if failed_claims:descriptions+='\n实际未通过的报告事实声明：'+json.dumps(failed_claims,ensure_ascii=False)
    failed_reports=[{'path':item['path'],'faults':item['faults']} for item in validation.get('facts',{}).get('reportChecks',[]) if item.get('faults')]
    if failed_reports:descriptions+='\n本次交付声明中未通过验算的正式 REPORT 文件：'+json.dumps(failed_reports,ensure_ascii=False)+'。修复说明和草稿应声明为 AUXILIARY；请核对 delivery_manifest.json 中的交付类型。'
    # Do not hand the model golden numbers or analysis code. It must inspect and
    # compute from the unchanged original files using the current native session.
    return ('Runtime 已完成对交付版本 '+version+' 的真实独立复算，未通过字段：'+', '.join(fields)+'。\n'
        +descriptions+'\n请依据当前工作区的真实原始数据核对并自行修复业务代码，实际重新执行，检查并更新报告和结构化结果。'
        '不要修改输入数据，不要硬编码验算值。保留来源原始记录数和实际成交记录数的不同含义。'
        '这是当前任务的真实验算回执；失败版本和完整证据已归档，尚未登记完成。')
