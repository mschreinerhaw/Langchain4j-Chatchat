// One observer and one animation frame per editor pane; hidden/unchanged sizes do no work.
export function observeSqlEditorLayout(entries, environment=globalThis) {
  let frame=null, disposed=false;
  const sizes=new Map();
  function update(){
    frame=null;if(disposed)return;
    for(const [node,editor] of entries){
      const width=node?.clientWidth || 0,height=node?.clientHeight || 0;
      if(!width || !height){sizes.delete(node);continue;}
      const previous=sizes.get(node);
      if(previous?.width===width && previous?.height===height)continue;
      // Monaco can move the first visible model position while recalculating wrapped
      // lines. Its own view state preserves that anchor, selection and horizontal scroll.
      const view=previous && previous.width!==width && previous.height===height ? editor.saveViewState?.() : null;
      const bottom=view && typeof editor.getScrollHeight==='function' && editor.getScrollTop()+previous.height>=editor.getScrollHeight()-1;
      const line=view?editor.getVisibleRanges?.()?.[0]?.startLineNumber:null;
      const offset=line!=null?editor.getScrollTop()-editor.getTopForLineNumber(line):null;
      sizes.set(node,{width,height});editor.layout({width,height});
      if(view)editor.restoreViewState?.(view);
      if(bottom)editor.setScrollTop(Math.max(0,editor.getScrollHeight()-height));
      else if(line!=null)editor.setScrollTop(editor.getTopForLineNumber(line)+offset);
    }
  }
  function schedule(){if(!disposed && frame===null)frame=environment.requestAnimationFrame(update);}
  const observer=new environment.ResizeObserver(schedule);
  entries.forEach(([node])=>{if(node)observer.observe(node);});schedule();
  return {schedule,dispose(){disposed=true;observer.disconnect();if(frame!==null)environment.cancelAnimationFrame(frame);frame=null;}};
}
