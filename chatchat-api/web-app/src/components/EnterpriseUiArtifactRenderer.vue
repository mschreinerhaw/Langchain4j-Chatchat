<template>
  <section
    class="enterprise-ui-artifact"
    :data-artifact-id="artifactId"
    @click="handleArtifactClick"
  >
    <div v-if="loading" class="artifact-shell-state">正在装载动态报告…</div>
    <div v-else-if="error" class="artifact-shell-state error" role="alert">{{ error }}</div>
    <JSONUIProvider v-else-if="spec" :registry="enterpriseUiRegistry" :initial-state="{}">
      <Renderer :spec="spec" :registry="enterpriseUiRegistry" />
    </JSONUIProvider>
  </section>
</template>

<script src="../js/components/EnterpriseUiArtifactRenderer.js"></script>

<style scoped src="../styles/components/enterprise-ui-artifact-renderer.css"></style>
