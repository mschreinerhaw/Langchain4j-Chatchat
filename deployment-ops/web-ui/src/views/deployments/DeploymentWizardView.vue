<template>
  <div class="wizard-stepper" aria-label="部署进度">
    <div
      v-for="(title, index) in steps"
      :key="title"
      :class="{ active: step === index, complete: step > index }"
      :aria-current="step === index ? 'step' : undefined"
    >
      <b>{{ index + 1 }}</b>
      {{ title }}
    </div>
  </div>
  <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
  <p v-if="notice" class="notice success" role="status">{{ notice }}</p>
  <div class="grid-two">
    <div v-if="step === 0">
      <PanelCard title="部署配置">
        <form @submit.prevent="preflight">
          <div class="form-grid">
            <label>
              目标服务器
              <select
                :value="node"
                :disabled="busy"
                aria-label="目标服务器"
                required
                @change="selectNode($event.target.value)"
              >
                <option value="" disabled>选择已注册的服务器</option>
                <option v-for="host in nodes" :key="host.id" :value="String(host.id)">
                  {{ host.name === 'local' ? '本机' : host.name }}
                </option>
              </select>
              <small v-if="loadingHost" role="status">正在检查主机和发行库…</small>
              <small v-else-if="hostReady">主机已连接，持久化数据位于 /mnt/disk01</small>
              <button v-else-if="node" type="button" @click="selectNode(node)">重新检查</button>
            </label>
            <label>
              环境名称
              <input
                v-model.trim="environment"
                pattern="[a-z][a-z0-9-]{0,39}"
                required
                placeholder="例如 team-test"
                aria-label="环境名称"
                :disabled="busy"
              />
              <small>
                {{
                  existingDeployment
                    ? '将预检已有环境的兼容升级。'
                    : '使用小写字母、数字或连字符，首字符为字母。'
                }}
              </small>
            </label>
          </div>
          <label v-if="catalog.length">
            发行版本
            <select
              v-model="digest"
              aria-label="发行版本"
              :disabled="busy"
              required
              @change="initializeParameters"
            >
              <option value="" disabled>选择已验证的应用版本</option>
              <option v-for="item in catalog" :key="item.digest" :value="item.digest">
                {{ item.name }} · {{ item.version }}
              </option>
            </select>
          </label>
          <template v-if="release">
            <ParameterFields
              v-model="parameters"
              :fields="release.parameters || {}"
              :retain-secrets="!!existingDeployment"
            />
            <details>
              <summary>查看安装协议</summary>
              <InstallationProtocol :protocol="release.installationProtocol" />
            </details>
          </template>
          <footer class="form-actions">
            <button v-if="release" type="button" :disabled="busy" @click="saveDraft">
              保存草稿
            </button>
            <button class="primary" :disabled="busy || !hostReady || !release">
              {{ busy ? '正在预检…' : '运行部署预检' }}
            </button>
          </footer>
        </form>
      </PanelCard>
      <PanelCard v-if="hostReady" title="发行包">
        <details :open="!catalog.length">
          <summary>{{ catalog.length ? '导入另一个签名发行包' : '导入第一个签名发行包' }}</summary>
          <PackageUpload :key="node" :node="node" @verified="packageVerified" />
        </details>
      </PanelCard>
    </div>
    <PanelCard v-else-if="step === 1" title="预检与确认">
      <form @submit.prevent="deploy">
        <PreflightReview :review="review" />
        <label v-if="review.ok && review.upgrade">
          输入环境名称确认升级
          <input v-model.trim="confirmation" :placeholder="environment" required />
        </label>
        <footer class="form-actions">
          <button type="button" :disabled="busy" @click="step = 0">返回修改</button>
          <button
            class="primary"
            :disabled="!review.ok || busy || (review.upgrade && confirmation !== environment)"
          >
            {{ busy ? '正在提交…' : review.upgrade ? '开始升级' : '开始部署' }}
          </button>
        </footer>
      </form>
    </PanelCard>
    <PanelCard v-else title="部署结果">
      <StateBadge :status="task.status" />
      <p role="status">{{ task.message }}</p>
      <TaskSteps :steps="task.steps" />
      <RouterLink :to="`/tasks/deployment/${node}/${task.id}`" class="button primary">
        查看任务详情
      </RouterLink>
    </PanelCard>
    <div>
      <PanelCard title="部署范围">
        <dl class="facts">
          <dt>环境</dt>
          <dd>{{ environment || '待填写' }}</dd>
          <dt>服务器</dt>
          <dd>{{ hostName || '待选择' }}</dd>
          <dt>版本</dt>
          <dd>{{ release ? `${release.name} · ${release.version}` : '待选择' }}</dd>
        </dl>
        <button
          v-if="step === 0"
          type="button"
          :disabled="busy || loadingHost"
          @click="restoreDraft"
        >
          恢复已保存草稿
        </button>
        <RouterLink v-if="!node && !nodes.length" to="/hosts">管理服务器</RouterLink>
      </PanelCard>
      <PanelCard v-if="task?.installerLog" title="部署日志">
        <pre class="log-viewer">{{ task.installerLog }}</pre>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
  import { computed, onUnmounted, ref, watch } from 'vue';
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

  const route = useRoute();
  const { nodes, jobs } = useCluster();
  const steps = ['配置部署', '预检确认', '执行结果'];
  const node = ref('');
  const environment = ref(String(route.query.environment || ''));
  const step = ref(0);
  const catalog = ref([]);
  const digest = ref('');
  const parameters = ref({});
  const review = ref(null);
  let reviewedSpec;
  const confirmation = ref('');
  const task = ref(null);
  const busy = ref(false);
  const loadingHost = ref(false);
  const hostReady = ref(false);
  const error = ref('');
  const notice = ref('');
  let nodeRequest;
  let timer;
  let disposed = false;

  const hostName = computed(() => {
    const name = nodes.value.find((host) => String(host.id) === node.value)?.name;
    return name === 'local' ? '本机' : name;
  });
  const existingDeployment = computed(() =>
    jobs.value.find(
      (job) =>
        job.active && job.environment === environment.value && String(job.nodeId) === node.value
    )
  );
  const release = computed(() => catalog.value.find((item) => item.digest === digest.value));
  watch(existingDeployment, (current, previous) => {
    if (current?.id !== previous?.id && step.value === 0) initializeParameters();
  });

  watch(
    nodes,
    (available) => {
      if (node.value || !available.length) return;
      const requested = available.find((host) => String(host.id) === route.query.node);
      const local = available.find((host) => host.name === 'local');
      const host = requested || local || (available.length === 1 ? available[0] : null);
      if (host) selectNode(String(host.id));
    },
    { immediate: true }
  );

  async function selectNode(selected, preferredDigest) {
    nodeRequest?.abort();
    const controller = new AbortController();
    nodeRequest = controller;
    const { signal } = controller;
    node.value = selected;
    hostReady.value = false;
    loadingHost.value = true;
    error.value = '';
    notice.value = '';
    catalog.value = [];
    digest.value = '';
    parameters.value = {};
    try {
      await infrastructureApi.host(selected, signal);
      const releases = await deploymentsApi.catalog(selected, signal);
      if (signal.aborted) return false;
      hostReady.value = true;
      useCatalog(releases, preferredDigest);
      return true;
    } catch (e) {
      if (!signal.aborted) error.value = e.message;
      return false;
    } finally {
      if (!signal.aborted) loadingHost.value = false;
    }
  }

  function useCatalog(releases, preferred) {
    catalog.value = releases;
    digest.value =
      releases.find((item) => item.digest === preferred)?.digest ||
      (releases.length === 1 ? releases[0].digest : '');
    initializeParameters();
  }

  async function packageVerified(upload) {
    const selected = node.value;
    const { signal } = nodeRequest;
    try {
      const releases = await deploymentsApi.catalog(selected, signal);
      if (signal.aborted) return;
      if (busy.value || step.value !== 0) {
        catalog.value = releases;
        return;
      }
      useCatalog(releases, upload.sha256);
      notice.value = '发行包已验证，可以配置参数并运行预检。';
    } catch (e) {
      if (!signal.aborted) error.value = e.message;
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
    notice.value = '';
    try {
      const values = {};
      for (const [key, field] of Object.entries(release.value.parameters || {})) {
        const value = parameters.value[key];
        if (value !== '' && value != null)
          values[key] = field.type === 'integer' ? Number(value) : value;
      }
      reviewedSpec = { package: digest.value, environment: environment.value };
      review.value = await deploymentsApi.preflight(node.value, {
        ...reviewedSpec,
        parameters: values,
      });
      confirmation.value = '';
      step.value = 1;
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
        ...reviewedSpec,
        reviewToken: review.value.reviewToken,
      });
      step.value = 2;
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
      error.value = '';
    } catch (e) {
      error.value = e.message;
    }
    if (!disposed && ['queued', 'running', 'starting', 'rolling_back'].includes(task.value.status))
      timer = setTimeout(poll, 2000);
  }

  function saveDraft() {
    const values = {};
    for (const [key, field] of Object.entries(release.value?.parameters || {})) {
      if (!field.secret) values[key] = parameters.value[key];
    }
    localStorage.setItem(
      'ops-deploy-draft',
      JSON.stringify({
        node: node.value,
        environment: environment.value,
        digest: digest.value,
        parameters: values,
      })
    );
    notice.value = '已保存非敏感配置。';
  }

  async function restoreDraft() {
    error.value = '';
    try {
      const draft = JSON.parse(localStorage.getItem('ops-deploy-draft') || 'null');
      if (!draft) throw Error('尚未保存部署草稿。');
      environment.value = draft.environment;
      if (!(await selectNode(String(draft.node), draft.digest))) return;
      if (digest.value !== draft.digest)
        throw Error('草稿对应的发行包已不在这台服务器的发行库中。');
      for (const [key, field] of Object.entries(release.value?.parameters || {})) {
        if (!field.secret && Object.hasOwn(draft.parameters, key))
          parameters.value[key] = draft.parameters[key];
      }
      notice.value = '已恢复草稿。';
    } catch (e) {
      error.value = e.message;
    }
  }

  onUnmounted(() => {
    disposed = true;
    nodeRequest?.abort();
    clearTimeout(timer);
  });
</script>
