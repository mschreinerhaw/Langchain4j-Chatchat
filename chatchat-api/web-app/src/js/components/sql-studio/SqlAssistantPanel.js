import { computed } from 'vue';
import { terminalSqlTask } from '../../utils/sqlStudioState';
export default {
  props: { mappingCount: Number, references: Array, referenceIds: Array, models: Array, agents: Array,
  agentId: String, modelName: String, prompt: String, busy: Boolean, generations: Array, generationId: String, result: Object },
  emits: ['reference','agent','model','prompt','generate','generation','resume','cancel','preview','apply'],
  setup(props, { emit: emitEvent }) {


const terminal = computed(() => terminalSqlTask(props.result?.task?.status));
  return { terminal };

  }
};
