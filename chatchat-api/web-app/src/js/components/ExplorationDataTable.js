import {ref,computed,watch} from 'vue';
import {sortedTableRows} from '../utils/explorationWorkspace';
export default {
  name:'ExplorationDataTable',props:{table:{type:Object,required:true}},
  setup(props){
    const page=ref(1),pageSize=ref(10),sortColumn=ref(null),sortDirection=ref('asc');
    const sorted=computed(()=>sortedTableRows(props.table.rows,sortColumn.value,sortDirection.value));
    const pageCount=computed(()=>Math.max(1,Math.ceil(sorted.value.length/pageSize.value)));
    const pageRows=computed(()=>sorted.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value));
    watch(()=>props.table,()=>{page.value=1;sortColumn.value=null;});
    return {page,pageSize,pageCount,pageRows,sortColumn,sortDirection,sort:index=>{sortDirection.value=sortColumn.value===index&&sortDirection.value==='asc'?'desc':'asc';sortColumn.value=index;page.value=1;}};
  }
};
