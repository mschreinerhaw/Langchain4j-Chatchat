<template>
  <div ref="root" class="schedule-time-picker" :class="{ open, disabled }">
    <button
      type="button"
      class="schedule-time-trigger"
      :disabled="disabled"
      :aria-label="ariaLabel"
      :aria-expanded="open"
      @click="toggle"
    >
      <strong>{{ displayValue }}</strong>
      <Clock3 :size="18" aria-hidden="true" />
    </button>
    <div v-if="open" class="schedule-time-popover" role="dialog" :aria-label="`${ariaLabel}选择器`">
      <header>
        <span>小时</span>
        <span>分钟</span>
      </header>
      <div class="schedule-time-columns">
        <div class="schedule-time-column" role="listbox" aria-label="小时">
          <button
            v-for="hour in hours"
            :key="hour"
            type="button"
            :class="{ selected: hour === selectedHour }"
            :aria-selected="hour === selectedHour"
            @click="selectHour(hour)"
          >
            {{ hour }}
          </button>
        </div>
        <div class="schedule-time-column" role="listbox" aria-label="分钟">
          <button
            v-for="minute in minutes"
            :key="minute"
            type="button"
            :class="{ selected: minute === selectedMinute }"
            :aria-selected="minute === selectedMinute"
            @click="selectMinute(minute)"
          >
            {{ minute }}
          </button>
        </div>
      </div>
      <footer>
        <span>已选择 {{ displayValue }}</span>
        <button type="button" @click="open = false">完成</button>
      </footer>
    </div>
  </div>
</template>

<script src="../js/components/ScheduleTimePicker.js"></script>
