import { Layers3, ShieldCheck, Files } from '@lucide/vue';
import PublicationSchemaFields from '../../../components/publications/PublicationSchemaFields.vue';
import { publicationTemplates, publicationSchemaFields } from '../../utils/publicationDefinition.js';
export default {
  components: { Layers3, ShieldCheck, Files, PublicationSchemaFields },
  props: {
    definition: { type: Object, default: () => ({}) }, catalog: { type: Array, default: () => [] },
    catalogState: { type: String, default: 'idle' }, executionLabel: { type: String, default: '受控模板能力' },
    roleLabel: { type: String, default: '已配置角色' },
    inputSchema: { type: [String, Object], default: () => ({}) }, outputSchema: { type: [String, Object], default: () => ({}) },
  },
  emits: ['retry'],
  computed: { items() { return publicationTemplates(this.definition, this.catalog); } },
  methods: { hasParameters(schema) { return publicationSchemaFields(schema).length > 0; } },
};
