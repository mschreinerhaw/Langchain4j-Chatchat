import { ElMessage } from "element-plus";

export default {
  name: "ApiTestParameterDialog",
  data() {
    return {
      visible: false,
      title: "API 请求测试",
      fields: [],
      values: {},
      resolver: null,
      rejecter: null,
    };
  },
  methods: {
    open({ title, schema } = {}) {
      this.finishPending("cancel");
      this.title = title || "API 请求测试";
      this.fields = schemaFields(schema);
      this.values = Object.fromEntries(
        this.fields.map((field) => [field.name, initialValue(field)])
      );
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
      reject?.("cancel");
      if (typeof done === "function") done();
    },
    finishPending(reason) {
      if (this.rejecter) this.rejecter(reason);
      this.clearPending();
    },
    clearPending() {
      this.resolver = null;
      this.rejecter = null;
    },
  },
};

function schemaFields(rawSchema) {
  const schema = parseSchema(rawSchema);
  const required = new Set(Array.isArray(schema.required) ? schema.required : []);
  const properties =
    schema.properties && typeof schema.properties === "object" ? schema.properties : {};
  return Object.entries(properties).map(([name, rawDefinition]) => {
    const definition = rawDefinition && typeof rawDefinition === "object" ? rawDefinition : {};
    return {
      name,
      label: definition.title || name,
      description: definition.description || "",
      type: definition.type || inferType(definition.default),
      required: required.has(name),
      enumValues: Array.isArray(definition.enum) ? definition.enum : null,
      hasDefault: Object.prototype.hasOwnProperty.call(definition, "default"),
      defaultValue: definition.default,
      placeholder: Object.prototype.hasOwnProperty.call(definition, "default")
        ? `默认值：${displayValue(definition.default)}`
        : required.has(name)
          ? `请输入 ${definition.title || name}`
          : "选填",
    };
  });
}

function parseSchema(rawSchema) {
  if (!rawSchema) return {};
  if (typeof rawSchema === "object") return rawSchema;
  try {
    const parsed = JSON.parse(rawSchema);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function initialValue(field) {
  if (!field.hasDefault) return undefined;
  if (field.type === "object" || field.type === "array") {
    return JSON.stringify(field.defaultValue, null, 2);
  }
  return field.defaultValue;
}

function typedValue(field, value) {
  if (field.type === "object" || field.type === "array") {
    try {
      const parsed = typeof value === "string" ? JSON.parse(value) : value;
      if (field.type === "array" && !Array.isArray(parsed)) throw new Error("必须填写 JSON 数组");
      if (
        field.type === "object" &&
        (!parsed || Array.isArray(parsed) || typeof parsed !== "object")
      ) {
        throw new Error("必须填写 JSON 对象");
      }
      return parsed;
    } catch (error) {
      if (error.message.startsWith("必须填写")) throw error;
      throw new Error("JSON 格式不正确");
    }
  }
  return value;
}

function isEmpty(value) {
  return value === undefined || value === null || (typeof value === "string" && !value.trim());
}

function inferType(value) {
  if (Array.isArray(value)) return "array";
  if (value !== null && typeof value === "object") return "object";
  if (typeof value === "number") return "number";
  if (typeof value === "boolean") return "boolean";
  return "string";
}

function displayValue(value) {
  return value !== null && typeof value === "object" ? JSON.stringify(value) : String(value);
}
