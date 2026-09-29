package com.chatchat.common.runtime.analysis.routing;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative fallback; an explicitly selected workflow always takes precedence. */
public final class AssetGuidanceIntent {
    private AssetGuidanceIntent() { }
    private static final Pattern ASSET = Pattern.compile("(?i)(?<![a-z0-9])(api|table|dataset|metric|view)(?![a-z0-9])|数据资产|接口|数据表|这.{0,4}表|表怎么|表如何|模板|指标");
    public static boolean matches(String query) {
        if (query == null) return false;
        String text = query.toLowerCase(Locale.ROOT);
        // Mixed guidance/execution requests must not silently lose their action portion.
        if (contains(text, "帮我执行", "立即执行", "执行一下", "帮我调用", "查询数据", "取出数据", "删除", "更新数据",
                "execute ", "run the ", "delete ", "fetch data")) return false;
        boolean reverse = contains(text, "用什么数据", "需要哪些数据", "推荐哪些资产", "用哪个接口", "用哪些表", "which data should");
        return reverse || ASSET.matcher(text).find() && contains(text, "做什么", "干什么", "什么用", "用途", "怎么用", "如何使用",
            "如何调用", "调用方式", "怎么调用", "哪些场景", "业务场景", "使用效果", "谁在用", "使用情况", "支持哪些", "参数说明",
            "how to use", "what is", "used for", "usage", "use cases");
    }
    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
