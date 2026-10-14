import {describe,it,expect} from 'vitest';
import {pythonProposalSource,sqlGenerationAction} from './studioAi';
describe('Studio code proposals',()=>{
  it('reviews exactly the selected replacement while retaining surrounding code',()=>{
    expect(pythonProposalSource({source:'a = 1\nb = 2\nc = 3',selectionOffsets:{start:6,end:11}},
      {action:'fix',code:'b = 20',replaceSelection:true})).toBe('a = 1\nb = 20\nc = 3');
  });
  it('uses the request cursor for continuation rather than the later editor cursor',()=>{
    expect(pythonProposalSource({source:'first\nlast',cursorOffset:6},{action:'continue',code:'middle\n'})).toBe('first\nmiddle\nlast');
  });
  it('reviews test generation as a separate new file',()=>{
    expect(pythonProposalSource({source:'existing()'},{action:'test',code:'test_existing()'})).toBe('test_existing()');
  });
  it('restores the operation from historical SQL requests, preserving legacy defaults',()=>{
    expect(sqlGenerationAction({generation:{prepared:{taskRequest:{toolInput:{sqlStudioAction:'explain'}}}}})).toBe('explain');
    expect(sqlGenerationAction({generation:{}})).toBe('generate');
  });
});
