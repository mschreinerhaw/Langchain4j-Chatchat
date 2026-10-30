import {describe,it,expect} from 'vitest';
import {explorationFigure} from './explorationPresentation';
describe('Exploration chart presentation preserves real series',()=>{
  const original={data:[{type:'bar',name:'股票',x:['2024-01','2024-02'],y:[3214120.25,3402880.5]}],layout:{yaxis:{title:{text:'元'}},barmode:'stack'}};
  it('switches bar to line and back without changing measured values, units or original',()=>{
    const snapshot=JSON.stringify(original),line=explorationFigure(original,'line');
    expect(line.data[0].type).toBe('scatter');expect(line.data[0].mode).toBe('lines+markers');
    expect(line.data[0].y).toEqual(original.data[0].y);expect(line.data[0].x).toEqual(original.data[0].x);expect(line.layout).toEqual(original.layout);
    expect(explorationFigure(line,'bar').data[0].y).toEqual(original.data[0].y);expect(JSON.stringify(original)).toBe(snapshot);
  });
  it('rejects unsupported charts and unsafe historical resources',()=>{
    expect(()=>explorationFigure({data:[{type:'pie',labels:['A'],values:[10]}]},'line')).toThrow('X/Y');
    expect(()=>explorationFigure({data:[{type:'bar',orientation:'h',x:[10],y:['A']}]},'line')).toThrow('X/Y');
    expect(()=>explorationFigure({data:[{type:'bar',x:['A'],y:[10],url:'https://evil.invalid'}]},'line')).toThrow();
  });
});

import {explorationChartTypes,explorationChartStyle,explorationChartTable} from './explorationPresentation';
it('permits pie only for one nonnegative unique-category series',()=>{
 const figure={data:[{type:'bar',name:'资产',x:['A','B'],y:[1,3]}],layout:{}};expect(explorationChartTypes(figure)).toContain('pie');const pie=explorationFigure(figure,'pie');expect(pie.data[0].values).toEqual([1,3]);expect(explorationFigure(pie,'bar').data[0].x).toEqual(['A','B']);expect(explorationChartTypes({...figure,data:[...figure.data,...figure.data]})).not.toContain('pie');expect(explorationChartTypes({...figure,data:[{...figure.data[0],y:[-1,3]}]})).not.toContain('pie');expect(explorationChartTypes({...figure,frames:[{data:[]}]})).toEqual([]);
});
it('formats dates and extracts exact tabular values without mutating source',()=>{
 const figure={data:[{type:'bar',name:'余额',x:[20260803,20260804],y:[12345,18765]},{type:'bar',name:'证券',x:[20260803,20260804],y:[412568,1073542]}],layout:{title:'重复标题',xaxis:{title:{text:'业务日期'}}}},copy=JSON.stringify(figure);const styled=explorationChartStyle(figure);expect(styled.layout.xaxis.ticktext).toEqual(['2026-08-03','2026-08-04']);expect(styled.data).toEqual(figure.data);expect(styled.layout.title).toBeUndefined();expect(explorationChartTable(figure).rows).toEqual([[20260803,12345,412568],[20260804,18765,1073542]]);expect(JSON.stringify(figure)).toBe(copy);
});

it('fits fixed-size archived plots to the workspace and keeps ISO dates readable',()=>{const original={data:[{type:'scatter',x:['2026-08-03','2026-08-04'],y:[1,2]}],layout:{width:700,height:400,xaxis:{title:'业务日期'}}};const styled=explorationChartStyle(original);expect(styled.layout.width).toBeUndefined();expect(styled.layout.height).toBeUndefined();expect(styled.layout.autosize).toBe(true);expect(styled.layout.xaxis.ticktext).toEqual(original.data[0].x);expect(original.layout.width).toBe(700);});
