"""Prepare an explicitly synthetic, versioned Parquet object using existing Silo.

Run as the trusted acceptance controller; never expose storage credentials to Core.
"""
import argparse, hashlib, io, json, uuid
from pathlib import Path
import botocore.session
from botocore.config import Config
import pyarrow as pa
import pyarrow.parquet as pq

p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
cfg=json.loads((a.stage/'config/native-node/gateway.json').read_text());tenant=next(iter(cfg['tenants']))
origin=cfg['s3']['allowedOrigins'][0];task='parquet-'+uuid.uuid4().hex;run='run-'+uuid.uuid4().hex
prefix='tenants/'+tenant+'/acceptance/'+task+'/'+run+'/'
client=botocore.session.get_session().create_client('s3',endpoint_url=origin,region_name='us-east-1',
    aws_access_key_id=(a.stage/'config/secrets/asset-access-key').read_text().strip(),
    aws_secret_access_key=(a.stage/'config/secrets/asset-secret-key').read_text().strip(),
    config=Config(signature_version='s3v4',s3={'addressing_style':'path'}))
output=io.BytesIO();pq.write_table(pa.table({'label':['alpha','beta','gamma'],'amount':[12.5,20.0,15.0]}),output)
data=output.getvalue();digest=hashlib.sha256(data).hexdigest();key=prefix+'input/data/expenses.parquet'
receipt=client.put_object(Bucket='runtime-assets',Key=key,Body=data,Metadata={'sha256':digest,'data-status':'synthetic'})
version=receipt.get('VersionId')
if not version or version=='null':raise RuntimeError('Immutable Silo versioning required')
url=client.generate_presigned_url('get_object',Params={'Bucket':'runtime-assets','Key':key,'VersionId':version},ExpiresIn=1800)
plan={'tenantId':tenant,'taskId':task,'runId':run,'inputSnapshotVersion':'synthetic-parquet-'+digest,
    'inputPrefix':'/runtime-assets/'+prefix+'input/','outputPrefix':'/runtime-assets/'+prefix+'output/',
    'inputs':[{'path':'input/data/expenses.parquet','size':len(data),'sha256':digest,'objectVersion':version,'url':url}]}
a.output.write_text(json.dumps(plan));a.output.chmod(0o600)
print(json.dumps({'synthetic':True,'realSilo':True,'parquetSize':len(data),'sha256':digest,'versioned':True}))
