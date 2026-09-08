package com.chatchat.mcpserver.templatepublication.binding;

/**
 * Resolves the persisted child-capability route used by both publication and execution.
 * Implementations must treat the binding table as the source of truth and must not infer
 * a parent from tool-name conventions.
 */
public interface TemplateQueryRouteResolver {

    Route requireRoute(String childToolName);

    record Route(String childToolName, String parentToolName, String assetType) {
        public Route {
            childToolName = required(childToolName, "childToolName");
            parentToolName = required(parentToolName, "parentToolName");
            assetType = required(assetType, "assetType");
            if (childToolName.equals(parentToolName)) {
                throw new IllegalArgumentException("Child capability cannot route to itself: " + childToolName);
            }
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }
    }
}
