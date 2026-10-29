import { defineComponent, computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import PythonCodeDiff from "../../components/PythonCodeDiff.vue";
import { buildPythonLineDiff } from "../utils/pythonCodeDiff";
import {
  cancelCodingTask,
  codingStage,
  codeVersions,
  fetchCodingCode,
  fetchCodingTiming,
  fetchCodingEvents,
  fetchCodingTasks,
  fetchCodingInputs,
  mergeCodingEvents,
  modelCalls,
  pythonExecutions,
  pythonRepairs,
  pythonSummary,
  pythonTimeline,
  type PythonRepair,
  resumeCodingTask,
  stageLabels,
  type CodingEvent,
  type CodingTask,
  type CodingInputVersion,
  type CodingExecutionTiming,
} from "../../services/codingMonitor";

export default defineComponent({
  name: "CodingTaskMonitor",
  components: { PythonCodeDiff },
  props: {
    runId: { type: String, required: true },
  },
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;
    const expanded = ref(false),
      loading = ref(false),
      cancelling = ref(false),
      error = ref(""),
      codeError = ref("");
    const resuming = ref(false);
    const codePanel = ref<HTMLElement | null>(null);
    const timings = ref<Record<string, CodingExecutionTiming>>({}), timingErrors = ref<Record<string, string>>({});
    const timingRequested = new Set<string>();
    let timingGeneration = 0;
    const inputVersions = ref<CodingInputVersion[]>([]),
      inputBinding = ref("TASK_LATEST"),
      inputError = ref("");
    let inputGeneration = 0;
    const tasks = ref<CodingTask[]>([]),
      selectedId = ref(""),
      events = ref<Record<string, CodingEvent[]>>({});
    const selectedOperation = ref(""),
      code = ref<Record<string, any> | null>(null),
      previousCode = ref("");
    const showDiff = ref(false),
      eventLimit = ref(100),
      now = ref(Date.now());
    let timer: ReturnType<typeof setInterval> | undefined;
    let generation = 0;
    let codeGeneration = 0, diffGeneration = 0, pendingDiff = false;
    const selected = computed(() => tasks.value.find((task) => task.taskId === selectedId.value));
    const selectedEvents = computed(() => events.value[selectedId.value] ?? []);
    const stage = computed(() =>
      selected.value ? codingStage(selected.value, selectedEvents.value) : "QUEUED"
    );
    const executions = computed(() => selected.value ? pythonExecutions(selected.value, selectedEvents.value) : []);
    const repairs = computed(() => pythonRepairs(executions.value, selectedEvents.value));
    const diagnostics = computed(() => pythonSummary(executions.value, repairs.value));
    const executionTimeline = computed(() => pythonTimeline(executions.value, repairs.value, selectedEvents.value));
    const versions = computed(() => codeVersions(selectedEvents.value, executions.value));
    const focusedExecution = computed(() => executions.value.find(item => item.executionId === selectedOperation.value));
    const version = computed(() =>
      versions.value.find((item) => item.operationId === selectedOperation.value)
    );
    const previousVersion = computed(() => {
      const repair = repairs.value.find(item => item.to.executionId === selectedOperation.value);
      return repair ? versions.value.find(item => item.operationId === repair.from.executionId)
        : versions.value[versions.value.findIndex(item => item.operationId === selectedOperation.value) - 1];
    });
    const calls = computed(() => modelCalls(selectedEvents.value, selected.value?.modelReceipts));
    const loadedSkills = computed(
      () =>
        (selected.value?.configuration.skills?.skills ?? [])
          .map((skill: any) => skill.name || skill.skillName || skill.skillId || skill.id)
          .join("、") || "无"
    );
    const skillApplications = computed(
      () => selectedEvents.value.filter((event) => event.payload?.type === "SKILLS_APPLIED").length
    );
    const skillExecutions = computed(
      () => selectedEvents.value.filter((event) => event.payload?.type === "SKILL_EXECUTED").length
    );
    const elapsed = computed(() =>
      selected.value
        ? Math.max(
            0,
            Math.floor(
              ((selected.value.outcome.endedAt
                ? Date.parse(selected.value.outcome.endedAt)
                : now.value) -
                Date.parse(selected.value.outcome.createdAt)) /
                1000
            )
          )
        : 0
    );
    const visibleEvents = computed(() => selectedEvents.value.slice(-eventLimit.value));
    const diff = computed(() => buildPythonLineDiff(previousCode.value, code.value?.content ?? ""));
    const time = (value?: string) => (value ? new Date(value).toLocaleString() : "—");
    async function refresh() {
      if (loading.value || !expanded.value || !props.runId) return;
      const run = props.runId,
        epoch = generation;
      loading.value = true;
      try {
        const result = await fetchCodingTasks(run);
        if (epoch !== generation) return;
        tasks.value = result.tasks;
        if (!tasks.value.some((task) => task.taskId === selectedId.value))
          selectedId.value = tasks.value[0]?.taskId ?? "";
        for (const task of result.tasks) {
          let cursor = (events.value[task.taskId] ?? []).at(-1)?.sequence ?? 0;
          for (let page = 0; page < 64; page++) {
            const result = await fetchCodingEvents(run, task.taskId, cursor);
            if (epoch !== generation) return;
            events.value[task.taskId] = mergeCodingEvents(
              events.value[task.taskId] ?? [],
              result.events
            );
            if (!result.hasMore || result.nextSequence <= cursor) break;
            cursor = result.nextSequence;
          }
        }
        error.value = "";
        await nextTick(); void loadTimings();
        void loadInputs();
      } catch (failure: any) {
        if (epoch === generation) error.value = failure.message || "读取任务失败";
      } finally {
        if (epoch === generation) loading.value = false;
      }
    }
    async function loadTimings() {
      const run = props.runId, task = selectedId.value, epoch = timingGeneration;
      await Promise.all(executions.value.filter(row => row.feedback && !timingRequested.has(row.executionId)).map(async row => {
        timingRequested.add(row.executionId);
        try {
          const receipt = await fetchCodingTiming(run, task, row.executionId);
          if (receipt.operationId !== row.executionId || receipt.codeHash !== row.codeHash || receipt.source !== 'MCP_ARCHIVED_RECEIPT')
            throw new Error('归档时间回执与当前执行不一致');
          if (epoch === timingGeneration && run === props.runId && task === selectedId.value) timings.value[row.executionId] = receipt;
        } catch {
          if (epoch === timingGeneration && run === props.runId && task === selectedId.value)
            timingErrors.value[row.executionId] = '归档执行器起止时间不可用；不会以调度时间替代';
        }
      }));
    }
    async function loadInputs() {
      const epoch = generation,
        request = ++inputGeneration,
        run = props.runId,
        task = selectedId.value,
        operation = selectedOperation.value;
      if (!run || !task) return;
      try {
        const result = await fetchCodingInputs(run, task, operation || undefined);
        if (
          epoch === generation &&
          request === inputGeneration &&
          task === selectedId.value &&
          operation === selectedOperation.value
        ) {
          inputVersions.value = result.snapshots;
          inputBinding.value = result.binding;
          inputError.value = "";
        }
      } catch (failure: any) {
        if (epoch === generation && request === inputGeneration)
          inputError.value = failure.message || "输入版本暂不可用";
      }
    }
    async function loadCode() {
      const request = ++codeGeneration;
      diffGeneration++;
      code.value = null;
      showDiff.value = false;
      codeError.value = "";
      const run = props.runId,
        task = selectedId.value,
        operation = selectedOperation.value;
      if (!run || !task || !operation) return;
      try {
        const result = await fetchCodingCode(run, task, operation);
        if (
          run === props.runId &&
          task === selectedId.value &&
          operation === selectedOperation.value && request === codeGeneration
        ) {
          if (version.value?.hash && result.sha256 && version.value.hash !== result.sha256)
            throw new Error("源码哈希与执行版本不一致，已停止展示");
          code.value = result;
          if (pendingDiff) { pendingDiff = false; await loadDiff(); }
        }
      } catch (failure: any) {
        if (run === props.runId && task === selectedId.value && operation === selectedOperation.value && request === codeGeneration)
          codeError.value = failure.message || "源码尚未归档";
      }
    }
    async function loadDiff() {
      const previous = previousVersion.value,
        operation = selectedOperation.value, run = props.runId, task = selectedId.value, request = ++diffGeneration;
      if (!previous) return;
      try {
        const result = await fetchCodingCode(run, task, previous.operationId);
        if (run === props.runId && task === selectedId.value && operation === selectedOperation.value && request === diffGeneration) {
          if (previous.hash && result.sha256 && previous.hash !== result.sha256)
            throw new Error("修复前源码哈希与执行记录不一致");
          previousCode.value = result.content;
          showDiff.value = true;
        }
      } catch (failure: any) {
        if (request === diffGeneration && run === props.runId && task === selectedId.value) codeError.value = failure.message;
      }
    }
    async function selectExecution(operation: string) {
      pendingDiff = false;
      selectedOperation.value = operation;
      await nextTick(); codePanel.value?.scrollIntoView?.({ block: 'start' });
    }
    async function selectRepair(repair: PythonRepair) {
      pendingDiff = true;
      if (selectedOperation.value === repair.to.executionId) { pendingDiff = false; void loadDiff(); }
      else selectedOperation.value = repair.to.executionId;
      await nextTick(); codePanel.value?.scrollIntoView?.({ block: 'start' });
    }
    async function cancel() {
      if (!selected.value?.canCancel || cancelling.value) return;
      cancelling.value = true;
      try {
        await cancelCodingTask(props.runId, selectedId.value);
        await refresh();
      } catch (failure: any) {
        error.value = failure.message;
      } finally {
        cancelling.value = false;
      }
    }
    async function resume() {
      if (!selected.value?.canResume || resuming.value) return;
      resuming.value = true;
      try {
        await resumeCodingTask(props.runId, selectedId.value);
        await refresh();
      } catch (failure: any) {
        error.value = failure.message;
      } finally {
        resuming.value = false;
      }
    }
    function toggle(event: Event) {
      expanded.value = (event.target as HTMLDetailsElement).open;
      clearInterval(timer);
      if (expanded.value) {
        void refresh();
        timer = setInterval(() => {
          now.value = Date.now();
          void refresh();
        }, 2500);
      }
    }
    watch(
      () => props.runId,
      () => {
        generation++;
        timingGeneration++; timingRequested.clear(); timings.value = {}; timingErrors.value = {};
        codeGeneration++; diffGeneration++; pendingDiff = false;
        loading.value = false;
        tasks.value = [];
        events.value = {};
        selectedId.value = "";
        selectedOperation.value = "";
        code.value = null;
        void refresh();
      }
    );
    watch(selectedId, () => {
      timingGeneration++; timingRequested.clear(); timings.value = {}; timingErrors.value = {};
      codeGeneration++; diffGeneration++; pendingDiff = false;
      selectedOperation.value = "";
      eventLimit.value = 100;
      code.value = null;
      inputVersions.value = [];
      inputError.value = "";
    });
    watch(versions, (value) => {
      if (!value.some((version) => version.operationId === selectedOperation.value))
        selectedOperation.value = value.at(-1)?.operationId ?? "";
    });
    watch(selectedOperation, loadCode);
    watch([selectedId, selectedOperation], () => {
      inputVersions.value = [];
      inputBinding.value = "LOADING";
      void loadInputs();
    });
    onBeforeUnmount(() => {
      generation++;
      clearInterval(timer);
    });
    return {
      stageLabels,
      expanded,
      loading,
      cancelling,
      error,
      codeError,
      codePanel,
      timings,
      timingErrors,
      resuming,
      inputVersions,
      inputBinding,
      inputError,
      tasks,
      selectedId,
      selectedOperation,
      code,
      showDiff,
      eventLimit,
      selected,
      selectedEvents,
      stage,
      versions,
      version,
      previousVersion,
      executions,
      repairs,
      diagnostics,
      executionTimeline,
      focusedExecution,
      selectExecution,
      selectRepair,
      calls,
      loadedSkills,
      skillApplications,
      skillExecutions,
      elapsed,
      visibleEvents,
      diff,
      time,
      refresh,
      loadCode,
      loadDiff,
      cancel,
      resume,
      toggle,
    };
  },
});
