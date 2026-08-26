package com.chatchat.common.runtime.summary;

/** Minimal inference boundary hiding the concrete model SDK, router and remote provider. */
@FunctionalInterface
public interface ModelSummaryModel {
    String generate(String instruction);
}
