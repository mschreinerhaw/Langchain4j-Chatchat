import { Folder, FileCode2, FileSpreadsheet } from '@lucide/vue';
export default { components: { Folder, FileCode2, FileSpreadsheet }, props: { nodes: Array, activeId: String, mappingIds: Array }, emits: ['folder-select', 'open', 'mapping', 'context'] };
