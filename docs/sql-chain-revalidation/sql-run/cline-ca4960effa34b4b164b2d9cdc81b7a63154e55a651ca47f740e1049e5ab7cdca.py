import json, hashlib, collections, os
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import pyarrow.parquet as pq

MAN_ID = 'c48ed64264caab4fb6615c1d804396aa44c47c2f7a01c5f3936e50f15338f721'
SCH_ID = 'c83267bd572e947c4918b92dfa4906bcc5cac4b647b538670f7ff6b2b579bbc8'
EXP_MAN = '8b55d9fd0a2dd576ae2bdbfc4b81a6d8b3cebb5fbcff890b7fe8c32b743ecb31'
EXP_SCH = 'd81274014bb3d60da3dacf11c8615d238e4704040d1c3c434d935dd5cad0e480'

base = Path('/data/input')
man_path = base / MAN_ID / 'manifest.json'
sch_path = base / SCH_ID / 'schema.json'

def sha_file(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()

diag = {'checks': {}, 'structure': {}}
diag['checks']['manifestSha256'] = sha_file(man_path)
diag['checks']['schemaSha256'] = sha_file(sch_path)
assert diag['checks']['manifestSha256'] == EXP_MAN, 'manifest hash mismatch'
assert diag['checks']['schemaSha256'] == EXP_SCH, 'schema hash mismatch'

manifest = json.loads(man_path.read_text(encoding='utf-8'))
schema = json.loads(sch_path.read_text(encoding='utf-8'))
assert 'parts' in manifest and 'columns' in manifest, 'flat manifest expected'
diag['structure']['manifestRowCount'] = manifest['rowCount']
diag['structure']['manifestFormat'] = manifest['format']
diag['structure']['schemaFile'] = schema

colmap = {c['storageName']: c for c in manifest['columns']}
records = []
part_info = []
for part in manifest['parts']:
    p = base / part['fileId'] / part['fileName']
    got = sha_file(p)
    assert got == part['sha256'], 'part hash mismatch ' + part['fileName']
    tbl = pq.read_table(p)
    rows = tbl.to_pylist()
    assert len(rows) == part['rowCount'], 'part row count mismatch'
    part_info.append({'file': part['fileName'], 'rows': len(rows), 'sha256': got,
                      'columns': tbl.column_names})
    records.extend(rows)
diag['structure']['parts'] = part_info
assert len(records) == manifest['rowCount'] == 18, 'total row count mismatch'

# presence columns (pN) => missing vs null detection
presence_missing = []
logical = []
for r in records:
    for k, v in r.items():
        if k not in colmap and k.startswith('p') and k[1:].isdigit():
            if v is False:
                presence_missing.append(k)
    logical.append({colmap[k]['name']: v for k, v in r.items() if k in colmap})
diag['structure']['presenceColumnsMissing'] = presence_missing
assert len(logical) == 18

groups = collections.defaultdict(list)
for r in logical:
    groups[r['datasetReference']].append(r['record'])

diag['structure']['datasetReferences'] = sorted(groups.keys())
diag['structure']['rowsPerDataset'] = {k: len(v) for k, v in groups.items()}

YEARLY_REF = 'mcp_chatchat_mcp_server_tpl_212b88329a673657979943bbeaa7479b'
MONTHLY_REF = 'mcp_chatchat_mcp_server_tpl_b1e518f3811f3fb18d55dc959b24e3d7'
PAYLOAD_REF = 'internal_reconciliation#payload'

yearly = [json.loads(s, parse_float=Decimal) for s in groups[YEARLY_REF]]
monthly = [json.loads(s, parse_float=Decimal) for s in groups[MONTHLY_REF]]
payload_chunks = sorted([json.loads(s, parse_float=Decimal) for s in groups[PAYLOAD_REF]],
                        key=lambda x: int(x['chunkIndex']))

# reconstruct chunked governed payload strictly by declared offsets
payload_text = ''
for i, ch in enumerate(payload_chunks):
    assert int(ch['chunkIndex']) == i + 1, 'chunk index gap'
    assert int(ch['fromChar']) == len(payload_text), 'chunk coverage gap'
    payload_text += ch['content']
    assert int(ch['toChar']) == len(payload_text), 'chunk coverage overrun'
    assert ch.get('sourceComplete') is True, 'payload flagged incomplete'
payload = json.loads(payload_text)
diag['structure']['payloadKeys'] = sorted(payload.keys()) if isinstance(payload, dict) else str(type(payload))
diag['structure']['payloadChars'] = len(payload_text)

def D(x):
    return x if isinstance(x, Decimal) else Decimal(str(x))

def fmt_money(d):
    return f"{d.quantize(Decimal('0.01')):,}"

def fmt_int(i):
    return f"{int(i):,}"

# ---------- quality / structure ----------
quality = {}
for name, rows in (('yearly', yearly), ('monthly', monthly)):
    fields = sorted({k for r in rows for k in r})
    quality[name] = {f: {'null': sum(1 for r in rows if r.get(f) is None),
                         'absent': sum(1 for r in rows if f not in r)} for f in fields}
diag['quality'] = quality

yearly_years = [int(r['sales_year']) for r in yearly]
yearly_dup_years = [y for y, c in collections.Counter(yearly_years).items() if c > 1]
monthly_keys = [(int(r['sale_year']), int(r['sale_month']), r.get('category')) for r in monthly]
monthly_dup_keys = [k for k, c in collections.Counter(monthly_keys).items() if c > 1]
monthly_years = sorted({int(r['sale_year']) for r in monthly})
monthly_months = sorted({int(r['sale_month']) for r in monthly})
monthly_cats = sorted({str(r.get('category')) for r in monthly})

yearly_1999 = [r for r in yearly if int(r['sales_year']) == 1999]
monthly_1999 = [r for r in monthly if int(r['sale_year']) == 1999]

# per-month aggregation of the delivered monthly rows (declared dimensions: year, month, category)
bymonth = {}
for r in monthly_1999:
    m = int(r['sale_month'])
    a = bymonth.setdefault(m, {'rows': 0, 'qty': 0, 'paid': Decimal('0'), 'profit': Decimal('0'), 'cats': set(), 'n': 0})
    a['rows'] += int(r['row_count'])
    a['qty'] += int(r['total_quantity'])
    a['paid'] += D(r['total_net_paid'])
    a['profit'] += D(r['total_net_profit'])
    a['cats'].add(str(r.get('category')))
    a['n'] += 1
months_present = sorted(bymonth.keys())
months_absent = [m for m in range(1, 13) if m not in bymonth]

metrics = [
    ('销售行数（sales_rows / row_count）', '销售行数',
     int(yearly_1999[0]['sales_rows']) if yearly_1999 else None,
     sum(int(r['row_count']) for r in monthly_1999), 'int'),
    ('数量合计（total_quantity）', '数量合计',
     int(yearly_1999[0]['total_quantity']) if yearly_1999 else None,
     sum(int(r['total_quantity']) for r in monthly_1999), 'int'),
    ('净收款合计（total_net_paid）', '净收款合计',
     D(yearly_1999[0]['total_net_paid']) if yearly_1999 else None,
     sum((D(r['total_net_paid']) for r in monthly_1999), Decimal('0')), 'money'),
    ('利润合计（total_net_profit）', '利润合计',
     D(yearly_1999[0]['total_net_profit']) if yearly_1999 else None,
     sum((D(r['total_net_profit']) for r in monthly_1999), Decimal('0')), 'money'),
]
recon = []
for label, short, ann, mon, kind in metrics:
    diff = (ann - mon) if (ann is not None and mon is not None) else None
    pct = None
    if diff is not None and ann not in (None, 0):
        pct = (Decimal(diff) / Decimal(ann) * Decimal(100)).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
    recon.append({'label': label, 'short': short, 'annual': ann, 'monthly': mon,
                  'diff': diff, 'pct': pct, 'kind': kind})
diag['reconciliation'] = {r['short']: {'annual': str(r['annual']), 'monthly': str(r['monthly']),
                                       'diff': str(r['diff']), 'pct': str(r['pct'])} for r in recon}
diag['monthsPresent'] = months_present
diag['monthsAbsent'] = months_absent
diag['yearlyYears'] = yearly_years
diag['monthlyYears'] = monthly_years
diag['monthlyCats'] = monthly_cats
diag['yearlyDupYears'] = yearly_dup_years
diag['monthlyDupKeys'] = [list(k) for k in monthly_dup_keys]

def fnum(v, kind):
    if v is None:
        return '—'
    return fmt_money(v) if kind == 'money' else fmt_int(v)

exact_match = all(r['diff'] is not None and r['diff'] == 0 for r in recon)
any_diff = [r for r in recon if r['diff'] not in (None, 0)]

# ---------- report ----------
L = []
A = L.append
A('# tpcds 门店销售对账：商品主键 1 · 1999 年度汇总 vs 逐月明细')
A('')
A('## 1. 结论摘要')
A('')
if yearly_1999 and monthly_1999:
    if exact_match:
        A('在本次实际返回的两个结果集之间，1999 年度的**年度汇总**与**逐月明细加总**在销售行数、数量、净收款、利润四项上完全一致（差额均为 0），勾稽通过。')
    else:
        A('在本次实际返回的两个结果集之间，1999 年度的**年度汇总**与**逐月明细加总**存在差额，按项列示如下（年度 − 逐月）：')
        A('')
        for r in recon:
            if r['diff'] not in (None, 0):
                A(f"- {r['label']}：年度 {fnum(r['annual'], r['kind'])}，逐月加总 {fnum(r['monthly'], r['kind'])}，差额 **{fnum(r['diff'], r['kind'])}**（占年度 {r['pct']}%）。")
        A('')
        A('差额的成因**未能在已交付证据内分解**：两个结果集都是聚合输出，不含明细行，且逐月查询的实际生效参数（起止日期、最低购买数量、品类）未随数据集声明，因此本报告只确认差额的存在与量级，不把它判为数据错误，也不归因到某一业务原因。')
    A('')
    A(f"覆盖情况：逐月结果实际包含 1999 年的 {len(months_present)} 个月（{'、'.join(str(m) for m in months_present)} 月）" +
      (f"，**缺少 {'、'.join(str(m) for m in months_absent)} 月**；缺少月份可能代表该月无销售行，也可能是查询条件或返回不完整，已交付证据不足以区分，未按 0 补齐。" if months_absent else '，覆盖 1–12 月。'))
else:
    A('年度汇总或逐月明细中缺少 1999 年记录，无法完成 1999 年度的勾稽对账，具体缺口见下文。')
A('')
A('金额单位：两个结果集的生产者声明均未给出金额单位，本报告**不推断货币单位**，金额以原始数值列示。')
A('')
A('## 2. 数据来源与作用域')
A('')
A('| 数据集引用 | 生产声明用途 | 返回行数 | 本次使用范围 |')
A('| --- | --- | --- | --- |')
A(f'| `{YEARLY_REF}` | 限定商品 ss_item_sk=1，按实际销售日期年度汇总销售行数、数量、净收款和利润，排除无日期维度关联记录；无参数 | {len(yearly)} | 仅取 sales_year=1999 的行 |')
A(f'| `{MONTHLY_REF}` | 指定商品、起止日期、最低购买数量及可选品类的门店逐月销售、数量、净收款及利润；默认商品 1，NULL 品类不过滤 | {len(monthly)} | 取返回结果中 sale_year=1999 的行 |')
A(f'| `{PAYLOAD_REF}` | 分块交付的受治理结果载荷（非业务行；recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | {len(payload_chunks)} 块 | 仅用于重建方法说明，不含业务数值 |')
A('')
A('作用域核对（重要）：')
A('')
A(f"- 年度汇总模板声明为**无参数**，商品在主键层面固定为 ss_item_sk=1，覆盖全部年度；实际返回 {len(yearly)} 个年度（{'、'.join(str(y) for y in sorted(yearly_years))}），本次仅使用 1999 行，其余年度只作上下文。")
A(f"- 逐月模板有起止日期、最低购买数量、品类三类参数，但本次运行未提供生效参数绑定（templateParameterBinding 为空），因此**无法用配置证明**它实际绑定为 1999-01-01~1999-12-31、月度最低购买数量 0、品类不限；只能依据返回行实际的 sale_year/sale_month 取值判断覆盖区间。")
A(f"- 逐月结果为月粒度，无法验证年内的日边界（例如是否 1 月 1 日起、12 月 31 日止）。")
A(f"- 两个结果集都不含商品主键列；商品 ss_item_sk=1 的身份仅由生产者声明保证。")
A('')
A('## 3. 年度汇总结果（模板 `212b8832…`，全部年度，1999 在范围内）')
A('')
A('| sales_year（年度） | sales_rows（销售行数） | total_quantity（数量） | total_net_paid（净收款） | total_net_profit（利润） |')
A('| --- | --- | --- | --- | --- |')
for r in sorted(yearly, key=lambda x: int(x['sales_year'])):
    mark = ' **（对账口径）**' if int(r['sales_year']) == 1999 else ''
    A(f"| {int(r['sales_year'])}{mark} | {fmt_int(r['sales_rows'])} | {fmt_int(r['total_quantity'])} | {fmt_money(D(r['total_net_paid']))} | {fmt_money(D(r['total_net_profit']))} |")
A('')
A('## 4. 逐月明细（1999 年，模板 `b1e518f3…` 实际返回行）')
A('')
A('| sale_year | sale_month | category | row_count（行数） | total_quantity（数量） | total_net_paid（净收款） | total_net_profit（利润） |')
A('| --- | --- | --- | --- | --- | --- | --- |')
for r in sorted(monthly_1999, key=lambda x: int(x['sale_month'])):
    A(f"| {int(r['sale_year'])} | {int(r['sale_month'])} | {r.get('category')} | {fmt_int(r['row_count'])} | {fmt_int(r['total_quantity'])} | {fmt_money(D(r['total_net_paid']))} | {fmt_money(D(r['total_net_profit']))} |")
A('')
A(f"逐月合计（按 1999 年已返回月份加总）：行数 {fmt_int(sum(int(r['row_count']) for r in monthly_1999))}，数量 {fmt_int(sum(int(r['total_quantity']) for r in monthly_1999))}，净收款 {fmt_money(sum((D(r['total_net_paid']) for r in monthly_1999), Decimal('0')))}，利润 {fmt_money(sum((D(r['total_net_profit']) for r in monthly_1999), Decimal('0')))}。")
A('')
A('## 5. 勾稽对账（1999 年；年度汇总 − 逐月加总）')
A('')
A('| 指标（来源列） | 年度汇总 1999 | 逐月加总 1999 | 差额（年度−逐月） | 差额占年度 |')
A('| --- | --- | --- | --- | --- |')
for r in recon:
    pct = '—' if r['pct'] is None else f"{r['pct']}%"
    A(f"| {r['label']} | {fnum(r['annual'], r['kind'])} | {fnum(r['monthly'], r['kind'])} | {fnum(r['diff'], r['kind'])} | {pct} |")
A('')
A('对账口径说明：行数项比较年度 `sales_rows` 与逐月 `row_count` 之和；数量项比较 `total_quantity` 之和；金额项用 Decimal 从返回数值精确相加，保留两位小数比较，差额为 0 表示在该精度下完全相等。')
A('')
A('## 6. 差额解释与证据边界')
A('')
if exact_match:
    A('四项差额均为 0，说明对已返回的 1999 年月份而言，两个结果集的声明口径可以互相勾稽。但“无差额”不等于“查询范围就是用户要求的范围”：')
else:
    A('差额已量化，但以下可比子总计在已交付数据中**无法检验**，因此差额不能被判为数据错误，也不能归因到具体原因：')
A('')
A('1. 年度汇总声明“排除无日期维度关联记录”，逐月模板未声明其对日期维度的关联/排除规则——若两者过滤不同，任何一项都可能产生差额。')
A('2. 逐月模板的起止日期、最低购买数量实际生效值未随数据集交付；若最低购买数量不为 0，整月行可能被过滤掉，从年对账上表现为年度大于逐月。')
if months_absent:
    A(f"3. 逐月结果缺少 {'、'.join(str(m) for m in months_absent)} 月的行；无法区分“该月无销售”与“该月被条件过滤或返回不完整”，因此逐月加总不是年度总额的完备分解。")
else:
    A('3. 逐月结果覆盖 1–12 月，未观察到整月缺失。')
A('4. 金额单位未声明，任何按单位换算或跨源合并（如与其他货币/量表汇总）都不成立，本报告不做。')
A('5. 缺少明细行（两个数据集均为聚合输出），差额无法按商品、门店、渠道等维度进一步拆分。')
A('')
A('判定原则：只有当两侧数值的指标、范围、期间、口径、单位都确认一致时，差额才可判为数据错误。本次范围与参数口径未获确认，因此差额保留为**未决勾稽成分**。')
A('')
A('## 7. 数据质量与结构核查')
A('')
A('| 检查项 | 结果 |')
A('| --- | --- |')
A(f"| manifest SHA-256 | 与任务声明一致（{diag['checks']['manifestSha256'][:16]}…） |")
A(f"| schema SHA-256 | 与任务声明一致（{diag['checks']['schemaSha256'][:16]}…） |")
A(f"| 分片文件数与行数 | {len(part_info)} 个分片，合计 {len(records)} 行，与 manifest rowCount=18 一致 |")
A(f"| 逻辑行分组 | 年度汇总 {len(yearly)} 行、逐月明细 {len(monthly)} 行、载荷 {len(payload_chunks)} 块 |")
A(f"| 分块载荷完整性 | chunkIndex 连续、fromChar/toChar 覆盖 0–{len(payload_text)} 无缺口，sourceComplete=true |")
A(f"| 年度汇总销售年度唯一性 | 重复年度：{('无' if not yearly_dup_years else yearly_dup_years)} |")
A(f"| 逐月重复键 (sale_year,sale_month,category) | {('无' if not monthly_dup_keys else monthly_dup_keys)} |")
A(f"| 逐月覆盖年度 | {monthly_years}（品类取值：{'、'.join(monthly_cats)}） |")
A(f"| 缺失月份 | {('无' if not months_absent else '、'.join(str(m) for m in months_absent) + ' 月')} |")
nulls = []
for nm, rows in (('年度汇总', yearly), ('逐月明细', monthly)):
    for f, st in quality['yearly' if nm == '年度汇总' else 'monthly'].items():
        if st['null'] or st['absent']:
            nulls.append(f"{nm}.{f} null={st['null']}, absent={st['absent']}")
A(f"| 空值/缺失字段 | {('未观察到' if not nulls else '；'.join(nulls))} |")
A('')
A('## 8. 限制')
A('')
lim = []
lim.append('逐月模板的生效参数未交付（templateParameterBinding 为空），1999-01-01~1999-12-31、月度最低购买数量 0、品类不限这三项只能依据返回行的 sale_year/sale_month 观测推断覆盖情况，不能证明查询配置与用户要求等价。')
lim.append('年度汇总模板声明为无参数，商品固定为 ss_item_sk=1；两个结果集都不含商品主键列，商品身份仅由模板声明保证，无法在数据中复核。')
lim.append('逐月结果为月粒度，无法验证年内的日边界；逐月缺少的月份既可能是真实无销售，也可能是过滤或返回不完整，未按 0 补齐。')
if months_absent:
    lim.append(f"逐月明细缺少 {'、'.join(str(m) for m in months_absent)} 月，年度与逐月的差额因此不是完备分解，差额成因不可分解。")
lim.append('两个数据集均为聚合结果，无明细行；无法进一步拆分差额，也无法检验年度模板“排除无日期维度关联记录”与逐月模板的关联规则是否一致。')
lim.append('净收款与利润的金额单位未由数据来源声明，本报告不推断货币单位，也不做任何单位换算或跨源合并。')
lim.append('年度汇总结果另含 earliest_date / latest_date 两列，生产者未给出列级定义，本报告未使用这两列作时间范围判断（不按名称推断含义）。')
lim.append(f"内部载荷（{PAYLOAD_REF}）仅包含对账方法说明文本，不含业务数值，不能作为独立数据来源验证两侧结果。")
lim.append('对账在返回数值的两位小数精度下比较，不做四舍五入政策之外的调整。')
for i, t in enumerate(lim, 1):
    A(f'{i}. {t}')
A('')
A('## 9. 字段溯源')
A('')
A('| 数据集 | 源字段 | 声明含义 | 声明来源 | 状态 |')
A('| --- | --- | --- | --- | --- |')
prov = []
def add_prov(ds, field, meaning, src, status='CONFIRMED'):
    prov.append({'datasetReference': ds, 'sourceField': field, 'meaning': meaning,
                 'declarationSource': src, 'status': status})
    A(f"| `{ds}` | `{field}` | {meaning} | {src} | {status} |")

y_src = f'inputVersions.sourceDefinition.description（模板 {YEARLY_REF}）'
m_src = f'inputVersions.sourceDefinition.description（模板 {MONTHLY_REF}）'
p_src = 'inputVersions 表示声明 runtime_os.coding.source-representation.v1（recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW）'
Y = YEARLY_REF; M = MONTHLY_REF
add_prov(Y, 'sales_year', '销售发生的年度（声明按其“实际销售日期”年度汇总）', y_src)
add_prov(Y, 'sales_rows', '该年度的销售行数', y_src)
add_prov(Y, 'total_quantity', '该年度数量合计（数量单位未声明）', y_src)
add_prov(Y, 'total_net_paid', '该年度净收款合计（金额单位未声明）', y_src)
add_prov(Y, 'total_net_profit', '该年度利润合计（金额单位未声明）', y_src)
add_prov(M, 'sale_year', '销售发生的年度（逐月明细）', m_src)
add_prov(M, 'sale_month', '销售发生的月份（逐月明细，月粒度）', m_src)
add_prov(M, 'category', '品类维度取值（模板声明“可选品类”，NULL 表示不过滤）', m_src)
add_prov(M, 'row_count', '该月销售行数', m_src)
add_prov(M, 'total_quantity', '该月数量合计（数量单位未声明）', m_src)
add_prov(M, 'total_net_paid', '该月净收款合计（金额单位未声明）', m_src)
add_prov(M, 'total_net_profit', '该月利润合计（金额单位未声明）', m_src)
add_prov(PAYLOAD_REF, 'content', '分块载荷内容（对账方法说明文本，非业务记录）', p_src)
add_prov(PAYLOAD_REF, 'chunkIndex', '分块序号（用于按声明顺序重建）', p_src)
add_prov(PAYLOAD_REF, 'fromChar', '分块起始字符偏移（覆盖性校验）', p_src)
add_prov(PAYLOAD_REF, 'toChar', '分块结束字符偏移（覆盖性校验）', p_src)
add_prov(PAYLOAD_REF, 'sourceComplete', '来源完整性标志', p_src)
A('')
A('派生指标（本报告计算，非源字段）：逐月加总 = Σ 各月 `row_count`/`total_quantity`/`total_net_paid`/`total_net_profit`；差额 = 年度 `sales_rows`/`total_quantity`/`total_net_paid`/`total_net_profit` − 对应逐月加总；差额占年度 = 差额 ÷ 年度值（年度值为 0 时不计算）。')
A('')
A(f"分析状态：**PARTIAL** — 1999 年度的年度/逐月勾稽已按实际返回结果完成并量化差额；但逐月查询的生效参数、年内日边界、缺失月份语义、金额单位未获确认，不能宣称查询范围与用户要求完全等价。")

report_md = '\n'.join(L)

row = {
    'reportMarkdown': report_md,
    'evidenceReferences': [YEARLY_REF, MONTHLY_REF, PAYLOAD_REF],
    'limitations': lim,
    'analysisStatus': 'PARTIAL',
    'fieldProvenance': prov,
}

outdir = Path('/workspace/output')
(outdir / 'results').mkdir(parents=True, exist_ok=True)
(outdir / 'validation').mkdir(parents=True, exist_ok=True)
with open(outdir / 'results' / 'rows.jsonl', 'w', encoding='utf-8') as f:
    f.write(json.dumps(row, ensure_ascii=False) + '\n')
with open(outdir / 'validation' / 'diagnostics.json', 'w', encoding='utf-8') as f:
    json.dump(diag, f, ensure_ascii=False, indent=2, default=str)
print('written', len(report_md), 'chars; recon:', [(r['short'], str(r['diff'])) for r in recon])
