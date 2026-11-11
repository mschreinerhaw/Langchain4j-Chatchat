import unittest
from native_execution_observation import observe_python_repair

class NativeObservationTest(unittest.TestCase):
    def event(self,id,tool,result,kind='tool.completed',success=True,query='python project.py'):
        return {'eventId':str(id),'eventType':kind,'payload':{'toolName':tool,'output':[{'result':result,'query':query,'success':success}]}}
    def test_shell_wrapper_success_does_not_hide_actual_python_error(self):
        events=[self.event(1,'run_commands','Traceback (most recent call last):\nTypeError: bad\nEXIT_CODE=1'),
            self.event(2,'editor','edited'),self.event(3,'run_commands','report generated\nEXIT_CODE=0')]
        result=observe_python_repair(events)
        self.assertEqual(result['failures'][0]['pythonExitCode'],1)
        self.assertTrue(result['subsequentPythonExecution'])
    def test_source_diff_and_failed_later_commands_cannot_fake_repair(self):
        events=[self.event(1,'editor','Traceback (most recent call last):\nTypeError: example'),
            self.event(2,'run_commands','unrelated failure',kind='tool.failed',success=False)]
        self.assertFalse(observe_python_repair(events)['actualErrorObserved'])
        events.insert(1,self.event(2,'run_commands','Traceback (most recent call last):\nTypeError: bad',kind='tool.failed',success=False))
        self.assertFalse(observe_python_repair(events)['subsequentPythonExecution'])

if __name__=='__main__':unittest.main()
