import { defineComponent, computed, onBeforeUnmount, ref, watch } from "vue";
import PythonCodeDiff from "../../components/PythonCodeDiff.vue";
import { buildPythonLineDiff } from "../utils/pythonCodeDiff";
import {
  cancelCodingTask,
  codingStage,
  codeVersions,
  fetchCodingCode,
  fetchCodingEvents,
  fetchCodingTasks,
  fetchCodingInputs,
  mergeCodingEvents,
  modelCalls,
  resumeCodingTask,
  stageLabels,
  type CodingEvent,
  type CodingTask,
  type CodingInputVersion,
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
    const selected = computed(() => tasks.value.find((task) => task.taskId === selectedId.value));
    const selectedEvents = computed(() => events.value[selectedId.value] ?? []);
    const stage = computed(() =>
      selected.value ? codingStage(selected.value, selectedEvents.value) : "QUEUED"
    );
    const versions = computed(() => codeVersions(selectedEvents.value));
    const version = computed(() =>
      versions.value.find((item) => item.operationId === selectedOperation.value)
    );
    const previousVersion = computed(
      () =>
        versions.value[
          versions.value.findIndex((item) => item.operationId === selectedOperation.value) - 1
        ]
    );
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
        void loadInputs();
      } catch (failure: any) {
        if (epoch === generation) error.value = failure.message || "读取任务失败";
      } finally {
        if (epoch === generation) loading.value = false;
      }
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
          operation === selectedOperation.value
        )
          code.value = result;
      } catch (failure: any) {
        if (operation === selectedOperation.value)
          codeError.value = failure.message || "源码尚未归档";
      }
    }
    async function loadDiff() {
      const previous = previousVersion.value,
        operation = selectedOperation.value;
      if (!previous) return;
      try {
        const result = await fetchCodingCode(props.runId, selectedId.value, previous.operationId);
        if (operation === selectedOperation.value) {
          previousCode.value = result.content;
          showDiff.value = true;
        }
      } catch (failure: any) {
        codeError.value = failure.message;
      }
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
