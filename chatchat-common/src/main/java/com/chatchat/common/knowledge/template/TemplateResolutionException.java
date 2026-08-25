package com.chatchat.common.knowledge.template;

/** Backward-compatible IllegalArgumentException carrying a structured recovery event. */
public final class TemplateResolutionException extends IllegalArgumentException {
    private final TemplateResolutionEvent event;

    public TemplateResolutionException(TemplateResolutionEvent event) {
        super(event == null ? "Template resolution failed" : event.message());
        if (event == null) throw new IllegalArgumentException("template resolution event is required");
        this.event = event;
    }

    public TemplateResolutionEvent event() { return event; }
}
