package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;

@FunctionalInterface
public interface NewsToolExecutor {
    ToolOutput execute(ToolInput input);
}
