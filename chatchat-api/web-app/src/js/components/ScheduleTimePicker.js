import { Clock3 } from "@lucide/vue";

function twoDigits(value) {
  return String(Number(value) || 0).padStart(2, "0");
}

function normalizedTime(value) {
  const match = String(value || "").match(/^(\d{1,2}):(\d{1,2})$/);
  if (!match) {
    return "00:00";
  }
  const hour = Math.min(23, Math.max(0, Number(match[1])));
  const minute = Math.min(59, Math.max(0, Number(match[2])));
  return `${twoDigits(hour)}:${twoDigits(minute)}`;
}

export default {
  name: "ScheduleTimePicker",
  components: { Clock3 },
  props: {
    modelValue: {
      type: String,
      default: "00:00"
    },
    disabled: {
      type: Boolean,
      default: false
    },
    minuteStep: {
      type: Number,
      default: 1
    },
    ariaLabel: {
      type: String,
      default: "时间"
    }
  },
  emits: ["update:modelValue"],
  data() {
    return { open: false };
  },
  computed: {
    displayValue() {
      return normalizedTime(this.modelValue);
    },
    selectedHour() {
      return this.displayValue.slice(0, 2);
    },
    selectedMinute() {
      return this.displayValue.slice(3, 5);
    },
    hours() {
      return Array.from({ length: 24 }, (_, index) => twoDigits(index));
    },
    minutes() {
      const step = Math.min(30, Math.max(1, Math.round(this.minuteStep || 1)));
      return Array.from({ length: Math.ceil(60 / step) }, (_, index) => twoDigits(index * step));
    }
  },
  mounted() {
    document.addEventListener("pointerdown", this.closeFromOutside, true);
    document.addEventListener("keydown", this.closeFromKeyboard);
  },
  beforeUnmount() {
    document.removeEventListener("pointerdown", this.closeFromOutside, true);
    document.removeEventListener("keydown", this.closeFromKeyboard);
  },
  methods: {
    toggle() {
      if (this.disabled) {
        return;
      }
      this.open = !this.open;
      if (this.open) {
        this.$nextTick(this.scrollSelectedIntoView);
      }
    },
    selectHour(hour) {
      this.$emit("update:modelValue", `${hour}:${this.selectedMinute}`);
    },
    selectMinute(minute) {
      this.$emit("update:modelValue", `${this.selectedHour}:${minute}`);
      this.open = false;
    },
    closeFromOutside(event) {
      if (this.open && this.$refs.root && !this.$refs.root.contains(event.target)) {
        this.open = false;
      }
    },
    closeFromKeyboard(event) {
      if (event.key === "Escape") {
        this.open = false;
      }
    },
    scrollSelectedIntoView() {
      this.$refs.root?.querySelectorAll(".schedule-time-column .selected").forEach((element) => {
        element.scrollIntoView({ block: "center" });
      });
    }
  }
};
