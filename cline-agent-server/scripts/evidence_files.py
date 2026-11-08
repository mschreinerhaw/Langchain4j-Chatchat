"""Read untrusted, stopped workspace snapshots without following host-side symlinks."""
import os,pathlib,stat
LIMIT=2*1024*1024
def safe_bytes(root,relative):
 relative=pathlib.Path(relative)
 if relative.is_absolute() or not relative.parts or any(p in ['..','.'] for p in relative.parts):raise ValueError('Unsafe evidence path')
 fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 try:
  for part in relative.parts[:-1]:
   next_fd=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd);os.close(fd);fd=next_fd
  item=os.open(relative.parts[-1],os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
  try:
   metadata=os.fstat(item)
   if not stat.S_ISREG(metadata.st_mode) or metadata.st_size>LIMIT:raise ValueError('Non-regular or oversized evidence file')
   chunks=[];size=0
   while True:
    b=os.read(item,65536)
    if not b:break
    size+=len(b)
    if size>LIMIT:raise ValueError('Evidence file grew beyond limit')
    chunks.append(b)
   return b''.join(chunks)
  finally:os.close(item)
 finally:os.close(fd)
def tree_bytes(root):
 root=pathlib.Path(root);files={};skipped=[]
 def walk(relative):
  directory=root/relative
  for name in os.listdir(directory):
   if name=='.git':continue
   path=relative/name;metadata=os.lstat(root/path)
   if stat.S_ISLNK(metadata.st_mode):skipped.append(str(path));continue
   if stat.S_ISDIR(metadata.st_mode):walk(path)
   elif stat.S_ISREG(metadata.st_mode):files[str(path)]=safe_bytes(root,path)
   else:skipped.append(str(path))
   if len(files)>8192 or sum(map(len,files.values()))>32*1024*1024:raise ValueError('Workspace evidence exceeds export bounds')
 walk(pathlib.Path());return files,skipped
