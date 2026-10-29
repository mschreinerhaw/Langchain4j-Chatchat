import {describe,it,expect,vi} from 'vitest';
import {observeSqlEditorLayout} from './sqlEditorLayout';
describe('SQL editor layout scheduling',()=>{
  it('keeps the viewport at bottom when horizontal scrollbar changes content height by 12px',()=>{
    let frame,height=612,top=312;const env={ResizeObserver:class{observe(){}disconnect(){}},requestAnimationFrame:callback=>{frame=callback;return 1;},cancelAnimationFrame(){}};
    const node={clientWidth:200,clientHeight:300},editor={layout:({width})=>{height=width===200?612:600;},saveViewState:()=>({}),restoreViewState(){},getScrollTop:()=>top,getScrollHeight:()=>height,setScrollTop:value=>{top=value;}};
    const monitor=observeSqlEditorLayout([[node,editor]],env);frame();
    node.clientWidth=400;monitor.schedule();frame();expect(top).toBe(300);
    node.clientWidth=200;monitor.schedule();frame();expect(top).toBe(312);monitor.dispose();
  });
  it('restores the editor view anchor after a width-only reflow, without changing the model',()=>{
    let frame;const env={ResizeObserver:class{observe(){}disconnect(){}},requestAnimationFrame:callback=>{frame=callback;return 1;},cancelAnimationFrame(){}};
    let lineTop=740;const node={clientWidth:400,clientHeight:300},view={firstPosition:{lineNumber:39,column:1}},editor={layout:vi.fn(({width})=>{if(width===500)lineTop=760;}),saveViewState:vi.fn(()=>view),restoreViewState:vi.fn(),getVisibleRanges:()=>[{startLineNumber:39}],getScrollTop:()=>745,getTopForLineNumber:()=>lineTop,setScrollTop:vi.fn()};
    const monitor=observeSqlEditorLayout([[node,editor]],env);frame();expect(editor.restoreViewState).not.toHaveBeenCalled();
    node.clientWidth=500;monitor.schedule();frame();expect(editor.restoreViewState).toHaveBeenCalledWith(view);
    expect(editor.setScrollTop).toHaveBeenCalledWith(765);
    expect(editor.layout.mock.invocationCallOrder.at(-1)).toBeLessThan(editor.restoreViewState.mock.invocationCallOrder[0]);monitor.dispose();
  });
  it('coalesces resize bursts, skips unchanged and hidden sizes and disposes the observer',()=>{
    let notify,frame;const disconnect=vi.fn(),cancel=vi.fn();
    const env={ResizeObserver:class{constructor(callback){notify=callback;}observe(){}disconnect(){disconnect();}},
      requestAnimationFrame:vi.fn(callback=>{frame=callback;return 1;}),cancelAnimationFrame:cancel};
    const node={clientWidth:400,clientHeight:300},editor={layout:vi.fn()},monitor=observeSqlEditorLayout([[node,editor]],env);
    notify();notify();expect(env.requestAnimationFrame).toHaveBeenCalledTimes(1);frame();
    expect(editor.layout).toHaveBeenCalledWith({width:400,height:300});
    notify();frame();expect(editor.layout).toHaveBeenCalledTimes(1);
    node.clientWidth=0;notify();frame();expect(editor.layout).toHaveBeenCalledTimes(1);
    node.clientWidth=500;notify();frame();expect(editor.layout).toHaveBeenCalledTimes(2);
    notify();monitor.dispose();expect(cancel).toHaveBeenCalledWith(1);expect(disconnect).toHaveBeenCalledOnce();
    frame();expect(editor.layout).toHaveBeenCalledTimes(2);
  });
});
