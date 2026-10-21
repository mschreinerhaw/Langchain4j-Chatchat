import {describe,it,expect} from 'vitest';
import {userSpaceEntries,defaultPurpose,folderTrail,validateAnalysisFiles} from './userSpaceFiles';
import {parseWorkspaceRoute} from './workspaceContext';
describe('unified user space',()=>{
  it('groups facts by purpose and keeps legacy scripts/data visible without duplicating execution copies',()=>{
    const entries=userSpaceEntries([{id:'doc',fileName:'rules.md',kind:'DOCUMENT',size:1,state:'AVAILABLE'}],
      [{id:'data',fileName:'input.csv',fileSize:3,status:'AVAILABLE'},{id:'cache',sourceAssetId:'doc',fileName:'rules.md',status:'AVAILABLE'}],
      [{id:'script',fileName:'analysis.py',sourceCode:'print(1)',currentVersion:2}],
      [{source:'asset',sourceId:'doc',folderId:'custom'}]);
    expect(entries).toHaveLength(3);expect(entries.map(file=>file.folderId)).toEqual(['custom','data','scripts']);
    expect(entries[2].size).toBe(8);expect(entries[2].version).toBe(2);
    expect(defaultPurpose({name:'query.sql',kind:'DOCUMENT'})).toBe('scripts');
    expect(defaultPurpose({name:'result.json',kind:'CONTEXT'})).toBe('history');
  });
  it('keeps a bounded ancestry when data contains a cycle',()=>{
    expect(folderTrail('a',[{id:'a',parentId:'b'},{id:'b',parentId:'a'}])).toHaveLength(2);
  });
  it('only sends explicitly selected analyzable originals and contexts',()=>{
    const file={source:'asset',sourceId:'file',name:'input.csv',kind:'DOCUMENT',state:'AVAILABLE',size:3};
    expect(validateAnalysisFiles([file,{...file,sourceId:'history',kind:'CONTEXT'}])).toEqual({fileIds:['file'],contextIds:['history']});
    expect(()=>validateAnalysisFiles([{...file,source:'python-script'}])).toThrow('请选择');
    expect(()=>validateAnalysisFiles([{...file,size:16*1024*1024+1}])).toThrow('16 MiB');
  });
  it('redirects both old script bookmarks to the shared user-space entry',()=>{
    expect(parseWorkspaceRoute('#/w/data-science/dataScienceScripts')).toEqual({workspaceId:'data-science',pageId:'dataScienceData'});
    expect(parseWorkspaceRoute('#dataScienceScripts')).toEqual({legacyView:'dataScienceData'});
  });
});
