<template>
  <div class="publication-schema-fields">
    <div v-if="rows.length" class="publication-schema-scroll">
      <table>
        <thead
          ><tr
            ><th>字段</th><th>内容类型</th><th>{{ output ? "返回规则" : "填写要求" }}</th
            ><th>说明</th></tr
          ></thead
        >
        <tbody>
          <tr v-for="field in rows" :key="field.key">
            <td :style="{ paddingLeft: `${12 + Math.min(field.depth, 3) * 14}px` }">
              <strong>{{ field.title }}</strong
              ><small v-if="field.title !== field.name || field.depth">{{ field.key }}</small>
            </td>
            <td
              ><span class="publication-value-type">{{ field.type }}</span></td
            >
            <td
              ><span :class="{ 'publication-field-required': field.required }">{{
                output
                  ? field.required
                    ? "固定返回"
                    : "按需返回"
                  : field.required
                    ? "必填"
                    : "选填"
              }}</span
              ><small v-if="field.nullable">允许空值</small></td
            >
            <td>
              <p v-if="field.description">{{ field.description }}</p>
              <span v-if="field.hasDefault" class="publication-field-default"
                >默认值：{{ field.defaultText }}</span
              >
              <small v-if="field.choices">可选值：{{ field.choices }}</small>
              <small v-if="field.bounds">{{ field.bounds }}</small>
              <small v-if="field.recursive">关联字段由来源模板定义。</small>
              <span
                v-if="
                  !field.description &&
                  !field.hasDefault &&
                  !field.choices &&
                  !field.bounds &&
                  !field.recursive
                "
                >—</span
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="publication-definition-empty">{{ emptyLabel }}</p>
  </div>
</template>
<script src="../../js/components/publications/PublicationSchemaFields.js"></script>
