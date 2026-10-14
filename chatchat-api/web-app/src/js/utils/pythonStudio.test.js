import { describe, expect, it } from 'vitest';
import { pythonOutline, searchPythonWorkspace, pythonSuggestionIsCurrent } from './pythonStudio';

describe('Python Studio navigation and edit boundaries', () => {
  it('finds nested and async definitions without treating docstrings, strings or comments as symbols', () => {
    const code = `# def fake():\n"""\ndef hidden():\n"""\nclass Report:\n    async def run(self):\n        return "def not_a_function():"\ndef summarize(values):\n    pass`;
    expect(pythonOutline(code)).toEqual([
      {name:'Report',kind:'class',line:5,indent:0},
      {name:'run',kind:'function',line:6,indent:4},
      {name:'summarize',kind:'function',line:8,indent:0}
    ]);
  });
  it('searches only supplied scripts, with content line navigation and a separate command mode', () => {
    const scripts=[{id:'allowed',fileName:'analysis.py',sourceCode:'import json\ndef total(values):\n    return sum(values)'}];
    expect(searchPythonWorkspace(scripts,'TOTAL')[0]).toMatchObject({kind:'symbol',line:2,detail:'analysis.py:2'});
    expect(searchPythonWorkspace(scripts,'sum',[],'files')).toEqual([]);
    expect(searchPythonWorkspace(scripts,'> save',[{id:'save',label:'保存文件',keywords:'save'}])[0]).toMatchObject({id:'save',kind:'command'});
    expect(searchPythonWorkspace(scripts,'private.py')).toEqual([]);
    expect(searchPythonWorkspace([{fileName:'many.py',sourceCode:'x\n'.repeat(1000)}],'x')).toHaveLength(60);
  });
  it('allows an AI patch only for the same tab and exact original content', () => {
    const context={key:'tab1',source:'print(1)'};
    expect(pythonSuggestionIsCurrent(context,'tab1','print(1)')).toBe(true);
    expect(pythonSuggestionIsCurrent(context,'tab2','print(1)')).toBe(false);
    expect(pythonSuggestionIsCurrent(context,'tab1','print(2)')).toBe(false);
    expect(pythonSuggestionIsCurrent(null,'tab1','print(1)')).toBe(false);
  });
});
