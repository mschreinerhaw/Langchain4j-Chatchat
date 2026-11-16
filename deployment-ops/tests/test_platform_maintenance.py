import hashlib,json,sys,tempfile,unittest,zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'executor'))
from platform_maintenance import extract_release,checked_archive,below

class MaintenanceSafetyTests(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def archive(self,entries):
        target=self.root/'release.zip'
        with zipfile.ZipFile(target,'w') as output:
            for name,value in entries.items():output.writestr(name,value)
        return target
    def test_release_cannot_escape_target_or_add_uninventoried_payload(self):
        for name in ['../escape','/absolute','a\\escape']:
            archive=self.archive({name:b'invalid'})
            with self.assertRaises(ValueError):extract_release(archive,self.root/'target')
        metadata={'format':'deployment-ops-native/v1','schemaVersion':2,'files':{}}
        archive=self.archive({'release.json':json.dumps(metadata),'unexpected.bin':b'invalid'})
        with self.assertRaises(ValueError):extract_release(archive,self.root/'target')
    def test_corrupt_transport_is_rejected_before_maintenance(self):
        target=self.root/'upgrade.zip';target.write_bytes(b'original')
        target.with_name(target.name+'.sha256').write_text(hashlib.sha256(b'original').hexdigest())
        checked_archive(target);target.write_bytes(b'corrupt')
        with self.assertRaises(ValueError):checked_archive(target)
    def test_state_paths_cannot_escape_root(self):
        with self.assertRaises(ValueError):below(self.root/'../outside',self.root)

if __name__=='__main__':unittest.main()
