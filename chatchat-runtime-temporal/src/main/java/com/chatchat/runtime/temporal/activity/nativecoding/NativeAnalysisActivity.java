package com.chatchat.runtime.temporal.activity.nativecoding;
import com.chatchat.runtime.temporal.contract.nativecoding.*;
import io.temporal.activity.*;
@ActivityInterface
public interface NativeAnalysisActivity {
    @ActivityMethod(name="RuntimeNativeAnalysisRun.v1") NativeAnalysisResult run(NativeAnalysisReference reference);
    @ActivityMethod(name="RuntimeNativeAnalysisStop.v1") NativeAnalysisResult stop(NativeAnalysisReference reference,String reason);
}
