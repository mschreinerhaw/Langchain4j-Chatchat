import { ref, computed, watch } from "vue";
import {
  sqlTemplateSources,
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
  props: { projectId: { type: String, required: true }, file: { type: Object, required: true } },
  emits: ["close"],
  setup(props) {
    const sources = ref([]),
      history = ref([]),
      selectedId = ref(""),
      busy = ref(false),
      error = ref(""),
      status = ref(""),
      reviewed = ref(false),
      sample = ref("{}");
    const form = ref({
      title: "",
      description: "",
      category: "sql_diagnostic",
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
    const canPublish = computed(
      () =>
        !!selected.value?.testEvidence?.success &&
        sourceCurrent.value &&
        !changed.value &&
        sampleCurrent.value &&
        reviewed.value &&
        !dirty.value
    );
    function choose() {
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
        ]);
        if (current !== epoch) return;
        sources.value = values[0];
        history.value = values[1];
        selectedId.value = history.value[0]?.id || "";
        if (selected.value) choose();
        else {
          form.value.title = props.file.path.split("/").at(-1);
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
    async function createDraft() {
      await run(async () => {
        const input=body();
        if(published.value) {
          const names=[...new Set([...props.file.content.matchAll(/\{\{\s*([A-Za-z0-9_.-]+)\s*}}/g)].map(match=>match[1]))];
          const previous=new Set([...selected.value.sqlTemplate.matchAll(/\{\{\s*([A-Za-z0-9_.-]+)\s*}}/g)].map(match=>match[1]));
          if(names.length!==previous.size || names.some(name=>!previous.has(name)))
            input.parameterSchema={...input.parameterSchema,type:'object',properties:Object.fromEntries(names.map(name=>[name,input.parameterSchema.properties?.[name]||{type:'string'}])),required:names,additionalProperties:false};
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
      });
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
    };
  },
};
