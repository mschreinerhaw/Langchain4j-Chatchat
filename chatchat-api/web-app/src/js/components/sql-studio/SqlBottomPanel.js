import AppNotice from '../../../components/AppNotice.vue';
import {computed} from 'vue';
export default {
  components: { AppNotice },
  props: { tab: String, problemCount: Number, error: String, message: String, result: Object, generations: Array },
  emits: ['tab', 'close', 'generation'],
  setup(props) {
    const structureReview=computed(()=>props.result?.metadataExploration?.codingExploration?.structureReview);
    const warningLabel=code=>({JOIN_RELATION_UNCONFIRMED:'表关联尚未确认',JOIN_FANOUT_AGGREGATION_RISK:'汇总可能重复计数',MISSING_PARTITION_FILTER:'需要确认分区过滤范围'}[code]||'结构需要复核');
    return {structureReview,warningLabel,tabs: [
      { id: 'problems', label: 'Problems' },
      { id: 'output', label: 'Output' },
      { id: 'tasks', label: 'Tasks' },
      { id: 'validation', label: 'Validation' },
      { id:'diagnostics',label:'运行诊断' }
    ] };
  }
};
