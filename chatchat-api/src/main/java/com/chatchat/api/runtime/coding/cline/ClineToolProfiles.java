package com.chatchat.api.runtime.coding.cline;

import java.util.Set;

/** One Java capability profile for dispatch and model callback admission. */
final class ClineToolProfiles {
    static final Set<String> ANALYSIS=Set.of("python_execute","artifact_read","data_request","sql_validate","skill_read","input_read","report_submit");
    private ClineToolProfiles() { }
}
