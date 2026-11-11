"""Export a local image to a verified OCI image layout, without a registry.

The resulting manifest digest is an OCI artifact digest, not a RepoDigest from a
registry push. Streams Docker layer tar files; never loads an image into RAM.
"""
from pathlib import Path
import argparse, gzip, hashlib, json, os, subprocess, tarfile, time

p=argparse.ArgumentParser()
p.add_argument('--docker-host',required=True)
p.add_argument('--image',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();os.umask(0o077)
if not a.image.startswith('sha256:') or a.output.exists():raise RuntimeError('Pinned image and fresh output directory required')
a.output.mkdir(parents=True,mode=0o700);blobs=a.output/'blobs/sha256';blobs.mkdir(parents=True)
engine=['docker','--host',a.docker_host]
identity=json.loads(subprocess.check_output(engine+['image','inspect',a.image]))[0]
if identity['Id']!=a.image:raise RuntimeError('Image identity changed')
process=subprocess.Popen(engine+['image','save',a.image],stdout=subprocess.PIPE)
entries={};manifest=None
with tarfile.open(fileobj=process.stdout,mode='r|') as stream:
    for entry in stream:
        if entry.islnk():entries[entry.name]=entries[entry.linkname];continue
        if not entry.isfile():continue
        source=stream.extractfile(entry)
        if entry.name=='manifest.json':manifest=json.load(source);continue
        if not (entry.name.endswith('.tar') or entry.name.endswith('.json') or entry.name.startswith('blobs/sha256/')):continue
        leading=source.read(min(512,entry.size))
        compressed=leading.startswith(b'\x1f\x8b')
        zstd=leading.startswith(b'\x28\xb5\x2f\xfd')
        is_json=leading.lstrip().startswith(b'{')
        layer=entry.name.endswith('.tar') or entry.name.startswith('blobs/sha256/') and not is_json
        temp=blobs/('pending-'+str(time.time_ns()))
        with temp.open('xb') as out:
            if layer and not compressed and not zstd:
                with gzip.GzipFile(filename='',mode='wb',fileobj=out,mtime=0) as compressed:
                    compressed.write(leading)
                    while block:=source.read(1024*1024):compressed.write(block)
            else:
                out.write(leading)
                while block:=source.read(1024*1024):out.write(block)
        digest=hashlib.sha256()
        with temp.open('rb') as input:
            while block:=input.read(1024*1024):digest.update(block)
        digest=digest.hexdigest();size=temp.stat().st_size;target=blobs/digest
        if target.exists():temp.unlink() # This export's own duplicate temporary blob only.
        else:temp.rename(target)
        entries[entry.name]={'digest':'sha256:'+digest,'size':size,
            'mediaType':'application/vnd.oci.image.layer.v1.tar+'+('zstd' if zstd else 'gzip') if layer else 'application/vnd.oci.image.config.v1+json'}
if process.wait()!=0 or not manifest or len(manifest)!=1:raise RuntimeError('Docker image export incomplete')
config=entries[manifest[0]['Config']]
if config['digest']!=a.image:raise RuntimeError('Exported image config hash differs')
document={'schemaVersion':2,'mediaType':'application/vnd.oci.image.manifest.v1+json',
    'config':{'mediaType':'application/vnd.oci.image.config.v1+json',**config},
    'layers':[entries[path] for path in manifest[0]['Layers']]}
raw=json.dumps(document,separators=(',',':')).encode();digest=hashlib.sha256(raw).hexdigest();(blobs/digest).write_bytes(raw)
index={'schemaVersion':2,'manifests':[{'mediaType':document['mediaType'],'digest':'sha256:'+digest,'size':len(raw),
    'platform':{'os':identity['Os'],'architecture':identity['Architecture']}}]}
(a.output/'index.json').write_text(json.dumps(index,indent=2));(a.output/'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}')
record={'imageId':a.image,'ociManifestDigest':'sha256:'+digest,'registryRepoDigest':None,
    'layout':str(a.output),'layerCount':len(document['layers']),'officialSourceCommit':'a459f1d04dea030833b558be645011ab739c463b'}
(a.output/'export-evidence.json').write_text(json.dumps(record,indent=2));print(json.dumps(record,indent=2))
