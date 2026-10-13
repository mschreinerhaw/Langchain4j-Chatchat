import { computed, onBeforeUnmount, ref } from 'vue';
import SqlFileTreeNode from '../../../components/sql-studio/SqlFileTreeNode.vue';
import { sqlFileTree } from '../../utils/sqlStudioState';
import { Plus, RotateCw, Search, Folder, FolderPlus } from '@lucide/vue';

export default {
  components: { SqlFileTreeNode, Plus, RotateCw, Search, Folder, FolderPlus },
  props: { folders: {type:Array,default:()=>[]}, selectedFolder:String, files: Array, references: Array, referenceIds: Array, projectName: String, activeId: String, mappingIds: Array, busy: Boolean },
  emits: ['create-folder', 'folder-select', 'open', 'mapping', 'reference', 'reference-open', 'choose-library', 'create', 'refresh', 'versions', 'download', 'rename', 'delete'],
  setup(props, { emit }) {
    const path = ref(''), dragging = ref(false), contextFile = ref(null), contextX = ref(0), contextY = ref(0), contextOpen = ref(false);
    const renaming = ref(false), creating = ref(false), renameName = ref('');
    const query = ref('');
    const filteredFiles = computed(() => (props.files || []).filter(file => file.path.toLowerCase().includes(query.value.trim().toLowerCase())));
    const tree = computed(() => sqlFileTree(filteredFiles.value, props.folders.filter(folder => folder.toLowerCase().includes(query.value.trim().toLowerCase()))));
    const contextFolder = ref('');
    const filteredReferences = computed(() => (props.references || []).filter(file => file.path.toLowerCase().includes(query.value.trim().toLowerCase())));
    function newFile(event) { openContext({ event, file: null }); beginCreate(); }
    function create() { if (path.value.trim()) emit('create', path.value.trim()); path.value = ''; }
    function upload(event, kind) { emit('upload', { files: [...event.target.files], kind }); event.target.value = ''; }
    function dragLeave(event) { if (!event.currentTarget.contains(event.relatedTarget)) dragging.value = false; }
    function dropFiles(event) {
      dragging.value = false;
      const incoming = [...(event.dataTransfer?.files || [])];
      if (incoming.length) emit('upload', { files: incoming, kind: 'AUTO' });
    }
    function openContext({ event, file, folder }) {
      contextFolder.value = folder || file?.path.slice(0, file.path.lastIndexOf('/')) || props.selectedFolder || '';
      contextFile.value = file; contextOpen.value = true; renaming.value = false; creating.value = false;
      contextX.value = Math.min(event.clientX, window.innerWidth - 190);
      contextY.value = Math.min(event.clientY, window.innerHeight - 240);
      window.addEventListener('pointerdown', outsideContext, { once: true });
    }
    function outsideContext(event) { if (!event.target.closest('.sql-explorer-context')) closeContext(); }
    function closeContext() { contextFile.value = null; contextOpen.value = false; renaming.value = false; creating.value = false; }
    function beginCreate() {
      emit('create', contextFolder.value); closeContext();
    }
    function beginCreateFolder() { emit('create-folder', contextFolder.value); closeContext(); }
    function commitCreate() { if (path.value.trim()) emit('create', path.value.trim()); closeContext(); }
    function deleteCurrent() { if (contextFile.value) emit('delete', contextFile.value); closeContext(); }
    async function copyPath() { if (contextFile.value) await navigator.clipboard?.writeText(contextFile.value.path); closeContext(); }
    function beginRename() { renameName.value = contextFile.value.path.split('/').at(-1); renaming.value = true; }
    function commitRename() {
      if (!contextFile.value || !renameName.value.trim()) return;
      const file = contextFile.value;
      emit('rename', { file, path: file.path.slice(0, file.path.lastIndexOf('/') + 1) + renameName.value.trim() });
      closeContext();
    }
    onBeforeUnmount(() => window.removeEventListener('pointerdown', outsideContext));
    return { path, tree, query, filteredFiles, filteredReferences, newFile, dragging, contextFile, contextOpen, contextX, contextY, renaming, creating, renameName, create, upload, dragLeave, dropFiles, openContext, closeContext, copyPath, beginRename, commitRename, beginCreate, beginCreateFolder, commitCreate, deleteCurrent };
  }
};
