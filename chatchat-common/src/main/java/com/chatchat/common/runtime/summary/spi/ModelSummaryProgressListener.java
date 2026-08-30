package com.chatchat.common.runtime.summary.spi;

import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;

/** Driver-side sink for durable Worker progress and heartbeat events. */
@FunctionalInterface
public interface ModelSummaryProgressListener {

    ModelSummaryProgressListener NOOP = progress -> { };

    void onProgress(ModelSummaryProgress progress);
}
