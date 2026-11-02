import { publicationSchemaFields } from '../../utils/publicationDefinition.js';
export default {
  props: { schema: { type: [String, Object], default: () => ({}) }, output: { type: Boolean, default: false }, emptyLabel: { type: String, default: '此版本未声明固定字段。' } },
  computed: { rows() { return publicationSchemaFields(this.schema); } },
};
