package com.chatchat.runtime.news.store;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts only explicit day-level dates; years and reporting periods remain lexical query text. */
final class NewsQueryDateParser {
    private static final Pattern DATE = Pattern.compile(
        "(?<!\\d)(\\d{4})(?:年|[-/.])(\\d{1,2})(?:月|[-/.])(\\d{1,2})日?(?!\\d)");

    private NewsQueryDateParser() {
    }

    static Optional<TimeRange> parse(String query, String zoneId) {
        if (query == null || query.isBlank()) return Optional.empty();
        Matcher matcher = DATE.matcher(query);
        if (!matcher.find()) return Optional.empty();
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            ZoneId zone = ZoneId.of(zoneId == null || zoneId.isBlank() ? "Asia/Shanghai" : zoneId.trim());
            Instant start = date.atStartOfDay(zone).toInstant();
            return Optional.of(new TimeRange(start, date.plusDays(1).atStartOfDay(zone).toInstant()));
        } catch (DateTimeException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static String lexicalText(String query) {
        if (query == null || query.isBlank()) return "";
        return DATE.matcher(query).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    record TimeRange(Instant startInclusive, Instant endExclusive) {
    }
}
