const scheduleFields = ["effectiveAt", "expiresAt"];

export function localIdentitySchedule(identity = {}) {
  return Object.fromEntries(scheduleFields.map(field => {
    const date = identity[field] ? new Date(identity[field]) : null;
    if (!date || Number.isNaN(date.getTime())) return [field, ""];
    const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
    return [field, local.toISOString().slice(0, 16)];
  }));
}

export function identityScheduleChanges(form, original) {
  return Object.fromEntries(scheduleFields
    .filter(field => form[field] !== original[field])
    .map(field => [field, form[field] ? new Date(form[field]).toISOString() : null]));
}
