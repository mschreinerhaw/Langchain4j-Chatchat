import {ref,watch} from 'vue';
import {sqlMetadataOperation} from '../../../services/sqlStudioApi';
export default {
  props:{projectId:String,evidence:Object},
  setup(props){
    const loading=ref(false),error=ref(''),detail=ref(null);let epoch=0;
    watch(()=>[props.projectId,props.evidence],()=>{epoch++;detail.value=null;error.value='';loading.value=false;});
    async function inspect(table){const turn=++epoch;loading.value=true;error.value='';detail.value=null;
      try{const args={operation:'DESCRIBE',tableName:table.tableName,expectedSnapshotId:table.expectedSnapshotId};for(const key of ['database','schema'])if(table[key]!=null)args[key]=table[key];
        const result=await sqlMetadataOperation(props.projectId,table.discoveryId,table.bindingId,args);if(turn!==epoch)return;
        const metadata=result?.metadata;if(metadata?.status!=='SUCCEEDED'||metadata.snapshotId!==table.expectedSnapshotId||metadata.items?.length!==1)throw new Error('结构快照已变化或当前字段不可用，请重新探索。');detail.value=metadata.items[0];
      }catch(failure){if(turn===epoch)error.value=failure.message||'无法读取当前授权字段';}finally{if(turn===epoch)loading.value=false;}
    }
    return {loading,error,detail,inspect};
  }
};
