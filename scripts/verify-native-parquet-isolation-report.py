"""Check reported Markdown numbers against separately computed raw-file statistics.

This is evidence for review, not a substitute for execution evidence or reading the
report. Unsupported report layouts remain REVIEW_REQUIRED, never an automatic pass.
"""
import json,re,sys
from decimal import Decimal,InvalidOperation
from pathlib import Path

ROOT=Path('/opt/cline-parquet-isolation-20261110')
base=json.loads((ROOT/'evidence/independent-baseline.json').read_text())
aliases={'计数':'count','count':'count','均值':'mean','平均值':'mean','mean':'mean','标准差':'std','std':'std','最小值':'min','min':'min','最大值':'max','max':'max','q1':'25%','25%':'25%','中位数':'50%','median':'50%','50%':'50%','q3':'75%','75%':'75%','异常值数':'iqrOutliers'}
aliases.update({'异常数':'iqrOutliers','离群值数':'iqrOutliers','iqr':'iqr','下界':'lowerBound','上界':'upperBound','偏度':'skewness','峰度':'kurtosis'})
aliases.update({'std dev':'std','25th percentile':'25%','median (50%)':'50%','75th percentile':'75%','zeros':'zeros','negatives':'negatives','skewness':'skewness','kurtosis':'kurtosis','outliers (iqr)':'iqrOutliers'})
def cell(s):return s.strip().strip('`').strip().strip('*')
def number(s):
 s=cell(s).replace(',','').replace('，','');m=re.fullmatch(r'([-+]?\d+(?:\.\d+)?)(%)?',s)
 if not m:return None
 return Decimal(m[1])

for case in sys.argv[1:]:
 folder=ROOT/'runs'/case;report=folder/'workspace/report.md';program=folder/'workspace/analysis.py'
 checks=[];mismatches=[];schema=set();nulls=set();numeric=set()
 def check(label,actual,expected):
  if isinstance(actual,str):
   value=number(actual)
   if value is None:
    if actual!=expected:mismatches.append({'metric':label,'reported':actual,'expected':expected})
    else:checks.append(label)
    return
   tolerance=Decimal(1).scaleb(value.as_tuple().exponent)/2+Decimal('0.000000001') if value.as_tuple().exponent<0 else Decimal('0.000000001')
   passed=abs(value-Decimal(str(expected)))<=tolerance
  else:passed=actual==expected
  if passed:checks.append(label)
  else:mismatches.append({'metric':label,'reported':actual,'expected':expected})
 if report.exists():
  text=report.read_text(encoding='utf-8');headers=[];section='';field=None
  for line in text.splitlines():
   if line.startswith('#'):
    section=line;headers=[];field=line.lstrip('#').strip() if line.lstrip('#').strip() in base['numericDescribe'] else None
   if not line.lstrip().startswith('|'):continue
   row=[cell(s) for s in line.strip().strip('|').split('|')]
   if not row or all(re.fullmatch(r'[-: ]+',v) for v in row):continue
   key=row[0]
   if field and key.lower() in aliases and len(row)==2:
    metric=aliases[key.lower()]
    if metric in base['numericDescribe'][field]:expected=base['numericDescribe'][field][metric]
    elif metric=='skewness':expected=base['numericSkewness'][field]
    elif metric=='kurtosis':expected=base['numericKurtosis'][field]
    elif metric=='zeros':expected=base['numericZeroCounts'][field]
    elif metric=='negatives':expected=base['numericNegativeCounts'][field]
    elif metric=='iqrOutliers':expected=base['numericIqrOutlierCounts'][field]
    else:continue
    value=row[1].split(' (')[0];check(field+'.'+metric,value,expected);numeric.add(field);continue
   if len(row)>1 and row[1] in ['数量','计数','值','占比','count','value','Count','Value']:
    headers=row;continue
   if key.lower() in aliases and len(headers)>1 and all(c in base['numericDescribe'] for c in headers[1:]):
    metric=aliases[key.lower()]
    for i,c in enumerate(headers[1:],1):
     if metric in base['numericDescribe'][c]:check(c+'.'+metric,row[i],base['numericDescribe'][c][metric]);numeric.add(c)
    continue
   if key in base['pandasDtypes']:
    for i,h in enumerate(headers[1:],1):
     if i>=len(row):continue
     if h in ['数据类型','类型','dtype','Dtype','Data Type']:
      check(key+'.dtype',row[i],base['pandasDtypes'][key]);schema.add(key)
     if h in ['缺失值数量','缺失值数','缺失数','缺失数量','缺失数量 (个)','缺失值','Missing Count']:
      check(key+'.nulls',row[i],base['nulls'][key]);nulls.add(key)
     metric=aliases.get(h.lower())
     if metric and (metric in ['iqrOutliers','skewness','kurtosis'] or any(v in headers for v in ['均值','平均值','mean','Mean','IQR'])):
      if metric=='iqrOutliers':expected=base['numericIqrOutlierCounts'][key]
      elif metric=='skewness':expected=base['numericSkewness'][key]
      elif metric=='kurtosis':expected=base['numericKurtosis'][key]
      elif metric in ['iqr','lowerBound','upperBound']:
       q1=base['numericDescribe'][key]['25%'];q3=base['numericDescribe'][key]['75%'];iqr=q3-q1
       expected={'iqr':iqr,'lowerBound':q1-1.5*iqr,'upperBound':q3+1.5*iqr}[metric]
      elif key in base['numericDescribe']:expected=base['numericDescribe'][key][metric]
      else:continue
      check(key+'.'+metric,row[i],expected);numeric.add(key)
   elif key in ['总行数','行数','记录数','总记录数']:check('rows',row[1],base['rows'])
   elif key in ['总列数','列数','字段数','总字段数']:check('columns',row[1],base['columns'])
   elif key in ['完全重复行','重复行数','完全重复行数']:check('duplicateRows',row[1],base['duplicateRowsExcludingFirst'])
   elif key=='零时长':
    m=re.search(r'[\d,]+',row[1])
    if m:check('zeroDurationRows',m.group(),base['taxiDurationMinutes']['zero'])
   elif key=='<0' and '车费' in section:check('fare_amount<0 histogram',row[1],base['numericNegativeCounts']['fare_amount'])
   else:headers=row
  for label,expected in [('行数',base['rows']),('列数',base['columns']),('重复行数',base['duplicateRowsExcludingFirst'])]:
   for match in re.finditer(r'\*\*'+label+r'\*\*\s*:\s*([\d,]+)',text):check(label,match.group(1),expected)
  for match in re.finditer(r'整体缺失率[：:]?[^\d\n]*([\d.]+)%',text):check('overallMissingCellPercent',match.group(1),base['missingCellPercent'])
  for label,expected in [('Total Rows',base['rows']),('Total Columns',base['columns']),('Duplicate Rows',base['duplicateRowsExcludingFirst'])]:
   for match in re.finditer(r'\*\*'+label+r':\*\*\s*([\d,]+)',text):check(label,match.group(1),expected)
  for match in re.finditer(r'Overall Missing Percentage:\*\*\s*([\d.]+)%',text):check('overallMissingCellPercent',match.group(1),base['missingCellPercent'])
  if re.search(r'50%\s*以上[^\n]*小费[^\n]*为\s*0',text):check('claim: at least 50% zero tips',base['taxiExtraChecks']['zeroTipPercent']>=50,True)
  if '132 条非 2020 年 4 月' in text:check('pickupOutsideApril2020','132',base['taxiExtraChecks']['pickupOutsideApril2020'])
  if '> 100 英里的记录（约 100+' in text:check('claim: >=100 distance-over-100 records',base['taxiExtraChecks']['distanceOver100Miles']>=100,True)
 else:text=''
 result={'case':case,'programExists':program.exists(),'reportExists':report.exists(),'schemaFieldsChecked':sorted(schema),'nullFieldsChecked':sorted(nulls),'numericFieldsChecked':sorted(numeric),'passedNumericOrSchemaChecks':len(checks),'checks':checks,'mismatches':mismatches,'status':'FAILED' if mismatches or not program.exists() or not report.exists() else 'REVIEW_REQUIRED','requiresExecutionEvidenceAndHumanSemanticReview':True}
 (folder/'independent-acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({k:result[k] for k in ['case','passedNumericOrSchemaChecks','mismatches','status']},ensure_ascii=False))
