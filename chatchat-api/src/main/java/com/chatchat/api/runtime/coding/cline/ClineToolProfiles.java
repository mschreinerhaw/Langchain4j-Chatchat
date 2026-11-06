package com.chatchat.api.runtime.coding.cline;

import java.util.Set;

/** One Java capability profile for dispatch and model callback admission. */
final class ClineToolProfiles {
    static final Set<String> ANALYSIS=Set.of("python_execute","artifact_read","data_request","sql_validate","skill_read","skills","read_files","input_read","report_submit","code_read",
        "knowledge_search","knowledge_get_document","knowledge_read","knowledge_list_sections","knowledge_related",
        "news_search","news_get_article","news_related_events","market_search","market_get_history","market_get_indicators","market_get_snapshot","market_resolve_security","market_get_day_snapshot","web_search","web_fetch");
    private ClineToolProfiles() { }
}
