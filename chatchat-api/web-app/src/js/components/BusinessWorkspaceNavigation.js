import { BookOpen, BriefcaseBusiness, FolderOpen, MessageSquare, Plus, Sparkles } from "@lucide/vue";


const entries = [
  { id: "chat", label: "智能对话", icon: MessageSquare },
  { id: "skills", label: "技能", icon: Sparkles },
  { id: "guides", label: "岗位工作", icon: BriefcaseBusiness },
  { id: "knowledge", label: "知识中心", icon: BookOpen },
  { id: "outcomes", label: "工作成果", icon: FolderOpen },
];

export default {
  name: "BusinessWorkspaceNavigation",
  components: { Plus },
  props: { panel: String, collapsed: Boolean },
  emits: ["select", "new-conversation"],
  setup: () => ({ entries }),
};
