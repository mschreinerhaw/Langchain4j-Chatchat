<template>
  <section class="enterprise-ui-artifact" :data-artifact-id="artifactId" @click="handleArtifactClick">
    <div v-if="loading" class="artifact-shell-state">正在装载动态报告…</div>
    <div v-else-if="error" class="artifact-shell-state error" role="alert">{{ error }}</div>
    <JSONUIProvider v-else-if="spec" :registry="enterpriseUiRegistry" :initial-state="{}">
      <Renderer :spec="spec" :registry="enterpriseUiRegistry" />
    </JSONUIProvider>
  </section>
</template>

<script setup>
import { computed, onErrorCaptured, provide, ref, watch } from "vue";
import { JSONUIProvider, Renderer } from "@json-render/vue";
import { fetchUiArtifact, fetchUiArtifactResource } from "../services/api.js";
import {
  ARTIFACT_EVENT_DISPATCHER,
  ARTIFACT_RESOURCE_LOADER,
  enterpriseUiRegistry
} from "../js/ui-artifact/registry.js";

const emit = defineEmits(["drill-down", "table-chart"]);

const props = defineProps({
  artifact: {
    type: Object,
    required: true
  }
});

const manifest = ref(null);
const loading = ref(false);
const error = ref("");
const resourceCache = new Map();

const artifactId = computed(() => String(props.artifact?.artifactId || ""));
const spec = computed(() => manifest.value?.spec || null);

function handleArtifactClick(event) {
  const chartTarget = event.target?.closest?.("[data-result-chart-payload]");
  if (!chartTarget) return;
  event.preventDefault();
  event.stopPropagation();
  emit("table-chart", chartTarget.dataset.resultChartPayload || "");
}

async function loadManifest() {
  manifest.value = null;
  resourceCache.clear();
  error.value = "";
  if (!artifactId.value) {
    error.value = "动态报告缺少 artifactId";
    return;
  }
  loading.value = true;
  try {
    const nextManifest = await fetchUiArtifact(artifactId.value);
    if (nextManifest?.schemaVersion !== "enterprise_ui_artifact_v1") {
      throw new Error("不支持的动态报告协议");
    }
    manifest.value = nextManifest;
  } catch (loadError) {
    error.value = loadError?.message || "动态报告加载失败";
  } finally {
    loading.value = false;
  }
}

async function loadResource(resourceId) {
  const key = String(resourceId || "");
  if (!key || !manifest.value?.resources?.[key]) {
    throw new Error(`动态报告资源不存在：${key}`);
  }
  if (!resourceCache.has(key)) {
    resourceCache.set(key, fetchUiArtifactResource(artifactId.value, key));
  }
  return resourceCache.get(key);
}

provide(ARTIFACT_RESOURCE_LOADER, loadResource);
provide(ARTIFACT_EVENT_DISPATCHER, (eventName, payload = {}, context = {}) => {
  if (eventName !== "drill-down") {
    return;
  }
  emit("drill-down", {
    ...payload,
    artifactId: artifactId.value,
    artifactResourceId: context.resourceId || "",
    artifactSchemaVersion: manifest.value?.schemaVersion || "enterprise_ui_artifact_v1"
  });
});
onErrorCaptured((renderError) => {
  error.value = renderError?.message || "动态报告渲染失败";
  return false;
});
watch(artifactId, loadManifest, { immediate: true });
</script>

<style scoped>
.enterprise-ui-artifact {
  margin-top: 0.25rem;
}

.artifact-shell-state {
  padding: 0.9rem 1rem;
  border: 1px solid var(--border-color, #dbe3ef);
  border-radius: 12px;
  color: var(--text-secondary, #667085);
  background: var(--surface-muted, #f8fafc);
}

.artifact-shell-state.error {
  color: #b42318;
  border-color: #fecdca;
  background: #fffbfa;
}

:deep(.enterprise-ui-report) {
  display: grid;
  gap: 1rem;
}

:deep(.artifact-html-document) {
  color: #172033;
  line-height: 1.72;
}

:deep(.artifact-html-document h1),
:deep(.artifact-html-document h2),
:deep(.artifact-html-document h3) {
  color: #101828;
  line-height: 1.3;
}

:deep(.artifact-html-document table) {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  overflow: hidden;
  border: 1px solid #dce5f1;
  border-radius: 12px;
}

:deep(.artifact-html-document th),
:deep(.artifact-html-document td) {
  padding: 0.72rem 0.8rem;
  border-right: 1px solid #e4eaf2;
  border-bottom: 1px solid #e4eaf2;
  text-align: left;
}

:deep(.artifact-html-document th) {
  background: #f3f7fc;
  font-weight: 700;
}

:deep(.artifact-html-document tr:last-child td) {
  border-bottom: 0;
}

:deep(.artifact-html-document th:last-child),
:deep(.artifact-html-document td:last-child) {
  border-right: 0;
}

:deep(.artifact-notice),
:deep(.artifact-evidence) {
  padding: 0.72rem 0.9rem;
  border: 1px solid #dbe3ef;
  border-radius: 12px;
  background: #f8fafc;
  color: #667085;
  font-size: 0.84rem;
}

:deep(.artifact-notice > summary),
:deep(.artifact-evidence > summary),
:deep(.artifact-evidence-item > summary) {
  cursor: pointer;
  list-style: none;
}

:deep(.artifact-notice > summary::-webkit-details-marker),
:deep(.artifact-evidence > summary::-webkit-details-marker),
:deep(.artifact-evidence-item > summary::-webkit-details-marker) {
  display: none;
}

:deep(.artifact-notice > summary),
:deep(.artifact-evidence > summary) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  min-height: 1.5rem;
  color: #667085;
  font-size: 0.84rem;
  font-weight: 500;
}

:deep(.artifact-notice > summary::after),
:deep(.artifact-evidence > summary::after) {
  content: "＋";
  color: #667085;
}

:deep(.artifact-notice[open] > summary::after),
:deep(.artifact-evidence[open] > summary::after) {
  content: "－";
}

:deep(.artifact-notice > summary > span),
:deep(.artifact-evidence > summary > span) {
  margin-left: auto;
  color: #667085;
  font-size: 0.84rem;
}

:deep(.artifact-evidence h4) {
  margin: 0;
  color: inherit;
  font-size: inherit;
  font-weight: 500;
}

:deep(.artifact-notice > summary strong) {
  color: inherit;
  font-size: inherit;
  font-weight: 500;
}

:deep(.artifact-notice-content),
:deep(.artifact-evidence > ol) {
  margin-top: 0.85rem;
  color: #667085;
  font-size: 0.82rem;
}

:deep(.artifact-evidence li + li) {
  margin-top: 0.65rem;
}

:deep(.artifact-evidence > ol) {
  padding-left: 0;
  list-style: none;
}

:deep(.artifact-evidence-item) {
  border-top: 1px solid #e4eaf2;
  padding-top: 0.65rem;
}

:deep(.artifact-evidence-item > summary) {
  display: flex;
  align-items: flex-start;
  gap: 0.7rem;
}

:deep(.artifact-evidence-rank) {
  display: inline-grid;
  place-items: center;
  width: 1.65rem;
  height: 1.65rem;
  flex: 0 0 auto;
  border-radius: 999px;
  color: #175cd3;
  background: #eff8ff;
  font-weight: 700;
  font-size: 0.78rem;
}

:deep(.artifact-evidence-heading) {
  display: grid;
  min-width: 0;
  gap: 0.15rem;
}

:deep(.artifact-evidence-heading strong),
:deep(.artifact-evidence-heading a) {
  color: #667085;
  font-size: 0.84rem;
  font-weight: 500;
}

:deep(.artifact-evidence-heading small) {
  overflow: hidden;
  color: #667085;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.artifact-evidence-content) {
  margin: 0.65rem 0 0 2.35rem;
  padding: 0.8rem 0.9rem;
  max-height: 24rem;
  overflow: auto;
  border-radius: 8px;
  color: #475467;
  font-size: 0.82rem;
  background: #fff;
}

:deep(.artifact-resource-state) {
  padding: 0.7rem;
  color: #667085;
}

:deep(.artifact-resource-state.error) {
  color: #b42318;
}
</style>
