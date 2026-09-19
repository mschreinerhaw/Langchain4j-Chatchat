package com.chatchat.runtime.news.temporal.activity;

import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface NewsCollectionActivity {
    @ActivityMethod(name = "CollectNewsSource")
    NewsCollectionWorkflowResult collect(Long sourceId);
}
