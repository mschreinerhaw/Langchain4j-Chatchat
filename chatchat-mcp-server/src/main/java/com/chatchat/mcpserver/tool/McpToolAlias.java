package com.chatchat.mcpserver.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mcp_tool_alias")
public class McpToolAlias {

    @Id
    @Column(name = "lookup_key", length = 256)
    private String lookupKey;

    @Column(name = "chinese_alias", nullable = false, length = 128)
    private String chineseAlias;

    protected McpToolAlias() {
    }

    public McpToolAlias(String lookupKey, String chineseAlias) {
        this.lookupKey = lookupKey;
        this.chineseAlias = chineseAlias;
    }

    public String getLookupKey() { return lookupKey; }
    public String getChineseAlias() { return chineseAlias; }
    public void setChineseAlias(String chineseAlias) { this.chineseAlias = chineseAlias; }
}
