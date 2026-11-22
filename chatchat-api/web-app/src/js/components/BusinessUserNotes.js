import MarkdownIt from "markdown-it";
import AppConfirmDialog from "../../components/AppConfirmDialog.vue";
import { apiRequest } from "../../services/api";
import { useAppConfirmation } from "../utils/useAppConfirmation";

const markdown = new MarkdownIt({ html: false, breaks: true }).disable("image");
const formats = [
  { name: "标题", label: "H", prefix: "## ", suffix: "", sample: "小标题", block: true },
  { name: "粗体", label: "B", prefix: "**", suffix: "**", sample: "重点" },
  { name: "斜体", label: "I", prefix: "*", suffix: "*", sample: "文字" },
  { name: "项目列表", label: "列表", prefix: "- ", suffix: "", sample: "项目", block: true },
  { name: "引用", label: "引用", prefix: "> ", suffix: "", sample: "引用内容", block: true },
  { name: "代码块", label: "代码", prefix: "```\n", suffix: "\n```", sample: "代码", block: true },
];

export default {
  components: { AppConfirmDialog },
  props: { panel: { type: String, required: true }, workspaceContext: Object },
  setup: useAppConfirmation,
  data: () => ({
    formats, notes: [], roles: [], active: null, selectedRole: "", preview: false, editing: false, savedText: "",
    page: 0, hasMore: false, busy: false, saving: false, error: "", notice: "",
  }),
  computed: {
    path() { return "/workspaces/business/notes/" + this.panel; },
    renderedBody() { return markdown.render(this.active?.body || ""); },
    articleTitle() { return this.active?.publishedTitle ?? this.active?.title; },
    articleBody() { return markdown.render(this.active?.publishedBody ?? this.active?.body ?? ""); },
    draftText() { return JSON.stringify([this.active?.title, this.active?.body]); },
  },
  mounted() { this.load(0); },
  methods: {
    async load(page) {
      if (this.busy) return;
      this.busy = true;
      try {
        const result = await apiRequest(this.path + "?page=" + page, { workspaceContext: this.workspaceContext });
        this.notes = result.notes;
        this.roles = result.roles;
        this.page = result.page;
        this.hasMore = result.hasMore;
        if (!this.active) {
          const article = this.notes.find(note => note.published);
          if (article) this.setActive(article);
        }
      } catch (error) {
        this.error = error.message;
      } finally {
        this.busy = false;
      }
    },
    async open(note) {
      if (this.active?.owned && this.draftText !== this.savedText
        && !await this.confirmAction("当前笔记还有未保存的修改。", { title: "切换笔记", confirmLabel: "放弃修改" })) return;
      this.setActive(note);
      if (!note) this.$nextTick(() => this.$refs.titleInput?.focus());
    },
    setActive(note) {
      this.active = note ? { ...note } : { title: "", body: "", owned: true };
      this.savedText = this.draftText;
      this.selectedRole = this.roles.find(role => role.id === note?.roleId)?.id
        || (this.roles.length === 1 ? this.roles[0].id : "");
      this.preview = false;
      this.editing = this.active.owned && !this.active.published;
      this.error = "";
      this.notice = "";
    },
    startEditing() {
      this.editing = true;
      this.notice = "";
      this.$nextTick(() => this.$refs.titleInput?.focus());
    },
    async readArticle() {
      const [title, body] = JSON.parse(this.savedText);
      await this.open({ ...this.active, title, body });
    },
    applyFormat(format) {
      const editor = this.$refs.bodyInput;
      if (!editor) return;
      const { selectionStart: start, selectionEnd: end } = editor;
      const text = this.active.body;
      const prefix = format.block && start > 0 && text[start - 1] !== "\n" ? "\n" + format.prefix : format.prefix;
      const selected = text.slice(start, end) || format.sample;
      const formatted = text.slice(0, start) + prefix + selected + format.suffix + text.slice(end);
      if (formatted.length > 20000) return;
      this.active.body = formatted;
      this.$nextTick(() => {
        editor.focus();
        editor.setSelectionRange(start + prefix.length, start + prefix.length + selected.length);
      });
    },
    async save(publish) {
      if (this.saving || this.busy || !this.active?.owned) return;
      this.saving = true;
      this.error = "";
      try {
        const { id, title, body, revision } = this.active;
        const noteId = await apiRequest(this.path + (id ? "/" + encodeURIComponent(id) : ""), {
          method: id ? "PUT" : "POST", workspaceContext: this.workspaceContext,
          body: JSON.stringify({ title, body, revision, publishTo: publish ? this.selectedRole : null }),
        });
        const roleId = publish ? this.selectedRole : this.active.roleId;
        this.setActive({ ...this.active, id: noteId, title: title.trim(), revision: id ? revision + 1 : 0,
          roleId, published: publish || this.active.published,
          publishedTitle: publish ? title.trim() : this.active.publishedTitle,
          publishedBody: publish ? body : this.active.publishedBody });
        if (!publish) this.editing = true;
        this.notice = publish ? "笔记已发布。" : "草稿已保存。";
        await this.load(0);
      } catch (error) {
        this.error = error.message;
      } finally {
        this.saving = false;
      }
    },
    async remove() {
      if (this.saving || this.busy || !this.active?.owned || !this.active.id) return;
      if (!await this.confirmAction("删除后，角色成员也将无法查看这条笔记。", {
        title: "删除笔记", confirmLabel: "删除笔记", tone: "danger",
      })) return;
      this.saving = true;
      try {
        await apiRequest(this.path + "/" + encodeURIComponent(this.active.id) + "?revision=" + this.active.revision, {
          method: "DELETE", workspaceContext: this.workspaceContext,
        });
        this.active = null;
        this.notice = "";
        this.error = "";
        await this.load(0);
      } catch (error) {
        this.error = error.message;
      } finally {
        this.saving = false;
      }
    },
  },
};
