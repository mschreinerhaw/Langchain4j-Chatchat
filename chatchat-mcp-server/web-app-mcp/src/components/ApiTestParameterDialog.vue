<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="620px"
    destroy-on-close
    :close-on-click-modal="false"
    :before-close="cancel"
  >
    <el-alert
      v-if="fields.length"
      title="参数来自已注册的 API 参数契约；有默认值的参数已自动填写。"
      type="info"
      :closable="false"
      show-icon
      class="parameter-alert"
    />
    <el-empty v-else description="该 API 没有声明请求参数，可直接执行测试" :image-size="72" />

    <el-form v-if="fields.length" label-position="top" @submit.prevent>
      <el-form-item v-for="field in fields" :key="field.name">
        <template #label>
          <span>{{ field.label }}</span>
          <span v-if="field.required" class="required-mark"> *</span>
          <small v-if="field.description" class="parameter-description">{{ field.description }}</small>
        </template>

        <el-select
          v-if="field.enumValues"
          v-model="values[field.name]"
          class="w-100"
          clearable
          :placeholder="field.placeholder"
        >
          <el-option
            v-for="option in field.enumValues"
            :key="String(option)"
            :label="String(option)"
            :value="option"
          />
        </el-select>
        <el-select
          v-else-if="field.type === 'boolean'"
          v-model="values[field.name]"
          class="w-100"
          clearable
          :placeholder="field.placeholder"
        >
          <el-option label="true" :value="true" />
          <el-option label="false" :value="false" />
        </el-select>
        <el-input-number
          v-else-if="field.type === 'integer' || field.type === 'number'"
          v-model="values[field.name]"
          class="w-100"
          controls-position="right"
          :step="field.type === 'integer' ? 1 : 0.1"
          :placeholder="field.placeholder"
        />
        <el-input
          v-else-if="field.type === 'object' || field.type === 'array'"
          v-model="values[field.name]"
          type="textarea"
          :rows="4"
          :placeholder="field.placeholder"
        />
        <el-input
          v-else
          v-model="values[field.name]"
          clearable
          :placeholder="field.placeholder"
          @keyup.enter="submit"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" @click="submit">发送请求</el-button>
    </template>
  </el-dialog>
</template>

<script>
import { ElMessage } from 'element-plus';

export default {
  name: 'ApiTestParameterDialog',
  data() {
    return {
      visible: false,
      title: 'API 请求测试',
      fields: [],
      values: {},
      resolver: null,
      rejecter: null
    };
  },
  methods: {
    open({ title, schema } = {}) {
      this.finishPending('cancel');
      this.title = title || 'API 请求测试';
      this.fields = schemaFields(schema);
      this.values = Object.fromEntries(this.fields.map(field => [field.name, initialValue(field)]));
      this.visible = true;
      return new Promise((resolve, reject) => {
        this.resolver = resolve;
        this.rejecter = reject;
      });
    },
    submit() {
      const payload = {};
      for (const field of this.fields) {
        const value = this.values[field.name];
        if (isEmpty(value)) {
          if (field.required) {
            ElMessage.warning(`请填写必填参数：${field.label}`);
            return;
          }
          continue;
        }
        try {
          payload[field.name] = typedValue(field, value);
        } catch (error) {
          ElMessage.warning(`${field.label}：${error.message}`);
          return;
        }
      }
      const resolve = this.resolver;
      this.clearPending();
      this.visible = false;
      resolve?.(payload);
    },
    cancel(done) {
      const reject = this.rejecter;
      this.clearPending();
      this.visible = false;
      reject?.('cancel');
      if (typeof done === 'function') done();
    },
    finishPending(reason) {
      if (this.rejecter) this.rejecter(reason);
      this.clearPending();
    },
    clearPending() {
      this.resolver = null;
      this.rejecter = null;
    }
  }
};

function schemaFields(rawSchema) {
  const schema = parseSchema(rawSchema);
  const required = new Set(Array.isArray(schema.required) ? schema.required : []);
  const properties = schema.properties && typeof schema.properties === 'object' ? schema.properties : {};
  return Object.entries(properties).map(([name, rawDefinition]) => {
    const definition = rawDefinition && typeof rawDefinition === 'object' ? rawDefinition : {};
    return {
      name,
      label: definition.title || name,
      description: definition.description || '',
      type: definition.type || inferType(definition.default),
      required: required.has(name),
      enumValues: Array.isArray(definition.enum) ? definition.enum : null,
      hasDefault: Object.prototype.hasOwnProperty.call(definition, 'default'),
      defaultValue: definition.default,
      placeholder: Object.prototype.hasOwnProperty.call(definition, 'default')
        ? `默认值：${displayValue(definition.default)}`
        : (required.has(name) ? `请输入 ${definition.title || name}` : '选填')
    };
  });
}

function parseSchema(rawSchema) {
  if (!rawSchema) return {};
  if (typeof rawSchema === 'object') return rawSchema;
  try {
    const parsed = JSON.parse(rawSchema);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function initialValue(field) {
  if (!field.hasDefault) return undefined;
  if (field.type === 'object' || field.type === 'array') {
    return JSON.stringify(field.defaultValue, null, 2);
  }
  return field.defaultValue;
}

function typedValue(field, value) {
  if (field.type === 'object' || field.type === 'array') {
    try {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      if (field.type === 'array' && !Array.isArray(parsed)) throw new Error('必须填写 JSON 数组');
      if (field.type === 'object' && (!parsed || Array.isArray(parsed) || typeof parsed !== 'object')) {
        throw new Error('必须填写 JSON 对象');
      }
      return parsed;
    } catch (error) {
      if (error.message.startsWith('必须填写')) throw error;
      throw new Error('JSON 格式不正确');
    }
  }
  return value;
}

function isEmpty(value) {
  return value === undefined || value === null || (typeof value === 'string' && !value.trim());
}

function inferType(value) {
  if (Array.isArray(value)) return 'array';
  if (value !== null && typeof value === 'object') return 'object';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  return 'string';
}

function displayValue(value) {
  return value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value);
}
</script>

<style scoped>
.parameter-alert {
  margin-bottom: 18px;
}

.required-mark {
  color: var(--el-color-danger);
}

.parameter-description {
  display: block;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
}
</style>
