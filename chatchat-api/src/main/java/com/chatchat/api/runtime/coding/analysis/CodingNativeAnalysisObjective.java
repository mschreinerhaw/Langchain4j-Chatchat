package com.chatchat.api.runtime.coding.analysis;

import java.util.Collection;

/** Native task requirements, not a coding plan, tool sequence or repair algorithm. */
final class CodingNativeAnalysisObjective {
    private CodingNativeAnalysisObjective() { }
    static String compose(String userObjective,Collection<String> tools,boolean visual,boolean textResult){
        String core=userObjective+"\n\nRuntime contract: use only the frozen authorized inputs and tools. "
            +"The complete input inventory, exact output row schema, field/source declarations and environment are in runtime-inputs.json. "
            +"Native reads use nativePath; Managed Python uses virtualPath under /data/input. datasetReference is an opaque logical reference, never a file path. "
            +"Original nativeFiles are business inputs; transport parts and Base64 records are not extra business datasets. "
            +"Preserve missing/null, declared units, exact identifiers and numeric precision. Never invent values or infer business roles from column positions. "
            +"An empty dataset, missing authorization, invalid format and failed transfer are different outcomes; report the actual evidence and limitations. "
            +"Use only installed packages and the authorized Sandbox; no dependency installation, direct network or local shell. "
            +"Choose applicable Skills and coding/repair tools yourself. On a real failure inspect executionFeedback and repair only within Runtime policy; STOP and unresolved operations cannot be replayed. "
            +"Deliver the exact required files, complete row schema and supported field provenance. Review the actual archived result before official submit_and_exit. "
            +"Native completion, process exit zero and structural validation do not establish business correctness; Runtime independently decides delivery status.";
        if(!textResult)core+="\nThis is a computation task: actual Managed Python execution and the contracted rows.jsonl report are required; prose alone is not delivery.";
        if(visual)core+="\nVisual delivery: follow the delivered visualReport schema and required artifacts; charts must use computed data and real archived file references.";
        if(tools.stream().anyMatch(t->t.startsWith("market_")||t.startsWith("news_")))core+="\nResource scope: discover authorized datasets and coverage. Verify security candidates against real observations; if needed use the authorized complete daily snapshot. Report actual as-of dates and distinguish facts from analysis. Empty matches do not prove all data absent.";
        if(tools.contains("web_search"))core+="\nInternet scope: only the model-authorized server tools may search public data. Check actual availability, cite returned sources and dates, and never send private input data to public search.";
        return core;
    }
}
