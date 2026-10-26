/** runtime_os.execution_feedback.v1; Java is the authority for repair admission. */
export interface ExecutionFeedback {
  script?: {fileName: string; scriptPath: string; workingDirectory: string; scriptVersion: number} | null;
  schemaVersion: 'runtime_os.execution_feedback.v1';
  taskId: string; executionId: string; attemptId: string; traceId: string;
  status: string; stage: string; exitCode: number | null; durationMs: number | null; codeHash: string;
  cpuTimeMs?: number | null; maxRssBytes?: number | null;
  stdout: string; stderr: string; diagnosticPolicy: 'METADATA_ONLY_V1';
  stdoutBytes: number | null; stderrBytes: number | null;
  error: null | { category: string; exceptionType: string | null; diagnosticCode: string; message: string;
    stack: {file: string; line: number; column: number | null}[]; detailRef: string };
  inputDataRefs: string[]; schemaRef: string; artifactRefs: Record<string,string>;
  inputFiles: {fileId: string; fileName: string; sha256: string}[]; inputSchemaSummary: Record<string,string>;
  validationStatus: string; resultAvailable: boolean; manifestHash: string;
  outputStatus: string | null; businessValidationPassed: boolean;
  repair: { allowed: boolean; action: 'REPAIR_IN_CURRENT_CONTEXT' | 'COMPLETE' | 'STOP'; reason: string;
    attemptsUsed: number; maxAttempts: number; baseCodeHash: string; guidance: string };
}
