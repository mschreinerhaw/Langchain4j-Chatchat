package com.chatchat.common.runtime.summary;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;

/** Map-reduce boundary that combines immutable worker summaries without knowing their transport. */
public interface ModelSummaryReducer<S extends ModelSummary, C, R> extends RuntimeProtocolPort {

    R reduce(C context, List<S> summaries);
}
