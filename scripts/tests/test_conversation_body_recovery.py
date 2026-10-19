import importlib.util
import json
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('recovery',Path(__file__).resolve().parents[1]/'prepare-conversation-body-recovery.py')
recovery=importlib.util.module_from_spec(spec);spec.loader.exec_module(recovery)

class BodyRecoveryTest(unittest.TestCase):
    def fixtures(self):
        identity={'tenant_id':'tenant','user_id':'user','session_id':'session'}
        indexes=[dict(identity,message_id=role,role=role,created_at='2026-10-19T00:00:00+00:00',
                      rocks_key='chat:tenant:session:1792368000000:'+role)
                 for role in ('user','assistant')]
        task=dict(identity,task_id='task',canonical_state='FAILED',question='persisted question',execution_attempt_id='attempt')
        event=dict(identity,task_id='task',attempt_id='attempt',event_id='event',created_at=1,
                   payload_json=json.dumps({'answer':'persisted answer','toolTraces':[{'tool':'python_execute'}],
                                            'uiResponse':{'uiArtifact':{'artifactId':'artifact'}}}))
        return indexes,[task],[event]

    def test_preserves_original_ids_failure_state_and_rich_response(self):
        indexes,tasks,events=self.fixtures()
        candidates,unresolved=recovery.prepare(indexes,tasks,events,indexes)
        self.assertEqual(unresolved,[])
        self.assertEqual(candidates[0]['detail']['messageId'],'user')
        self.assertEqual(candidates[1]['detail']['status'],'failed')
        self.assertEqual(candidates[1]['detail']['traces'],[{'tool':'python_execute'}])
        self.assertEqual(candidates[1]['detail']['uiResponse']['uiArtifact']['artifactId'],'artifact')
        self.assertNotIn('detail',indexes[0])

    def test_refuses_ambiguous_multiturn_mapping(self):
        indexes,tasks,events=self.fixtures()
        candidates,unresolved=recovery.prepare(indexes+[dict(indexes[0],message_id='another')],tasks,events,indexes)
        self.assertEqual(candidates,[])
        self.assertEqual(len(unresolved),2)

    def test_refuses_other_tenant_response_and_other_attempt(self):
        for field,value in [('tenant_id','other'),('attempt_id','older-attempt')]:
            indexes,tasks,events=self.fixtures();events[0][field]=value
            candidates,unresolved=recovery.prepare(indexes,tasks,events,[indexes[1]])
            self.assertEqual(candidates,[])
            self.assertEqual(unresolved[0]['reason'],'NO_PERSISTED_RESPONSE')

    def test_never_converts_an_active_task_to_completed_history(self):
        indexes,tasks,events=self.fixtures();tasks[0]['canonical_state']='RUNNING'
        candidates,unresolved=recovery.prepare(indexes,tasks,events,indexes)
        self.assertEqual(candidates,[])
        self.assertTrue(all(r['reason']=='NONTERMINAL_TASK' for r in unresolved))

    def test_original_key_controls_timestamp_after_legacy_sql_timezone_conversion(self):
        indexes,tasks,events=self.fixtures();indexes[0]['created_at']='2026-10-18T16:00:00+00:00'
        candidates,_=recovery.prepare(indexes,tasks,events,[indexes[0]])
        self.assertEqual(candidates[0]['detail']['createdAt'],'2026-10-19T00:00:00Z')

if __name__=='__main__':unittest.main()
