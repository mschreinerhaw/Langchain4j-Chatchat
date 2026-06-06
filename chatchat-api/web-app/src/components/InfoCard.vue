<template>
  <section class="info-card">
    <header>
      <h2>{{ title }}</h2>
      <button v-if="action" type="button">{{ action }} <span></span></button>
    </header>
    <div class="info-card-body">
      <slot />
    </div>
  </section>
</template>

<script>
export default {
  name: "InfoCard",
  props: {
    title: {
      type: String,
      required: true
    },
    action: {
      type: String,
      default: ""
    }
  }
};
</script>
