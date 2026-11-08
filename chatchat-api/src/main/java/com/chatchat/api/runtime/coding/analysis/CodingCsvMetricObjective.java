package com.chatchat.api.runtime.coding.analysis;

import java.util.List;

/** Requirements for the opt-in metric contract; no program, tool sequence or repair implementation. */
final class CodingCsvMetricObjective {
    private CodingCsvMetricObjective(){}
    static String objective(String original,String field){
        return original+"\nExplicit verification scope: parsed CSV data-row count, exact decimal sum of column "
            +field+", and binding to the original CSV SHA-256. These are technical measurements, not financial meanings. "
            +"Runtime independently recomputes both facts from immutable original bytes. "
            +"Write one rows.jsonl report with the complete delivered row schema including verifiedSummary. "
            +"reportMarkdown is the readable report; analysisStatus describes the requested deliverables. "
            +"fieldProvenance describes the literal numeric column explicitly selected by this contract, with no invented domain interpretation. "
            +"The exact original CSV is already delivered in nativeFiles and verified by Runtime. "
            +"Use its Native nativePath for Native reads and virtualPath for Managed Python. "
            +"Transport manifests, Parquet parts and Base64 envelopes are not additional business datasets and do not need reconstruction. "
            +"General Skill transport-recovery instructions apply only when original nativeFiles are unavailable. "
            +"Preserve real failure and repair receipts; a model response or a zero exit code does not establish delivery.";
    }
    static String appendix(String objective,List<String> references){
        return objective+"\nUse the authorized input inventory and exact output schema delivered in Runtime context. "
            +"Evidence references are exactly "+references+". Runtime has already verified immutable delivery; do not duplicate transport verification in the analytical program. "
            +"Use installed Python packages from runtimeContext.pythonEnvironment. No network, dependency installation, source-data changes or local shell execution is authorized.";
    }
}
