"""Independent statistics; run outside agent workspaces, never injected into the task."""
import hashlib,json,sys
from pathlib import Path
import pandas as pd
import pyarrow.parquet as pq
import numpy as np

p=Path(sys.argv[1]);df=pq.read_table(p).to_pandas()
numeric=df.select_dtypes(include='number')
summary={'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,
 'rows':len(df),'columns':len(df.columns),'arrowSchema':str(pq.read_schema(p)),
 'pandasDtypes':{c:str(v) for c,v in df.dtypes.items()},'uniqueCounts':{c:int(df[c].nunique()) for c in df},
 'memoryDeepBytes':int(df.memory_usage(deep=True).sum()),'memoryShallowBytes':int(df.memory_usage().sum()),
 'nulls':{c:int(v) for c,v in df.isna().sum().items()},
 'duplicateRowsExcludingFirst':int(df.duplicated().sum()),
 'duplicateRowsIncludingFirst':int(df.duplicated(keep=False).sum()),
 'numericDescribe':json.loads(numeric.describe().to_json()),
 'numericNegativeCounts':{c:int((numeric[c]<0).sum()) for c in numeric},
 'numericZeroCounts':{c:int((numeric[c]==0).sum()) for c in numeric},
 'totalMissingCells':int(df.isna().sum().sum()),'missingCellPercent':float(df.isna().sum().sum()/(len(df)*len(df.columns))*100),
 'numericSkewness':{c:float(numeric[c].skew()) for c in numeric},'numericKurtosis':{c:float(numeric[c].kurtosis()) for c in numeric},
 'correlations':json.loads(numeric.corr().to_json()),
 'datetimeRanges':{c:{'min':str(df[c].min()),'max':str(df[c].max())} for c in df.select_dtypes(include='datetime')},
 'valueCounts':{c:{str(k):int(v) for k,v in df[c].value_counts(dropna=False).head(20).items()} for c in df},
 'numericIqrOutlierCounts':{},'nonFiniteCounts':{c:int((np.isinf(numeric[c])).sum()) for c in numeric}}
for c in numeric:
 q1,q3=numeric[c].quantile([.25,.75]);iqr=q3-q1
 summary['numericIqrOutlierCounts'][c]=int(((numeric[c]<q1-1.5*iqr)|(numeric[c]>q3+1.5*iqr)).sum())
if {'tpep_pickup_datetime','tpep_dropoff_datetime'}.issubset(df):
 minutes=(df['tpep_dropoff_datetime']-df['tpep_pickup_datetime']).dt.total_seconds()/60
 summary['taxiDurationMinutes']={'describe':json.loads(minutes.describe().to_json()),'negative':int((minutes<0).sum()),'zero':int((minutes==0).sum()),'over24Hours':int((minutes>1440).sum())}
 summary['taxiExtraChecks']={'pickupMonthCounts':{str(k):int(v) for k,v in df['tpep_pickup_datetime'].dt.to_period('M').value_counts().items()},'distanceOver100Miles':int((df['trip_distance']>100).sum()),'distanceOver50Miles':int((df['trip_distance']>50).sum()),'pickupOutsideApril2020':int((df['tpep_pickup_datetime'].dt.to_period('M')!='2020-04').sum()),'zeroTipPercent':float((df['tip_amount']==0).sum()/len(df)*100)}
 summary['taxiExtraChecks']['samePickupDropoffZone']=int((df['PULocationID']==df['DOLocationID']).sum())
 components=['fare_amount','extra','mta_tax','tip_amount','tolls_amount','improvement_surcharge','congestion_surcharge']
 difference=(df[components].fillna(0).sum(axis=1)-df['total_amount']).abs()
 summary['fareCompositionDiagnostic']={'components':components,'nullPolicy':'fillna(0)',
  'absoluteDifferenceOver1':int((difference>1).sum()),'meanAbsoluteDifference':float(difference.mean()),'maxAbsoluteDifference':float(difference.max()),
  'native10DeclaredKeyDuplicates':int(df.duplicated(subset=['VendorID','tpep_pickup_datetime','passenger_count','trip_distance','fare_amount']).sum()),
  'native9DeclaredKeyDuplicates':int(df.duplicated(subset=['VendorID','tpep_pickup_datetime','tpep_dropoff_datetime','PULocationID','DOLocationID']).sum()),
  'durationLessThanOneMinute':int((minutes<1).sum())}
print(json.dumps(summary,ensure_ascii=False,indent=2,allow_nan=False))
