package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.mcpserver.python.PythonMcpToolPublisher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateQueryParentCatalogTest {

    @Test
    void exposesPythonAnalysisAsAParentTemplateQueryTool() {
        TemplateQueryParentCatalog.ParentTool parent = new TemplateQueryParentCatalog()
            .require(PythonMcpToolPublisher.ANALYSIS_RUN_TOOL);

        assertThat(parent.assetType()).isEqualTo(TemplateAssetCatalogService.PYTHON);
        assertThat(parent.title()).isEqualTo("Python 分析模板检索");
    }
}
