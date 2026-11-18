<template>
  <div class="wizard-stepper">
    <div
      v-for="(title, index) in steps"
      :key="title"
      :class="{ active: progressStep === index, complete: progressStep > index }"
    >
      <b>{{ step > index ? '✓' : index + 1 }}</b>
      {{ title }}
    </div>
  </div>
  <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
  <p v-if="notice" class="notice success" role="status">{{ notice }}</p>
  <div class="grid-two">
    <PanelCard
      :title="step === 4 ? '部署执行与健康检查' : steps[step]"
      :description="descriptions[step]"
    >
      <form v-if="step === 0" @submit.prevent="selectHost">
        <label>
          应用环境名称
          <input v-model="environment" pattern="[a-z][a-z0-9-]{0,39}" required />
          <small>新环境使用独立网络、容器和数据目录；已有同名环境进入升级或配置变更流程。</small>
        </label>
        <label>
          目标服务器
          <select v-model="node" required>
            <option value="" disabled>选择已注册执行器</option>
            <option v-for="host in nodes" :key="host.id" :value="host.id">
              {{ host.name }} · {{ host.url }}
            </option>
          </select>
        </label>
        <label>
          目标主机的应用数据目录
          <input
            :value="
              selectedStorage.runtimeDirectory || '\u7b49\u5f85\u4e3b\u673a\u5b58\u50a8\u4fe1\u606f'
            "
            readonly
          />
          <small>使用目标主机安装时选择的目录；预检会核对实际路径与可用容量。</small>
        </label>
        <footer class="form-actions">
          <button class="primary" :disabled="busy">检查主机并继续</button>
        </footer>
      </form>
      <template v-else-if="step === 1">
        <InstallationProtocol :protocol="release?.installationProtocol" />
        <label>
          选择可信离线发行包
          <select v-model="digest" @change="initializeParameters">
            <option value="" disabled>请选择已验证发行包</option>
            <option v-for="release in catalog" :key="release.digest" :value="release.digest">
              {{ release.name }} · {{ release.version }} · {{ release.platform }}
            </option>
          </select>
        </label>
        <details>
          <summary>导入新的离线发行包</summary>
          <div style="padding-top: 18px">
            <PackageUpload :node="node" @verified="loadCatalog" />
          </div>
        </details>
        <footer class="form-actions">
          <button @click="step--">上一步</button>
          <button class="primary" :disabled="!release" @click="step++">配置应用参数</button>
        </footer>
      </template>
      <form v-else-if="step === 2" @submit.prevent="preflight">
        <InstallationProtocol :protocol="release?.installationProtocol" />
        <ParameterFields
          v-model="parameters"
          :fields="release?.parameters || {}"
          :retain-secrets="!!existingDeployment"
        />
        <footer class="form-actions">
          <button type="button" @click="step--">上一步</button>
          <button type="button" @click="saveDraft">保存草稿</button>
          <button class="primary" :disabled="busy">
            {{ busy ? '正在预检…' : '运行部署预检' }}
          </button>
        </footer>
      </form>
      <form v-else-if="step === 3" @submit.prevent="deploy">
        <PreflightReview :review="review" />
        <label v-if="review?.ok" style="margin-top: 24px">
          输入环境名称确认执行
          <input v-model="confirmation" :placeholder="environment" required />
        </label>
        <footer class="form-actions">
          <button
            type="button"
            @click="
              step = 2;
              review = null;
            "
          >
            返回参数配置
          </button>
          <button class="primary" :disabled="!review?.ok || busy || confirmation !== environment">
            {{ busy ? '正在提交…' : '确认部署 / 升级' }}
          </button>
        </footer>
      </form>
      <template v-else-if="task">
        <StateBadge :status="task.status" />
        <p style="margin-top: 15px">{{ task.message }}</p>
        <TaskSteps :steps="task.steps" />
        <RouterLink :to="`/tasks/deployment/${node}/${task.id}`" class="button primary">
          打开任务中心
        </RouterLink>
      </template>
    </PanelCard>
    <div>
      <PanelCard v-if="task" title="部署日志">
        <pre class="log-viewer">{{ task.installerLog || '等待首条执行日志…' }}</pre>
      </PanelCard>
      <PanelCard title="部署范围">
        <dl class="facts">
          <dt>环境</dt>
          <dd>{{ environment || '尚未配置' }}</dd>
          <dt>主机</dt>
          <dd>{{ nodes.find((n) => String(n.id) === String(node))?.name || '尚未选择' }}</dd>
          <dt>应用</dt>
          <dd>{{ release?.name || '尚未选择' }}</dd>
          <dt>版本</dt>
          <dd>{{ release?.version || '尚未选择' }}</dd>
          <dt>数据目录</dt>
          <dd>{{ review?.runtimeDirectory || selectedStorage.runtimeDirectory }}</dd>
          <dt>数据保留</dt>
          <dd>升级与回滚均保留持久化数据卷</dd>
        </dl>
      </PanelCard>
      <PanelCard title="执行说明">
        <p class="muted">
          镜像须来自已验证的离线发行包。配置经预检生成有时效的执行计划，确认后按组件依赖顺序部署。
        </p>
        <p class="muted">
          部署任务与实时健康状态分别记录。失败或中断后，可在任务中心查看具体步骤及允许的恢复操作。
        </p>
        <button v-if="step < 4" @click="restoreDraft">恢复已保存草稿</button>
      </PanelCard>
    </div>
  </div>
</template>
<script setup>
  import { ref, computed, onUnmounted } from 'vue';
  import { useRoute } from 'vue-router';
  import { useCluster } from '../../stores/cluster';
  import { infrastructureApi } from '../../api/infrastructure';
  import { deploymentsApi } from '../../api/deployments';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import StateBadge from '../../components/ui/StateBadge.vue';
  import PackageUpload from '../../components/deployments/PackageUpload.vue';
  import ParameterFields from '../../components/deployments/ParameterFields.vue';
  import InstallationProtocol from '../../components/deployments/InstallationProtocol.vue';
  import PreflightReview from '../../components/deployments/PreflightReview.vue';
  import TaskSteps from '../../components/tasks/TaskSteps.vue';
  const route = useRoute(),
    { nodes, jobs, inventory } = useCluster(),
    node = ref(route.query.node || ''),
    environment = ref(route.query.environment || ''),
    step = ref(0),
    catalog = ref([]),
    digest = ref(''),
    parameters = ref({}),
    review = ref(null),
    confirmation = ref(''),
    task = ref(null),
    busy = ref(false),
    error = ref(''),
    notice = ref('');
  let timer,
    disposed = false;
  const steps = ['环境检查', '离线镜像 / 发行包', '参数配置', '预检与执行', '健康检查'],
    descriptions = [
      '选择管理对象与持久化范围',
      '导入或选择已验证的离线应用发行包',
      '根据发行包定义生成组件参数',
      '确认作用范围、资源预算和依赖顺序',
      '跟踪真实执行步骤与组件健康检查',
    ];
  const existingDeployment = computed(() =>
    jobs.value.find(
      (job) =>
        job.active &&
        job.environment === environment.value &&
        String(job.nodeId) === String(node.value)
    )
  );
  const progressStep = computed(() =>
    step.value < 4 ? step.value : task.value?.status === 'succeeded' ? 4 : 3
  );
  const selectedStorage = computed(
    () => inventory.value.find((n) => String(n.id) === String(node.value))?.data?.storage || {}
  );
  const release = computed(() => catalog.value.find((item) => item.digest === digest.value));
  async function loadCatalog() {
    catalog.value = await deploymentsApi.catalog(node.value);
  }
  async function selectHost() {
    busy.value = true;
    error.value = '';
    try {
      await infrastructureApi.host(node.value);
      await loadCatalog();
      step.value = 1;
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
  function initializeParameters() {
    parameters.value = Object.fromEntries(
      Object.entries(release.value?.parameters || {}).map(([key, field]) => [
        key,
        (!field.secret ? existingDeployment.value?.parameters?.[key] : undefined) ??
          field.default ??
          (field.type === 'boolean' ? false : ''),
      ])
    );
  }
  async function preflight() {
    busy.value = true;
    error.value = '';
    try {
      const values = {};
      for (const [key, field] of Object.entries(release.value.parameters || {})) {
        const value = parameters.value[key];
        if (value !== '' && value != null)
          values[key] = field.type === 'integer' ? Number(value) : value;
      }
      review.value = await deploymentsApi.preflight(node.value, {
        package: digest.value,
        environment: environment.value,
        parameters: values,
      });
      confirmation.value = '';
      step.value = 3;
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
  async function deploy() {
    busy.value = true;
    error.value = '';
    try {
      task.value = await deploymentsApi.deploy(node.value, {
        package: digest.value,
        environment: environment.value,
        reviewToken: review.value.reviewToken,
      });
      step.value = 4;
      poll();
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
  async function poll() {
    if (disposed) return;
    try {
      task.value = await deploymentsApi.job(node.value, task.value.id);
    } catch (e) {
      error.value = e.message;
    }
    if (!disposed && ['queued', 'running', 'starting', 'rolling_back'].includes(task.value.status))
      timer = setTimeout(poll, 2000);
  }
  function saveDraft() {
    const values = {};
    for (const [key, field] of Object.entries(release.value?.parameters || {}))
      if (!field.secret) values[key] = parameters.value[key];
    localStorage.setItem(
      'ops-deploy-draft',
      JSON.stringify({
        node: node.value,
        environment: environment.value,
        digest: digest.value,
        parameters: values,
      })
    );
    notice.value = '已保存非敏感配置；密码和密钥不会写入草稿。';
  }
  async function restoreDraft() {
    error.value = '';
    try {
      const draft = JSON.parse(localStorage.getItem('ops-deploy-draft') || 'null');
      if (!draft) throw Error('尚未保存部署草稿。');
      node.value = draft.node;
      environment.value = draft.environment;
      await loadCatalog();
      if (!catalog.value.some((item) => item.digest === draft.digest))
        throw Error('草稿对应的发行包不在当前主机可信发行库中。');
      digest.value = draft.digest;
      initializeParameters();
      parameters.value = { ...parameters.value, ...draft.parameters };
      step.value = 2;
    } catch (e) {
      error.value = e.message;
    }
  }
  onUnmounted(() => {
    disposed = true;
    clearTimeout(timer);
  });
</script>
