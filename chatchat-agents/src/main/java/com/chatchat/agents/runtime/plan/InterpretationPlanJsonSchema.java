package com.chatchat.agents.runtime.plan;

/**
 * JSON Schema for the planner-only InterpretationPlan protocol.
 */
public final class InterpretationPlanJsonSchema {

    public static final String VERSION = "1.0";

    public static final String SCHEMA = """
        {
          "type": "object",
          "required": ["version", "intent", "context", "plan", "review"],
          "additionalProperties": false,
          "properties": {
            "version": {"type": "string", "description": "protocol version, e.g. 1.0"},
            "intent": {
              "type": "object",
              "required": ["type", "goal"],
              "additionalProperties": false,
              "properties": {
                "type": {
                  "type": "string",
                  "enum": ["reasoning", "data_query", "tool_chain", "web_search", "sql_query", "document_retrieval", "system_operation", "mixed"]
                },
                "goal": {"type": "string"},
                "risk_level": {"type": "string", "enum": ["low", "medium", "high"]}
              }
            },
            "context": {
              "type": "object",
              "required": ["key_facts", "constraints"],
              "additionalProperties": false,
              "properties": {
                "key_facts": {"type": "array", "items": {"type": "string"}},
                "assumptions": {"type": "array", "items": {"type": "string"}},
                "missing_info": {"type": "array", "items": {"type": "string"}},
                "constraints": {"type": "array", "items": {"type": "string"}}
              }
            },
            "plan": {
              "type": "object",
              "required": ["steps"],
              "additionalProperties": false,
              "properties": {
                "steps": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "required": ["id", "action_type", "tool_name", "input", "depends_on"],
                    "additionalProperties": false,
                    "properties": {
                      "id": {"type": "integer"},
                      "action_type": {
                        "type": "string",
                        "enum": ["mcp_tool", "reasoning", "retrieval", "aggregation", "validation", "final_answer"]
                      },
                      "tool_name": {"type": "string", "description": "MCP tool name, required when action_type=mcp_tool"},
                      "input": {"type": "object", "description": "tool input payload"},
                      "depends_on": {"type": "array", "items": {"type": "integer"}},
                      "output_contract": {
                        "type": "object",
                        "additionalProperties": false,
                        "properties": {
                          "type": {"type": "string", "enum": ["json", "text", "table", "stream"]},
                          "schema_hint": {"type": "string"}
                        }
                      },
                      "validation": {
                        "type": "object",
                        "additionalProperties": false,
                        "properties": {
                          "required": {"type": "boolean"},
                          "rule": {
                            "type": "string",
                            "enum": ["schema_check", "non_empty", "confidence_threshold", "manual_review"]
                          },
                          "threshold": {"type": "number"}
                        }
                      }
                    }
                  }
                },
                "dependency_contracts": {
                  "type": "array",
                  "description": "Declarative dependency semantics. depends_on remains the hard DAG ordering field; dependency_contracts explains whether each dependency is mandatory or optional.",
                  "items": {
                    "type": "object",
                    "required": ["from", "to", "required"],
                    "additionalProperties": false,
                    "properties": {
                      "from": {"type": "integer"},
                      "to": {"type": "integer"},
                      "required": {"type": "boolean", "description": "true means this dependency must be executed before target; false means planner should include it only when condition is needed"},
                      "condition": {"type": "string", "description": "When required=false, the condition under which this dependency should be planned/executed"},
                      "reason": {"type": "string", "description": "Why the dependency is needed or optional"},
                      "on_failure": {
                        "type": "string",
                        "enum": ["stop", "skip", "continue_with_partial_evidence", "replan"],
                        "description": "Runtime/planner policy if this dependency is unavailable or fails"
                      }
                    }
                  }
                },
                "edge_contracts": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["from", "to", "field", "type"],
                    "additionalProperties": false,
                    "properties": {
                      "from": {"type": "integer"},
                      "to": {"type": "integer"},
                      "field": {"type": "string", "description": "source output field path, e.g. data.results"},
                      "type": {"type": "string", "enum": ["array", "object", "string", "number", "boolean", "any"]},
                      "required": {"type": "boolean"}
                    }
                  }
                },
                "bindings": {
                  "type": "array",
                  "description": "Executable data-flow bindings from an upstream step output into a downstream step input.",
                  "items": {
                    "type": "object",
                    "required": ["from", "output_path", "to", "type"],
                    "anyOf": [
                      {"required": ["input_field"]},
                      {"required": ["input_path"]}
                    ],
                    "additionalProperties": false,
                    "properties": {
                      "from": {"type": "integer"},
                      "output_path": {"type": "string", "description": "Canonical source output path. Asset names use $.assets[0].asset.name. Template ids use $.templates[0].templateId. Never bind parameterSchema or other schema metadata into execution parameters."},
                      "to": {"type": "integer"},
                      "input_field": {"type": "string", "description": "Complete target input path, e.g. filters.assetName or $.executionContext.assetName. Executor parameters contain concrete values only."},
                      "input_path": {"type": "string", "description": "alias of input_field; complete JSONPath destination, e.g. $.filters.assetName"},
                      "type": {"type": "string", "enum": ["jsonpath", "jq"]},
                      "required": {"type": "boolean"}
                    }
                  }
                },
                "stability": {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "stable_nodes": {"type": "array", "items": {"type": "integer"}},
                    "critical_tools": {"type": "array", "items": {"type": "string"}},
                    "locked_edges": {"type": "boolean"},
                    "mutable_action_types": {"type": "array", "items": {"type": "string"}}
                  }
                }
              }
            },
            "execution_policy": {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "max_steps": {"type": "integer"},
                "allow_parallel": {"type": "boolean"},
                "allow_tool": {"type": "array", "items": {"type": "string"}},
                "deny_tool": {"type": "array", "items": {"type": "string"}},
                "timeout_ms": {"type": "integer"},
                "max_rewrite_times": {"type": "integer"},
                "fallback_mode": {"type": "string", "enum": ["safe_answer", "partial_result"]},
                "tool_priority": {
                  "type": "object",
                  "additionalProperties": {"type": "number"}
                },
                "cost_budget": {"type": "number"},
                "latency_budget_ms": {"type": "integer"},
                "accuracy_vs_speed": {"type": "number"}
              }
            },
            "review": {
              "type": "object",
              "required": ["self_check"],
              "additionalProperties": false,
              "properties": {
                "self_check": {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "completeness_score": {"type": "number"},
                    "hallucination_risk": {"type": "number"},
                    "tool_sufficiency": {"type": "boolean"},
                    "missing_steps": {"type": "array", "items": {"type": "string"}}
                  }
                },
                "fallback_plan": {"type": "array", "items": {"type": "string"}}
              }
            }
          }
        }
        """;

    private InterpretationPlanJsonSchema() {
    }
}
