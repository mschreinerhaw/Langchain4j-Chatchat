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
      title="参数来自当前模板；有默认值的参数已自动填写。"
      type="info"
      :closable="false"
      show-icon
      class="parameter-alert"
    />
    <el-empty v-else description="该模板无需参数，可直接执行测试" :image-size="72" />

    <el-form v-if="fields.length" label-position="top" @submit.prevent>
      <el-form-item v-for="field in fields" :key="field.name">
        <template #label>
          <span>{{ field.label }}</span>
          <span v-if="field.required" class="required-mark"> *</span>
          <small v-if="field.description" class="parameter-description">{{
            field.description
          }}</small>
        </template>

        <el-checkbox v-if="field.nullable" v-model="nullValues[field.name]"
          >空值（NULL）</el-checkbox
        >
        <div :inert="field.nullable && nullValues[field.name]" class="w-100">
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
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" @click="submit">发送请求</el-button>
    </template>
  </el-dialog>
</template>

<script src="../scripts/components/ApiTestParameterDialog.js"></script>

<style scoped src="../styles/components/api-test-parameter-dialog.css"></style>
