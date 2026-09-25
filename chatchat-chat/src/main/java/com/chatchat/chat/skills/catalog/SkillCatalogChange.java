package com.chatchat.chat.skills.catalog;

/** Reconcile the local Agent registry only after a catalog transaction commits. */
public record SkillCatalogChange(String skillId, boolean deleted) { }
