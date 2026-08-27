package com.chatchat.mcpserver.metadata.search;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class EnterpriseMetadataVectorizer {

    private final EnterpriseMetadataProperties properties;

    public EnterpriseMetadataVectorizer(EnterpriseMetadataProperties properties) {
        this.properties = properties;
    }

    public List<Float> vectorize(String value) {
        int dimension = Math.max(8, properties.getKnn().getDimension());
        float[] vector = new float[dimension];
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> features = features(normalized);
        for (String feature : features) {
            byte[] bytes = feature.getBytes(StandardCharsets.UTF_8);
            int hash = 0x811C9DC5;
            for (byte current : bytes) {
                hash ^= current & 0xFF;
                hash *= 0x01000193;
            }
            int index = Math.floorMod(hash, dimension);
            float weight = feature.codePointCount(0, feature.length()) > 2 ? 1.4F : 1.0F;
            vector[index] += (hash & 1) == 0 ? weight : -weight;
        }
        double norm = 0.0D;
        for (float item : vector) {
            norm += item * item;
        }
        if (norm == 0.0D) {
            return List.of();
        }
        float scale = (float) (1.0D / Math.sqrt(norm));
        List<Float> result = new ArrayList<>(dimension);
        for (float item : vector) {
            result.add(item * scale);
        }
        return List.copyOf(result);
    }

    private List<String> features(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split("[\\s_\\-/.,，。；;:：()（）\\[\\]【】]+")) {
            if (token.isBlank()) {
                continue;
            }
            result.add("t:" + token);
            int[] points = token.codePoints().toArray();
            for (int size : List.of(2, 3)) {
                for (int index = 0; index + size <= points.length; index++) {
                    result.add("n" + size + ":" + new String(points, index, size));
                }
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
