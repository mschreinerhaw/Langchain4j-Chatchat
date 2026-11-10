package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.common.runtime.coding.CodingLimit;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component @ConfigurationPropertiesBinding
public final class CodingLimitConverter implements Converter<String,CodingLimit> {
    @Override public CodingLimit convert(String value){return CodingLimit.parse(value);}
}
