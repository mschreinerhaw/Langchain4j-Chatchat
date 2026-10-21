import {describe,it,expect} from 'vitest';
import {exceedsChatTextLimit,chatTextFile,pastedFiles,validateChatFiles} from './chatUserAssets';
import PromptComposer from '../components/PromptComposer';
const read=file=>file.text();
describe('chat file input',()=>{
  it('drops files once, preserves text dragging, and prevents navigation while busy',()=>{
    const files=[new File(['value\n1'],'drag.csv')];let prevented=0;const emitted=[];
    const context={loading:false,$emit:(event,value)=>emitted.push({event,value})};
    const event={dataTransfer:{files,types:['Files']},preventDefault:()=>prevented++};
    PromptComposer.methods.handleDragOver.call(context,event);
    PromptComposer.methods.handleDrop.call(context,event);
    expect(prevented).toBe(2);expect(emitted).toEqual([{event:'files',value:files}]);
    context.loading=true;PromptComposer.methods.handleDrop.call(context,event);
    expect(prevented).toBe(3);expect(emitted).toHaveLength(1);
    const text={dataTransfer:{files:[],types:['text/plain']},preventDefault:()=>prevented++};
    PromptComposer.methods.handleDragOver.call(context,text);PromptComposer.methods.handleDrop.call(context,text);
    expect(prevented).toBe(3);
  });
  it('does not submit while the Chinese IME is confirming composition',()=>{
    let sent=0,prevented=0;const context={send:()=>sent++};
    PromptComposer.methods.handleEnter.call(context,{isComposing:true,preventDefault:()=>prevented++});
    expect(sent).toBe(0);expect(prevented).toBe(0);
    PromptComposer.methods.handleEnter.call(context,{isComposing:false,preventDefault:()=>prevented++});
    expect(sent).toBe(1);expect(prevented).toBe(1);
  });
  it('uses Unicode character boundary and preserves every Markdown byte',async()=>{
    expect(exceedsChatTextLimit('😀'.repeat(8000))).toBe(false);
    expect(exceedsChatTextLimit('😀'.repeat(8001))).toBe(true);
    const original='  # 原始文本\r\n'+('完整内容😀\n'.repeat(2000))+'\n  ';
    const file=chatTextFile(original);expect(file.name).toMatch(/\.md$/);expect(await read(file)).toBe(original);
  });
  it('captures pasted files once and leaves ordinary text paste untouched',()=>{
    const file=new File(['x'],'sample.csv');
    expect(pastedFiles({items:[{kind:'file',getAsFile:()=>file}],files:[file]})).toEqual([file]);
    let emitted;let prevented=false;
    const context={loading:false,$emit:(event,files)=>{emitted={event,files};}};
    PromptComposer.methods.handlePaste.call(context,{clipboardData:{items:[{kind:'string'}]},preventDefault:()=>{prevented=true;}});
    expect(prevented).toBe(false);expect(emitted).toBeUndefined();
    PromptComposer.methods.handlePaste.call(context,{clipboardData:{files:[file]},preventDefault:()=>{prevented=true;}});
    expect(prevented).toBe(true);expect(emitted).toEqual({event:'files',files:[file]});
  });
  it('supports a file-only submission and blocks files still uploading',()=>{
    const sent=[];const context={draft:'',attachments:[{state:'ready'}],loading:false,$emit:(event,payload)=>sent.push({event,payload})};
    PromptComposer.methods.send.call(context);expect(sent).toHaveLength(1);expect(sent[0].payload).not.toHaveProperty('webSearch');
    context.attachments[0].state='uploading';PromptComposer.methods.send.call(context);expect(sent).toHaveLength(1);
  });
  it('rejects unsupported files and collective size/count overflow before upload',()=>{
    expect(()=>validateChatFiles([new File(['x'],'x.exe')])).toThrow('格式');
    expect(()=>validateChatFiles(Array.from({length:9},()=>new File(['x'],'x.md')))).toThrow('8');
    expect(()=>validateChatFiles([{name:'a.csv',size:16*1024*1024},{name:'b.csv',size:1}])).toThrow('16 MB');
  });
});
