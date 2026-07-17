package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsSourceType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores source types as an evolvable VARCHAR instead of a database-native closed ENUM. */
@Converter
public class NewsSourceTypeConverter implements AttributeConverter<NewsSourceType, String> {
    @Override
    public String convertToDatabaseColumn(NewsSourceType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public NewsSourceType convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? null : NewsSourceType.valueOf(value);
    }
}
