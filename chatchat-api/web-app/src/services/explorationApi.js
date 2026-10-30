import {apiRequest} from './api';
const base='/data-science/explorations';
const part=value=>encodeURIComponent(value);
const write=(path,method,body)=>apiRequest(path,{method,body:JSON.stringify(body)});
export const listExplorations=()=>apiRequest(base);
export const explorationAgents=()=>apiRequest(`${base}/agents`);
export const explorationNodeStatus=(id,nodeId)=>apiRequest(`${base}/${part(id)}/nodes/${part(nodeId)}/status`);
export const createExploration=(title,source={})=>write(base,'POST',{title,...source});
export const getExploration=id=>apiRequest(`${base}/${part(id)}`);
export const prepareExploration=(id,question)=>write(`${base}/${part(id)}/nodes`,'POST',question);
export const executeExploration=(id,nodeId)=>write(`${base}/${part(id)}/nodes/${part(nodeId)}/execute`,'POST',{});
export const saveExplorationNode=(id,nodeId,body)=>write(`${base}/${part(id)}/nodes/${part(nodeId)}`,'PUT',body);
export async function explorationDatasets(){
  const files=[];
  for(let offset=0;offset<10000;offset+=100){
    const page=await apiRequest(`/interactions/user-assets?personalOnly=false&limit=100&offset=${offset}`);
    files.push(...page.filter(file=>file.state==='AVAILABLE' && (
      file.kind==='DOCUMENT' && /\.(csv|xlsx|xls|json|jsonl|parquet)$/i.test(file.fileName)
      || file.kind==='DATASET' && /\.(csv|xlsx|xls|json|jsonl|parquet)$/i.test(file.fileName)
        && !/^(?:manifest\.json|schema\.json|rows\.jsonl|part-\d+\.parquet|original-\d+\..+)$/i.test(file.fileName)
      || file.kind==='ARTIFACT' && /\.(csv|xlsx|xls|parquet)$/i.test(file.fileName)
    )));
    if(page.length<100)return files;
  }
  throw new Error('数据文件数量超过本阶段浏览上限，请在用户空间筛选数据');
}
