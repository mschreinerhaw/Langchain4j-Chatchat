import {
  createSkill,
  deleteSkill,
  fetchSkills,
  fetchSkillVersions,
  fetchToolNames,
  rollbackSkillVersion,
  updateSkill
} from "../../services/api.js";
import "../../styles/pages/capability-market.css";

function uniqueList(values) {
  return [...new Set(values.map((value) => String(value || "").trim()).filter(Boolean))];
}

function parseList(value) {
  if (Array.isArray(value)) {
    return uniqueList(value);
  }
  return uniqueList(String(value || "").split(/[\n,，]/));
}

function joinList(values) {
  return Array.isArray(values) ? values.join("\n") : "";
}

function defaultRoutingSettings() {
  return {
    smartSelectionEnabled: true,
    limitParallelCalls: true,
    maxParallelCalls: 3
  };
}

function emptyForm() {
  return {
    value: "",
    label: "",
    description: "",
    usageScenarios: "",
    skillTags: "",
    defaultMode: "",
    systemPrompt: "",
    firstUseGreeting: "",
    preferredToolPrefixes: "",
    boundMcpServiceIds: "",
    boundMcpToolNames: "",
    toolConfigs: [],
    routingSettings: defaultRoutingSettings(),
    quickQuestions: ""
  };
}

export default {
  name: "CapabilityMarketView",
  data() {
    return {
      skills: [],
      tools: [],
      versions: [],
      loading: false,
      saving: false,
      versionLoading: false,
      settingsOpen: false,
      dialogMode: "edit",
      activeSkill: null,
      form: emptyForm(),
      skillTotal: 0,
      allSkillCount: 0,
      categoryOptionsData: [],
      searchQuery: "",
      categoryFilter: "all",
      page: 1,
      pageSize: 6,
      pageCount: 1,
      error: "",
      dialogError: ""
    };
  },
  computed: {
    filteredSkills() {
      return this.skills;
    },
    categoryOptions() {
      return this.categoryOptionsData.length
        ? this.categoryOptionsData
        : [{ value: "all", label: "全部业务", count: this.allSkillCount }];
    },
    pagedSkills() {
      return this.skills;
    },
    pageButtons() {
      const total = this.pageCount;
      const current = Math.min(Math.max(1, this.page), total);
      const start = Math.max(1, Math.min(current - 2, total - 4));
      const end = Math.min(total, start + 4);
      return Array.from({ length: end - start + 1 }, (_, index) => start + index);
    },
    pageStart() {
      if (!this.filteredSkills.length) {
        return 0;
      }
      return (Math.min(Math.max(1, this.page), this.pageCount) - 1) * this.pageSize + 1;
    },
    pageEnd() {
      return Math.min(this.page * this.pageSize, this.skillTotal);
    },
    selectedToolNames() {
      return parseList(this.form.boundMcpToolNames);
    },
    visibleTools() {
      const selected = new Set(this.selectedToolNames);
      const selectedTools = this.tools.filter((tool) => selected.has(tool));
      const remainingTools = this.tools.filter((tool) => !selected.has(tool)).slice(0, 24);
      return [...selectedTools, ...remainingTools];
    }
  },
  watch: {
    searchQuery() {
      this.page = 1;
      this.loadSkills();
    },
    categoryFilter() {
      this.page = 1;
      this.loadSkills();
    },
  },
  mounted() {
    this.loadSkills();
    this.loadTools();
  },
  methods: {
    async loadSkills() {
      this.loading = true;
      this.error = "";
      try {
        const payload = await fetchSkills({
          scope: "published",
          keyword: this.searchQuery.trim(),
          category: this.categoryFilter,
          page: this.page,
          pageSize: this.pageSize
        });
        this.skills = Array.isArray(payload?.items) ? payload.items : (Array.isArray(payload) ? payload : []);
        this.skillTotal = payload?.total ?? this.skills.length;
        this.page = payload?.page || this.page;
        this.pageSize = payload?.pageSize || this.pageSize;
        this.pageCount = payload?.totalPages || 1;
        this.categoryOptionsData = Array.isArray(payload?.categories) ? payload.categories : [];
        this.allSkillCount = this.categoryOptions.find((option) => option.value === "all")?.count ?? this.skillTotal;
        this.normalizeCategoryFilter();
      } catch (error) {
        this.error = error.message || "能力列表加载失败";
      } finally {
        this.loading = false;
      }
    },
    async loadTools() {
      try {
        const tools = await fetchToolNames();
        this.tools = Array.isArray(tools) ? tools : [];
      } catch (error) {
        this.tools = [];
      }
    },
    skillBadge(skill) {
      return String(skill?.label || skill?.value || "能").slice(0, 1).toUpperCase();
    },
    businessTypeLabel(skill) {
      const tags = Array.isArray(skill?.skillTags) ? skill.skillTags : [];
      if (tags.length) {
        return tags.slice(0, 2).join(" / ");
      }
      return skill?.builtin ? "平台预置能力" : "业务自定义能力";
    },
    selectCategory(category) {
      this.categoryFilter = category || "all";
    },
    normalizeCategoryFilter() {
      if (!this.categoryOptions.some((option) => option.value === this.categoryFilter)) {
        this.categoryFilter = "all";
      }
      this.clampPage();
    },
    goPage(page) {
      this.page = Math.min(Math.max(1, Number(page) || 1), this.pageCount);
      this.loadSkills();
    },
    clampPage() {
      if (this.page > this.pageCount) {
        this.page = this.pageCount;
      }
      if (this.page < 1) {
        this.page = 1;
      }
    },
    businessScenarios(skill) {
      const scenarios = Array.isArray(skill?.usageScenarios) ? skill.usageScenarios : [];
      return scenarios.slice(0, 3);
    },
    skillSearchText(skill) {
      const fields = [
        skill?.value,
        skill?.label,
        skill?.description,
        skill?.defaultMode,
        ...(skill?.usageScenarios || []),
        ...(skill?.skillTags || []),
        ...(skill?.quickQuestions || []),
        ...(skill?.preferredToolPrefixes || []),
        ...(skill?.boundMcpServiceIds || []),
        ...(skill?.boundMcpToolNames || []),
        ...(skill?.toolConfigs || []).flatMap((config) => [
          config?.toolName,
          config?.displayName,
          config?.description,
          ...(config?.tags || [])
        ])
      ];
      return fields.map((field) => String(field || "").toLowerCase()).join(" ");
    },
    toolCountLabel(skill) {
      const toolNames = new Set([
        ...(skill?.boundMcpToolNames || []),
        ...(skill?.toolConfigs || [])
          .filter((config) => config?.enabled !== false && config?.toolName)
          .map((config) => config.toolName)
      ]);
      const serviceCount = Array.isArray(skill?.boundMcpServiceIds) ? skill.boundMcpServiceIds.length : 0;
      if (toolNames.size === 0 && serviceCount === 0) {
        return "0 个";
      }
      return `${toolNames.size} 个`;
    },
    serviceCountLabel(skill) {
      const serviceCount = Array.isArray(skill?.boundMcpServiceIds) ? skill.boundMcpServiceIds.length : 0;
      return `${serviceCount} 个`;
    },
    openCreateDialog() {
      this.dialogMode = "create";
      this.activeSkill = null;
      this.form = emptyForm();
      this.versions = [];
      this.dialogError = "";
      this.settingsOpen = true;
    },
    openSettingsDialog(skill) {
      this.dialogMode = "edit";
      this.activeSkill = skill;
      this.form = this.skillToForm(skill);
      this.dialogError = "";
      this.settingsOpen = true;
      this.loadVersions(skill.value);
    },
    closeSettingsDialog() {
      if (this.saving) {
        return;
      }
      this.settingsOpen = false;
      this.dialogError = "";
      this.activeSkill = null;
      this.form = emptyForm();
      this.versions = [];
    },
    skillToForm(skill) {
      return {
        value: skill?.value || "",
        label: skill?.label || "",
        description: skill?.description || "",
        usageScenarios: joinList(skill?.usageScenarios),
        skillTags: joinList(skill?.skillTags),
        defaultMode: skill?.defaultMode || "",
        systemPrompt: skill?.systemPrompt || "",
        firstUseGreeting: skill?.firstUseGreeting || "",
        preferredToolPrefixes: joinList(skill?.preferredToolPrefixes),
        boundMcpServiceIds: joinList(skill?.boundMcpServiceIds),
        boundMcpToolNames: joinList(skill?.boundMcpToolNames),
        toolConfigs: Array.isArray(skill?.toolConfigs) ? skill.toolConfigs : [],
        routingSettings: {
          ...defaultRoutingSettings(),
          ...(skill?.routingSettings || {})
        },
        quickQuestions: joinList(skill?.quickQuestions)
      };
    },
    formToPayload() {
      const maxParallelCalls = Number(this.form.routingSettings.maxParallelCalls) || 3;
      const selectedToolNames = parseList(this.form.boundMcpToolNames);
      return {
        value: this.form.value,
        label: this.form.label,
        description: this.form.description,
        usageScenarios: parseList(this.form.usageScenarios),
        skillTags: parseList(this.form.skillTags),
        defaultMode: this.form.defaultMode,
        systemPrompt: this.form.systemPrompt,
        firstUseGreeting: this.form.firstUseGreeting,
        preferredToolPrefixes: parseList(this.form.preferredToolPrefixes),
        boundMcpServiceIds: parseList(this.form.boundMcpServiceIds),
        boundMcpToolNames: selectedToolNames,
        toolConfigs: this.buildToolConfigs(selectedToolNames),
        routingSettings: {
          smartSelectionEnabled: !!this.form.routingSettings.smartSelectionEnabled,
          limitParallelCalls: !!this.form.routingSettings.limitParallelCalls,
          maxParallelCalls: Math.max(1, Math.min(10, maxParallelCalls))
        },
        quickQuestions: parseList(this.form.quickQuestions)
      };
    },
    buildToolConfigs(selectedToolNames) {
      const existingConfigs = Array.isArray(this.form.toolConfigs) ? this.form.toolConfigs : [];
      const selected = new Set(selectedToolNames);
      if (existingConfigs.length === 0) {
        return [];
      }
      const byName = new Map(existingConfigs.filter((config) => config?.toolName).map((config) => [config.toolName, config]));
      const mergedNames = uniqueList([...selectedToolNames, ...existingConfigs.map((config) => config?.toolName)]);
      return mergedNames.map((toolName) => {
        const existing = byName.get(toolName) || {};
        return {
          ...existing,
          toolName,
          displayName: existing.displayName || toolName,
          callWeight: existing.callWeight ?? 5,
          enabled: selected.has(toolName)
        };
      });
    },
    async saveSettings() {
      this.dialogError = "";
      if (!this.form.value || !/^[a-z0-9_-]{2,64}$/.test(this.form.value)) {
        this.dialogError = "能力 ID 仅支持小写字母、数字、下划线和短横线，长度 2-64。";
        return;
      }
      if (!this.form.label) {
        this.dialogError = "请填写能力名称。";
        return;
      }

      this.saving = true;
      try {
        const payload = this.formToPayload();
        const saved = this.dialogMode === "create"
          ? await createSkill(payload)
          : await updateSkill(this.activeSkill.value, payload);
        this.upsertLocalSkill(saved);
        this.settingsOpen = false;
        this.dialogError = "";
        this.activeSkill = null;
        this.form = emptyForm();
        this.versions = [];
      } catch (error) {
        this.dialogError = error.message || "能力设置保存失败";
      } finally {
        this.saving = false;
      }
    },
    upsertLocalSkill(skill) {
      if (!skill?.value) {
        return;
      }
      const nextSkills = this.skills.filter((item) => item.value !== skill.value);
      this.skills = [...nextSkills, skill].sort((left, right) => {
        if (left.builtin !== right.builtin) {
          return left.builtin ? -1 : 1;
        }
        return String(left.label || left.value).localeCompare(String(right.label || right.value), "zh-CN");
      });
    },
    async removeSkill(skill) {
      if (!skill?.value || skill.builtin) {
        return;
      }
      if (!window.confirm(`确认删除能力「${skill.label || skill.value}」？`)) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        await deleteSkill(skill.value);
        this.skills = this.skills.filter((item) => item.value !== skill.value);
      } catch (error) {
        this.error = error.message || "能力删除失败";
      } finally {
        this.saving = false;
      }
    },
    async loadVersions(skillId) {
      if (!skillId) {
        return;
      }
      this.versionLoading = true;
      try {
        const versions = await fetchSkillVersions(skillId);
        this.versions = Array.isArray(versions) ? versions : [];
      } catch (error) {
        this.versions = [];
      } finally {
        this.versionLoading = false;
      }
    },
    async rollbackVersion(version) {
      if (!this.activeSkill?.value || !version?.id) {
        return;
      }
      if (!window.confirm("确认回滚到该版本？当前配置会先自动保存为回滚前版本。")) {
        return;
      }
      this.saving = true;
      this.dialogError = "";
      try {
        const saved = await rollbackSkillVersion(this.activeSkill.value, version.id);
        this.upsertLocalSkill(saved);
        this.activeSkill = saved;
        this.form = this.skillToForm(saved);
        await this.loadVersions(saved.value);
      } catch (error) {
        this.dialogError = error.message || "版本回滚失败";
      } finally {
        this.saving = false;
      }
    },
    toggleTool(toolName) {
      const selected = new Set(this.selectedToolNames);
      if (selected.has(toolName)) {
        selected.delete(toolName);
      } else {
        selected.add(toolName);
      }
      this.form.boundMcpToolNames = [...selected].sort().join("\n");
    },
    formatVersionTime(timestamp) {
      if (!timestamp) {
        return "未知时间";
      }
      return new Date(timestamp).toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
      });
    }
  }
};
