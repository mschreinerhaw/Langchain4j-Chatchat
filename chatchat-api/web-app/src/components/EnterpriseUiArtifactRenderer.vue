<template>
  <section class="enterprise-ui-artifact" :data-artifact-id="artifactId">
    <div v-if="loading" class="artifact-shell-state">正在装载动态报告…</div>
    <div v-else-if="error" class="artifact-shell-state error" role="alert">{{ error }}</div>
    <StateProvider v-else-if="spec" :initial-state="{}">
      <Renderer :spec="spec" :registry="enterpriseUiRegistry" />
    </StateProvider>
  </section>
</template>

<script setup>
import { computed, provide, ref, watch } from "vue";
import { Renderer, StateProvider } from "@json-render/vue";
import { fetchUiArtifact, fetchUiArtifactResource } from "../services/api.js";
import {
  ARTIFACT_EVENT_DISPATCHER,
  ARTIFACT_RESOURCE_LOADER,
  enterpriseUiRegistry
} from "../js/ui-artifact/registry.js";

const emit = defineEmits(["drill-down"]);

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

:deep(.artifact-notice),
:deep(.artifact-evidence) {
  padding: 0.9rem 1rem;
  border: 1px solid #dbe3ef;
  border-radius: 12px;
  background: #f8fafc;
}

:deep(.artifact-notice strong),
:deep(.artifact-evidence h4) {
  display: block;
  margin: 0 0 0.5rem;
}

:deep(.artifact-evidence li + li) {
  margin-top: 0.65rem;
}

:deep(.artifact-evidence p) {
  margin: 0.25rem 0 0;
  color: #667085;
}

:deep(.artifact-resource-state) {
  padding: 0.7rem;
  color: #667085;
}

:deep(.artifact-resource-state.error) {
  color: #b42318;
}
</style>
