import { ref, computed, watch, nextTick } from "vue";
import { X } from "@lucide/vue";
import {
  sqlTemplateSources,
  sqlTemplateCategories,
  sqlTemplateHistory,
  writeSqlTemplate,
  actSqlTemplate,
} from "../../../services/sqlStudioApi";
// getRandomValues also works on existing HTTP intranet deployments where randomUUID is unavailable.
function operationKey() {
  if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID();
  return Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (value) =>
    value.toString(16).padStart(2, "0")
  ).join("");
}
export default {
  components: { X },
  props: { projectId: { type: String, required: true }, file: { type: Object, required: true } },
  emits: ["close"],
  setup(props) {
    const sources = ref([]),
      categories = ref([]),
      history = ref([]),
      selectedId = ref(""),
      busy = ref(false),
      error = ref(""),
      status = ref(""),
      reviewed = ref(false),
      sample = ref("{}");
    const testDialog = ref(null),
      testOpen = ref(false),
      testError = ref(""),
      testFields = ref([]);
    let testTrigger;
    function parameterDefinition(schema, value, seen = new Set()) {
      if (!value?.$ref) return value || {};
      if (!value.$ref.startsWith("#/") || seen.has(value.$ref))
        throw new Error("此参数定义需要在“参数定义与 SQL 说明”中检查引用。");
      seen.add(value.$ref);
      const resolved = value.$ref
        .slice(2)
        .split("/")
        .reduce((v, key) => v?.[key.replaceAll("~1", "/").replaceAll("~0", "~")], schema);
      if (!resolved) throw new Error("参数定义引用不存在，请先修正参数定义。");
      return {
        ...parameterDefinition(schema, resolved, seen),
        ...Object.fromEntries(Object.entries(value).filter(([key]) => key !== "$ref")),
      };
    }
    async function openTest(event) {
      testTrigger = event?.currentTarget;
      error.value = "";
      try {
        const schema = JSON.parse(form.value.schema),
          previous = JSON.parse(sample.value);
        testFields.value = Object.entries(schema.properties || {}).map(([name, raw]) => {
          const definition = parameterDefinition(schema, raw);
          const types = Array.isArray(definition.type)
            ? definition.type
            : [definition.type || "string"];
          const type = types.find((t) => t !== "null") || "null";
          const required = (schema.required || []).includes(name);
          const hasValue = Object.hasOwn(previous, name) || Object.hasOwn(definition, "default");
          const value = Object.hasOwn(previous, name) ? previous[name] : definition.default;
          return {
            name,
            definition,
            type,
            required,
            nullable: types.includes("null"),
            mode: hasValue
              ? value === null
                ? "null"
                : "value"
              : required
                ? type === "null"
                  ? "null"
                  : "value"
                : "omit",
            value:
              value === undefined || value === null
                ? ""
                : ["object", "array"].includes(type)
                  ? JSON.stringify(value, null, 2)
                  : String(value),
            inputType:
              type === "integer" || type === "number"
                ? "number"
                : definition.format === "date"
                  ? "date"
                  : "text",
          };
        });
        testError.value = "";
        testOpen.value = true;
        await nextTick();
        testDialog.value
          ?.querySelector("input:not(:disabled), select:not(:disabled), button")
          ?.focus();
      } catch (e) {
        error.value = e.message;
      }
    }
    function closeTest() {
      if (busy.value) return;
      testOpen.value = false;
      nextTick(() => testTrigger?.focus());
    }
    function trapTestFocus(event) {
      const items = [
        ...testDialog.value.querySelectorAll(
          "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)"
        ),
      ];
      const first = items[0],
        last = items.at(-1);
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
    async function submitTest() {
      if (busy.value) return;
      testError.value = "";
      try {
        const values = {};
        for (const field of testFields.value) {
          if (field.mode === "omit") continue;
          if (field.mode === "null") {
            if (!field.nullable) throw new Error(`${field.name} 不允许空值。`);
            values[field.name] = null;
            continue;
          }
          let value = field.value;
          if (field.required && value === "") throw new Error(`请填写 ${field.name}。`);
          if (["integer", "number"].includes(field.type)) {
            if (value === "" || !Number.isFinite(Number(value)))
              throw new Error(`${field.name} 需要填写有效数字。`);
            value = Number(value);
            if (field.type === "integer" && !Number.isSafeInteger(value))
              throw new Error(`${field.name} 需要填写安全范围内的整数。`);
          } else if (field.type === "boolean") {
            if (!["true", "false"].includes(value)) throw new Error(`请选择 ${field.name}。`);
            value = value === "true";
          } else if (["object", "array"].includes(field.type)) value = JSON.parse(value);
          values[field.name] = value;
        }
        sample.value = JSON.stringify(values, null, 2);
        await perform("test");
        if (error.value) {
          testError.value = error.value;
          return;
        }
        closeTest();
      } catch (e) {
        testError.value = e.message;
      }
    }
    const form = ref({
      title: "",
      description: "",
      category: "",
      datasourceId: "",
      schema: '{"type":"object","properties":{},"additionalProperties":false}',
    });
    let epoch = 0,
      operationId = operationKey();
    const selected = computed(() => history.value.find((row) => row.id === selectedId.value)),
      published = computed(() => selected.value?.state === "PUBLISHED");
    const dirty = computed(
        () => props.file.draft !== undefined && props.file.draft !== props.file.content
      ),
      sourceCurrent = computed(() => selected.value?.source?.fileVersion === props.file.version);
    const fields = (row) => ({
      title: row.title,
      description: row.description || "",
      category: row.category,
      datasourceId: row.datasourceId,
      schema: JSON.stringify(row.parameterSchema, null, 2),
    });
    const changed = computed(
      () => selected.value && JSON.stringify(form.value) !== JSON.stringify(fields(selected.value))
    );
    const sampleCurrent = computed(() => {
      try {
        return (
          JSON.stringify(JSON.parse(sample.value)) ===
          JSON.stringify(selected.value?.testEvidence?.parameters)
        );
      } catch {
        return false;
      }
    });
    const legacyCategory = computed(
      () => !!form.value.category && !categories.value.some((c) => c.code === form.value.category)
    );
    const canPublish = computed(() => {
      if (
        dirty.value ||
        !reviewed.value ||
        !form.value.title.trim() ||
        !form.value.datasourceId ||
        !categories.value.some((c) => c.code === form.value.category)
      )
        return false;
      if (published.value && (!sourceCurrent.value || (selected.value.active && !changed.value)))
        return false;
      try {
        JSON.parse(form.value.schema);
        const values = JSON.parse(sample.value);
        return !!values && !Array.isArray(values) && typeof values === "object";
      } catch {
        return false;
      }
    });
    const publicationHint = computed(() =>
      dirty.value
        ? "请先保存 SQL 文件。"
        : legacyCategory.value
          ? "请选择当前有效的业务分类。"
          : published.value && !sourceCurrent.value
            ? "SQL 已修改，请先创建新修订。"
            : published.value && selected.value.active && !changed.value
              ? "当前信息已发布；修改名称、描述或分类后可发布更新。"
              : "发布会先验证查询，成功后同步更新。"
    );
    function choose() {
      testOpen.value = false;
      reviewed.value = false;
      if (selected.value) {
        form.value = fields(selected.value);
        sample.value = JSON.stringify(selected.value.testEvidence?.parameters || {}, null, 2);
      }
    }
    watch(selectedId, choose);
    watch([form, sample], () => (reviewed.value = false), { deep: true });
    async function load() {
      const current = ++epoch;
      busy.value = true;
      error.value = "";
      try {
        const values = await Promise.all([
          sqlTemplateSources(),
          sqlTemplateHistory(props.projectId, props.file.id),
          sqlTemplateCategories(),
        ]);
        if (current !== epoch) return;
        sources.value = values[0];
        history.value = values[1];
        categories.value = values[2];
        selectedId.value = history.value[0]?.id || "";
        if (selected.value) choose();
        else {
          form.value.title = props.file.path.split("/").at(-1);
          form.value.category = categories.value[0]?.code || "";
          form.value.datasourceId = sources.value[0]?.id || "";
          const names = [
            ...new Set(
              [...props.file.content.matchAll(/\{\{\s*([A-Za-z0-9_.-]+)\s*}}/g)].map(
                (match) => match[1]
              )
            ),
          ];
          form.value.schema = JSON.stringify(
            {
              type: "object",
              properties: Object.fromEntries(names.map((name) => [name, { type: "string" }])),
              required: names,
              additionalProperties: false,
            },
            null,
            2
          );
        }
      } catch (e) {
        if (current === epoch) error.value = e.message;
      } finally {
        if (current === epoch) busy.value = false;
      }
    }
    function body() {
      return {
        ...form.value,
        parameterSchema: JSON.parse(form.value.schema),
        databaseType: sources.value.find((row) => row.id === form.value.datasourceId)?.databaseType,
        expectedVersion: selected.value?.expectedVersion,
        expectedSourceVersion: props.file.version,
        parameters: JSON.parse(sample.value),
        expectedActiveReleaseId: history.value.find((row) => row.active)?.publication?.id || "",
      };
    }
    async function run(action) {
      if (busy.value) return;
      busy.value = true;
      error.value = "";
      try {
        await action();
      } catch (e) {
        error.value = e.message;
      } finally {
        busy.value = false;
      }
    }
    function accept(row) {
      const index = history.value.findIndex((item) => item.id === row.id);
      if (index < 0) history.value.unshift(row);
      else history.value.splice(index, 1, row);
      selectedId.value = row.id;
      choose();
    }
    async function createDraftRow() {
      const input = body();
      if (published.value) {
        const names = [
          ...new Set(
            [...props.file.content.matchAll(/\{\{\s*([A-Za-z0-9_.-]+)\s*}}/g)].map(
              (match) => match[1]
            )
          ),
        ];
        const previous = new Set(
          [...selected.value.sqlTemplate.matchAll(/\{\{\s*([A-Za-z0-9_.-]+)\s*}}/g)].map(
            (match) => match[1]
          )
        );
        if (names.length !== previous.size || names.some((name) => !previous.has(name)))
          input.parameterSchema = {
            ...input.parameterSchema,
            type: "object",
            properties: Object.fromEntries(
              names.map((name) => [
                name,
                input.parameterSchema.properties?.[name] || { type: "string" },
              ])
            ),
            required: names,
            additionalProperties: false,
          };
      }
      accept(
        await writeSqlTemplate(props.projectId, props.file.id, null, {
          ...input,
          operationId,
          groupId: selected.value?.groupId,
        })
      );
      operationId = operationKey();
      status.value = "草稿已保存，尚未执行 SQL。";
    }
    async function createDraft() {
      await run(createDraftRow);
    }
    async function saveDraft() {
      await run(async () => {
        accept(await writeSqlTemplate(props.projectId, props.file.id, selectedId.value, body()));
        status.value = "草稿已更新，原测试证据已失效。";
      });
    }
    async function perform(action) {
      await run(async () => {
        status.value =
          action === "test"
            ? "正在执行真实 SQL 查询…"
            : action === "rollback"
              ? "正在检查权限、重新测试并恢复历史修订…"
              : "正在校验并发布 MCP 能力…";
        const row = await actSqlTemplate(props.projectId, props.file.id, selectedId.value, action, {
          ...body(),
          testId: operationKey(),
        });
        accept(row);
        if (action !== "test") {
          history.value = await sqlTemplateHistory(props.projectId, props.file.id);
          choose();
        }
        status.value =
          action === "test"
            ? row.testEvidence?.success
              ? "真实 SQL 测试成功。"
              : "真实 SQL 测试失败。"
            : action === "rollback" && !row.active
              ? "回滚测试失败，线上版本未变更。"
              : "发布状态已由服务端确认。";
      });
    }
    async function publishTemplate() {
      if (!canPublish.value) return;
      await run(async () => {
        status.value = "正在校验并同步发布…";
        if (!selected.value) await createDraftRow();
        if (published.value) {
          const action = changed.value ? "metadata" : "rollback";
          accept(
            await actSqlTemplate(props.projectId, props.file.id, selectedId.value, action, {
              ...body(),
              testId: operationKey(),
            })
          );
        } else {
          if (changed.value || !sourceCurrent.value)
            accept(
              await writeSqlTemplate(props.projectId, props.file.id, selectedId.value, body())
            );
          if (!selected.value.testEvidence?.success || !sampleCurrent.value) {
            accept(
              await actSqlTemplate(props.projectId, props.file.id, selectedId.value, "test", {
                ...body(),
                testId: operationKey(),
              })
            );
            if (!selected.value.testEvidence?.success) {
              status.value = "真实 SQL 测试失败，尚未发布。";
              return;
            }
          }
          accept(
            await actSqlTemplate(props.projectId, props.file.id, selectedId.value, "publish", {
              ...body(),
              testId: operationKey(),
            })
          );
        }
        history.value = await sqlTemplateHistory(props.projectId, props.file.id);
        choose();
        status.value = selected.value?.active
          ? "模板已发布，MCP Server 信息已同步。"
          : "查询验证未通过，线上版本未变更。";
      });
    }
    watch(
      () => [props.projectId, props.file.id],
      () => {
        selectedId.value = "";
        history.value = [];
        void load();
      },
      { immediate: true }
    );

    return {
      sources,
      categories,
      legacyCategory,
      publicationHint,
      publishTemplate,
      history,
      selectedId,
      busy,
      error,
      status,
      reviewed,
      sample,
      form,
      selected,
      published,
      dirty,
      sourceCurrent,
      changed,
      canPublish,
      sampleCurrent,
      createDraft,
      saveDraft,
      perform,
      testDialog,
      testOpen,
      testError,
      testFields,
      openTest,
      closeTest,
      submitTest,
      trapTestFocus,
    };
  },
};
