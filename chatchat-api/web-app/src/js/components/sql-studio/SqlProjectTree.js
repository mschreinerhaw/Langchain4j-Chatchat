import { computed, ref } from 'vue';
import SqlFileTreeNode from '../../../components/sql-studio/SqlFileTreeNode.vue';
import { sqlFileTree } from '../../utils/sqlStudioState';

export default {
  components: { SqlFileTreeNode },
  props: { files: Array, activeId: String, mappingIds: Array, busy: Boolean },
  emits: ['open', 'mapping', 'upload', 'create', 'refresh'],
  setup(props, { emit: emitEvent }) {
const emit = emitEvent;


const path = ref(''); const tree = computed(() => sqlFileTree(props.files));
function create() { emit('create', path.value.trim()); path.value = ''; }
function upload(event, kind) { emit('upload', { files: [...event.target.files], kind }); event.target.value = ''; }
  return { path, tree, create, upload };

  }
};
