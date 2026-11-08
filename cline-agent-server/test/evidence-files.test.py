import unittest,tempfile,pathlib,os,sys
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'scripts'))
from evidence_files import safe_bytes,tree_bytes
class EvidenceBoundary(unittest.TestCase):
 def test_actual_file_and_parent_symlinks_never_read_foreign_data(self):
  with tempfile.TemporaryDirectory() as directory:
   base=pathlib.Path(directory);root=base/'own';root.mkdir();foreign=base/'foreign';foreign.mkdir();(foreign/'secret').write_text('OTHER_TENANT_PRIVATE');(root/'own.txt').write_text('OWN')
   (root/'foreign-file').symlink_to(foreign/'secret');(root/'foreign-dir').symlink_to(foreign,target_is_directory=True)
   for name in ['foreign-file','foreign-dir/secret','../foreign/secret']:
    with self.assertRaises((OSError,ValueError)):safe_bytes(root,name)
   files,skipped=tree_bytes(root);self.assertEqual(files,{'own.txt':b'OWN'});self.assertEqual(set(skipped),{'foreign-file','foreign-dir'})
 def test_fifo_is_rejected_without_blocking(self):
  with tempfile.TemporaryDirectory() as directory:
   os.mkfifo(pathlib.Path(directory)/'pipe')
   with self.assertRaises(ValueError):safe_bytes(directory,'pipe')
if __name__=='__main__':unittest.main()
