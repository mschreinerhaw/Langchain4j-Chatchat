package com.chatchat.agents.runtime.plan.review;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Resolves literal canonical references against a complete authorized discovery page. */
public final class CanonicalAssetReferenceReviewer {
    public static final String MODE = "RUNTIME_EXACT_CANONICAL_ASSET_REFERENCE";
    private static final Pattern QUALIFIED_REFERENCE = Pattern.compile(
        "(?iu)(?:\\b(?:no|not|never|neither|nor|don't|dont|except|exclude|excluding|ignore|without|instead|rather)\\b|不|非|无|勿|别|排除|忽略|除外|以外|之外|其他|其它|其余|替代)");
    // One literal name does not prove that the request is limited to one target.
    // Preserve semantic admission for comparisons and inclusive/multiple scopes.
    private static final Pattern MULTI_TARGET_REFERENCE = Pattern.compile(
        "(?iu)(?:\\b(?:compare|comparison|versus|vs|both|either|all|every|including|other)\\b|比较|对比|所有|全部|各个|每个|以及|同时|还有)");
    private CanonicalAssetReferenceReviewer() {}

    public static Optional<InterpretationPlanRuntime.StepReview> review(String userQuery, Object output) {
        String query = normalize(userQuery);
        if (query.isEmpty() || QUALIFIED_REFERENCE.matcher(query).find()
            || MULTI_TARGET_REFERENCE.matcher(query).find()
            || !(output instanceof Map<?,?> page)
            || !"asset_query_result.v1".equals(page.get("schemaVersion"))
            || !Boolean.TRUE.equals(page.get("success"))
            || !Boolean.FALSE.equals(page.get("possiblyTruncated"))
            || !(page.get("assets") instanceof List<?> candidates) || candidates.isEmpty()
            || !(page.get("returnedCount") instanceof Number count) || count.doubleValue()!=candidates.size())
            return Optional.empty();
        Set<String> allIds = new HashSet<>();
        Map<String,String> matches = new LinkedHashMap<>();
        for (Object candidate : candidates) {
            if (!(candidate instanceof Map<?,?> entry) || !(entry.get("asset") instanceof Map<?,?> asset))
                return Optional.empty();
            String id = text(asset.get("id"));
            if (id.isEmpty() || !allIds.add(id.toLowerCase(Locale.ROOT))) return Optional.empty();
            if (!Boolean.TRUE.equals(asset.get("enabled"))) continue;
            for (String field : List.of("name", "displayName")) {
                String name = normalize(text(asset.get(field)));
                if (literalReference(query, name)) matches.put(id, field);
            }
        }
        if (matches.size()!=1) return Optional.empty();
        if (!unavailableReferencesAreDisjoint(query,page,allIds)) return Optional.empty();
        var selected=matches.entrySet().iterator().next();
        return Optional.of(InterpretationPlanRuntime.StepReview.accepted(
            "The user explicitly named one canonical authorized asset; no fuzzy or rank-based selection was used.",
            Map.of("toolResultReviewSkipped",true,"toolResultReviewMode",MODE,
                "selectedAssetIds",List.of(selected.getKey()),"assetReferenceMatchedField",selected.getValue())));
    }

    /** Unrelated unavailable assets do not invalidate an exact authorized identity.
     * Their complete identities must still be present so ambiguity cannot be hidden. */
    private static boolean unavailableReferencesAreDisjoint(String query,Map<?,?> page,Set<String> ids) {
        Object raw=page.get("unavailableAssets");
        List<?> unavailable=raw instanceof List<?> list?list:List.of();
        if (page.get("unavailableCount") instanceof Number count) {
            if (count.doubleValue()!=unavailable.size()) return false;
        } else if (!unavailable.isEmpty()) return false;
        for (Object candidate : unavailable) {
            if (!(candidate instanceof Map<?,?> entry) || !(entry.get("asset") instanceof Map<?,?> asset)) return false;
            String id=text(asset.get("id"));
            if (id.isEmpty() || !ids.add(id.toLowerCase(Locale.ROOT))) return false;
            String name=normalize(text(asset.get("name"))),display=normalize(text(asset.get("displayName")));
            if (name.isEmpty() && display.isEmpty()) return false;
            if (literalReference(query,name) || literalReference(query,display)) return false;
        }
        return true;
    }

    private static boolean literalReference(String query, String name) {
        if (name.isEmpty()) return false;
        if (query.equals(name)) return true;
        // Short labels are only decisive when supplied as the entire reference.
        if (name.codePointCount(0,name.length())<3) return false;
        int offset=0;
        while ((offset=query.indexOf(name,offset))>=0) {
            int end=offset+name.length();
            if ((offset==0 || !identifierPart(query.codePointBefore(offset)))
                && (end==query.length() || !identifierPart(query.codePointAt(end)))) return true;
            offset=end;
        }
        return false;
    }
    private static boolean identifierPart(int codePoint) {
        return codePoint=='_' || codePoint=='-' || codePoint=='.'
            || (Character.isLetterOrDigit(codePoint) && Character.UnicodeScript.of(codePoint)!=Character.UnicodeScript.HAN);
    }
    private static String text(Object value) { return value instanceof String s?s.trim():""; }
    private static String normalize(String value) {
        return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC).strip()
            .replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
}
