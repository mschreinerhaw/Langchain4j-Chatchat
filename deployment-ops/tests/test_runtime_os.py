import json,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'executor'))
from package_store import authorize_manifest
from runtime_os import RuntimeOsAdapter

class NativeRuntimeSafetyTests(unittest.TestCase):
    def test_regular_signer_cannot_gain_native_docker_administration(self):
        manifest={'name':'runtime-os','runtimeInstaller':{'protocol':'runtime-os-native/v1'}}
        with self.assertRaises(ValueError):authorize_manifest(manifest,{'allowedPackages':['runtime-os']})
        with self.assertRaises(ValueError):authorize_manifest(manifest,{'allowedPackages':['another-app'],'capabilities':['runtime-os-native/v1']})
        authorize_manifest(manifest,{'allowedPackages':['runtime-os'],'capabilities':['runtime-os-native/v1']})
    def test_image_lookup_failure_does_not_overwrite_release_tags(self):
        class Base:
            def __init__(self):self.calls=[]
            def engine(self,method,path,body=None):
                self.calls.append(method)
                if method=='GET':raise ValueError('Docker backend temporarily unavailable')
        base=Base();adapter=RuntimeOsAdapter(base)
        with self.assertRaises(ValueError):adapter.aliases({'api':{'reference':'runtime-os-api:verified','imageId':'sha256:'+'a'*64}})
        self.assertEqual(base.calls,['GET'])
    def test_native_installer_logs_remove_supplied_and_generated_secrets(self):
        class Base:engine=None
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'installation.json').write_text(json.dumps({'adminPassword':'GeneratedAdmin123','secrets':{'issuerKey':'private-generated-issuer-key'}}))
            result=RuntimeOsAdapter(Base()).redact('ProvidedPassword123 GeneratedAdmin123 private-generated-issuer-key',{'parameters':{'osAdminPassword':'ProvidedPassword123'}},root)
            self.assertNotIn('ProvidedPassword123',result);self.assertNotIn('GeneratedAdmin123',result);self.assertNotIn('private-generated-issuer-key',result)

if __name__=='__main__':unittest.main()
