package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.orchestration.answer.AnswerContractCompiler;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Resolves the task's report language without depending on its input layout. */
public final class NativeReportLanguage {
    private NativeReportLanguage() {}

    public static String resolve(String query,Map<String,Object> metadata,Map<String,Object> attributes){
        var languageMetadata=new LinkedHashMap<>(attributes);
        languageMetadata.putAll(metadata);
        String configured=new AnswerContractCompiler()
            .compile(query,null,languageMetadata).language();
        if(!"AUTO".equalsIgnoreCase(configured)){
            try{
                var locale=new Locale.Builder().setLanguageTag(configured).build();
                if(!locale.getLanguage().isEmpty())return locale.toLanguageTag();
            }catch(IllformedLocaleException ignored){/* Use the request language. */}
        }
        if(query!=null){
            if(query.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HIRAGANA
                    ||Character.UnicodeScript.of(cp)==Character.UnicodeScript.KATAKANA))return "ja";
            if(query.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HANGUL))return "ko";
            if(query.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN))return "zh";
        }
        return "AUTO";
    }

    static String instruction(String language) {
        if ("AUTO".equals(language)) return "the language of the user's request";
        return Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH)+" ("+language+")";
    }
}
