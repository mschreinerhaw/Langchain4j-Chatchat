package com.chatchat.common.runtime.coding;

import java.util.List;
import java.util.Map;

/** Additive tool-result contract. All text is server-normalized; never raw customer streams. */
public record ExecutionFeedback(String schemaVersion, String taskId, String executionId, String attemptId,
        String traceId, String status, String stage, Integer exitCode, Long durationMs, String codeHash,
        String stdout, String stderr, Long stdoutBytes, Long stderrBytes, Failure error, List<String> inputDataRefs, String schemaRef,
        List<InputFile> inputFiles, Map<String,String> inputSchemaSummary,
        Map<String,String> artifactRefs, String validationStatus, boolean resultAvailable,
        String manifestHash, String outputStatus, boolean businessValidationPassed,
        Repair repair, String diagnosticPolicy, CodingScript script) {
    public static final String VERSION = "runtime_os.execution_feedback.v1";
    public ExecutionFeedback {
        CodingProtocol.require(VERSION.equals(schemaVersion),"Unknown execution feedback version");
        taskId=CodingProtocol.ref(taskId,"taskId");executionId=CodingProtocol.ref(executionId,"executionId");
        attemptId=CodingProtocol.ref(attemptId,"attemptId");traceId=CodingProtocol.ref(traceId,"traceId");
        CodingProtocol.require(java.util.Set.of("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT").contains(status),"Stopped execution status required");
        CodingProtocol.require(codeHash!=null&&codeHash.matches("[0-9a-f]{64}"),"Code hash required");
        CodingProtocol.require(durationMs==null||durationMs>=0,"Execution duration cannot be negative");
        CodingProtocol.require("METADATA_ONLY_V1".equals(diagnosticPolicy)&&"[raw stdout omitted]".equals(stdout),"Raw streams are forbidden in execution feedback");
        inputDataRefs=List.copyOf(inputDataRefs);inputFiles=List.copyOf(inputFiles);
        inputSchemaSummary=Map.copyOf(inputSchemaSummary);artifactRefs=Map.copyOf(artifactRefs);
    }
    public record Frame(String file, int line, Integer column) {
        public Frame { CodingProtocol.require(CodingScript.safeFileName(file)&&line>0&&line<100_000_000,"Invalid normalized source frame"); }
    }
    public record InputFile(String fileId,String fileName,String sha256) { }
    public record Failure(String category, String exceptionType, String diagnosticCode,
                          String message, List<Frame> stack, String detailRef) {
        public Failure { stack=List.copyOf(stack);CodingProtocol.require(stack.size()<=16,"Diagnostic stack limit"); }
    }
    public record Repair(boolean allowed, String action, String reason, int attemptsUsed,
                         int maxAttempts, String baseCodeHash, String guidance) { }
}
