package com.chatchat.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Tool parameter definition with validation support
 *
 * Represents a single parameter that a tool accepts, including type,
 * constraints, and validation rules. Compatible with LangChain4j tool definitions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolParameter {

    /**
     * Parameter name
     */
    private String name;

    /**
     * Parameter type (e.g., "string", "number", "boolean", "array")
     */
    private String type;

    /**
     * Parameter description for the LLM
     */
    private String description;

    /**
     * Whether this parameter is required
     */
    private boolean required;

    /**
     * Default value if not provided
     */
    private Object defaultValue;

    /**
     * Enumeration of allowed values (optional)
     */
    private String[] enumValues;

    /**
     * For string types: minimum length
     */
    private Integer minLength;

    /**
     * For string types: maximum length
     */
    private Integer maxLength;

    /**
     * For numeric types: minimum value
     */
    private Number minimum;

    /**
     * For numeric types: maximum value
     */
    private Number maximum;

    /**
     * For numeric types: minimum exclusive value
     */
    private Number exclusiveMinimum;

    /**
     * For numeric types: maximum exclusive value
     */
    private Number exclusiveMaximum;

    /**
     * Regular expression pattern for string validation
     */
    private String pattern;

    /**
     * Additional metadata for complex parameter types
     */
    private Map<String, Object> metadata;
}
