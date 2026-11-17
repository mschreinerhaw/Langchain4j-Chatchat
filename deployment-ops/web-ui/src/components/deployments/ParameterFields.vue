<template>
  <div class="form-grid">
    <div
      v-for="(field, key) in fields"
      :key="key"
      :class="{ full: field.description?.length > 80 }"
      class="parameter-field"
    >
      <label :for="'parameter-' + key">
        {{ field.label || key }}
        <span v-if="field.required && !field.generate" style="color: var(--danger)">*</span>
      </label>
      <select
        v-if="field.enum"
        :id="'parameter-' + key"
        :value="modelValue[key]"
        :aria-label="field.label || key"
        @change="change(key, $event.target.value)"
      >
        <option v-for="choice in field.enum" :key="choice" :value="choice">{{ choice }}</option>
      </select>
      <input
        v-else-if="field.type === 'boolean'"
        :id="'parameter-' + key"
        type="checkbox"
        :checked="modelValue[key]"
        @change="change(key, $event.target.checked)"
      />
      <div v-else class="parameter-input">
        <input
          :id="'parameter-' + key"
          :value="modelValue[key]"
          :aria-label="field.label || key"
          :aria-describedby="'parameter-help-' + key"
          :type="
            field.secret && !visible[key]
              ? 'password'
              : field.type === 'integer'
              ? 'number'
              : 'text'
          "
          :required="field.required && !field.generate && (!field.secret || !retainSecrets)"
          :min="field.min"
          :max="field.max"
          :placeholder="
            field.secret && retainSecrets
              ? '留空沿用已有凭据'
              : field.generate
              ? '留空由执行器生成'
              : ''
          "
          @input="change(key, $event.target.value)"
        />
        <template v-if="field.secret">
          <button type="button" @click="generate(key)">生成口令</button>
          <button type="button" @click="visible[key] = !visible[key]">
            {{ visible[key] ? '隐藏' : '显示' }}
          </button>
        </template>
      </div>
      <small :id="'parameter-help-' + key" class="muted">
        {{ field.description || key
        }}{{ field.secret ? ' · 不会写入草稿，请妥善保管首次设置的口令' : '' }}
      </small>
    </div>
  </div>
</template>
<script setup>
  import { reactive } from 'vue';
  const props = defineProps({
      fields: { type: Object, default: () => ({}) },
      modelValue: { type: Object, default: () => ({}) },
      retainSecrets: Boolean,
    }),
    emit = defineEmits(['update:modelValue']),
    visible = reactive({});
  function change(key, value) {
    emit('update:modelValue', { ...props.modelValue, [key]: value });
  }
  function generate(key) {
    change(
      key,
      Array.from(crypto.getRandomValues(new Uint8Array(24)), (value) =>
        value.toString(16).padStart(2, '0')
      ).join('')
    );
    visible[key] = true;
  }
</script>
