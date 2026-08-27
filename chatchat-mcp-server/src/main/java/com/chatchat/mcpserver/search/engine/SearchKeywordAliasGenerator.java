package com.chatchat.mcpserver.search.engine;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Derives compact search aliases from user-maintained asset and template names.
 *
 * <p>Only initial-letter aliases are emitted: Chinese characters use their
 * pinyin initial and English identifiers use word initials (including camel
 * case and separator-delimited names). Keeping this separate from descriptive
 * text prevents arbitrary prose from flooding the search index with acronyms.</p>
 */
final class SearchKeywordAliasGenerator {

    private static final int MIN_ALIAS_LENGTH = 2;
    private static final int MAX_ALIAS_LENGTH = 16;
    private static final int MAX_PRONUNCIATION_VARIANTS = 16;
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = pinyinFormat();

    private SearchKeywordAliasGenerator() {
    }

    static List<String> aliases(String... names) {
        Set<String> aliases = new LinkedHashSet<>();
        if (names == null) {
            return List.of();
        }
        for (String name : names) {
            addAliases(aliases, name);
        }
        return List.copyOf(aliases);
    }

    private static void addAliases(Set<String> aliases, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        List<String> units = logicalUnits(value);
        addAliases(aliases, initialVariants(units));

        List<String> chineseUnits = units.stream().filter(SearchKeywordAliasGenerator::isHanUnit).toList();
        if (chineseUnits.size() != units.size()) {
            addAliases(aliases, initialVariants(chineseUnits));
        }

        List<String> englishUnits = units.stream().filter(SearchKeywordAliasGenerator::isAsciiWord).toList();
        if (englishUnits.size() != units.size()) {
            addAliases(aliases, initialVariants(englishUnits));
        }
    }

    private static List<String> logicalUnits(String value) {
        List<String> units = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushAsciiUnits(units, ascii);
                units.add(new String(Character.toChars(codePoint)));
            } else if (Character.isLetterOrDigit(codePoint)) {
                ascii.appendCodePoint(codePoint);
            } else {
                flushAsciiUnits(units, ascii);
            }
        }
        flushAsciiUnits(units, ascii);
        return units;
    }

    private static void flushAsciiUnits(List<String> units, StringBuilder ascii) {
        if (ascii.isEmpty()) {
            return;
        }
        String value = ascii.toString();
        ascii.setLength(0);
        for (String word : value.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
            if (!word.isBlank()) {
                units.add(word);
            }
        }
    }

    private static List<String> initialVariants(List<String> units) {
        if (units.size() < 2) {
            return List.of();
        }
        List<String> variants = new ArrayList<>(List.of(""));
        for (String unit : units) {
            List<String> initials = new ArrayList<>();
            if (isHanUnit(unit)) {
                initials.addAll(pinyinInitials(unit.charAt(0)));
            } else if (isAsciiWord(unit) && Character.isLetter(unit.charAt(0))) {
                initials.add(String.valueOf(Character.toLowerCase(unit.charAt(0))));
            }
            if (initials.isEmpty()) {
                continue;
            }
            List<String> expanded = new ArrayList<>();
            for (String prefix : variants) {
                for (String initial : initials) {
                    expanded.add(prefix + initial);
                    if (expanded.size() >= MAX_PRONUNCIATION_VARIANTS) {
                        break;
                    }
                }
                if (expanded.size() >= MAX_PRONUNCIATION_VARIANTS) {
                    break;
                }
            }
            variants = expanded;
        }
        return variants;
    }

    private static List<String> pinyinInitials(char value) {
        try {
            String[] values = PinyinHelper.toHanyuPinyinStringArray(value, PINYIN_FORMAT);
            if (values == null || values.length == 0) {
                return List.of();
            }
            Set<String> initials = new LinkedHashSet<>();
            for (String pinyin : values) {
                if (pinyin != null && !pinyin.isBlank()) {
                    initials.add(pinyin.substring(0, 1).toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(initials);
        } catch (BadHanyuPinyinOutputFormatCombination ignored) {
            return List.of();
        }
    }

    private static void addAliases(Set<String> aliases, List<String> values) {
        for (String value : values) {
            addAlias(aliases, value);
        }
    }

    private static void addAlias(Set<String> aliases, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.length() >= MIN_ALIAS_LENGTH && normalized.length() <= MAX_ALIAS_LENGTH) {
            aliases.add(normalized);
        }
    }

    private static boolean isHanUnit(String value) {
        return value != null && value.length() == 1
            && Character.UnicodeScript.of(value.charAt(0)) == Character.UnicodeScript.HAN;
    }

    private static boolean isAsciiWord(String value) {
        return value != null && !value.isBlank() && value.chars().allMatch(codePoint -> codePoint < 128);
    }

    private static HanyuPinyinOutputFormat pinyinFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }
}
