"""Verify clean runtime boundaries without a Docker daemon."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('compact',Path(__file__).with_name('prepare-compact-runtime-images.py'))
compact=importlib.util.module_from_spec(spec);spec.loader.exec_module(compact)


class PrefixTest(unittest.TestCase):
    def config(self):
        return {'architecture':'amd64','os':'linux','rootfs':{'diff_ids':['os','jre','sql','jar1','jar2']},
                'config':{'Env':['PATH=/bin','JAVA_HOME=/java','SECRET=private','APP_OPTION=x'],'Entrypoint':['java','-jar','/srv/app/app.jar']},
                'history':[{'created_by':'OS'},{'created_by':'JRE'},
                           {'created_by':'ENV JAVA_HOME=/java','empty_layer':True},
                           {'created_by':"RUN pip install sqlglot; assert sqlglot.__version__=='30.23.0'"},
                           {'created_by':'COPY app.jar /srv/app/app.jar'},
                           {'created_by':'COPY app.jar /srv/app/app.jar'}]}

    def test_only_exact_dependency_layers_survive_and_no_application_config_is_inherited(self):
        source=self.config();original=copy.deepcopy(source)
        clean,count=compact.runtime_prefix(source)
        self.assertEqual(count,3);self.assertEqual(clean['rootfs']['diff_ids'],['os','jre','sql'])
        self.assertEqual(clean['config']['Env'],['PATH=/bin','JAVA_HOME=/java'])
        self.assertNotIn('Entrypoint',clean['config']);self.assertEqual(source,original)
        self.assertEqual(clean['config']['Labels'],{})
        self.assertEqual(len(clean['history']),4)

    def test_application_layer_before_dependency_boundary_is_rejected(self):
        config=self.config();config['history'].insert(1,{'created_by':'COPY app.jar /srv/app/app.jar'})
        with self.assertRaises(ValueError):compact.runtime_prefix(config)

    def test_missing_pinned_dependency_boundary_is_rejected(self):
        config=self.config();config['history'][3]['created_by']='RUN pip install sqlglot'
        with self.assertRaises(StopIteration):compact.runtime_prefix(config)


if __name__=='__main__':unittest.main()
