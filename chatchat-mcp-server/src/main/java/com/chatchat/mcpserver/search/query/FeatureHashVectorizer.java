package com.chatchat.mcpserver.search.query;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FeatureHashVectorizer {

    private FeatureHashVectorizer() {
    }

    public static List<Float> vectorize(String value, int requestedDimension) {
        int dimension = Math.max(8, requestedDimension);
        float[] vector = new float[dimension];
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return List.of();
        for (String token : normalized.split("[\\s_\\-/.,，。；;:：()（）\\[\\]【】]+")) {
            if (token.isBlank()) continue;
            add(vector, "t:" + token, 1.4F);
            int[] points = token.codePoints().toArray();
            for (int size : List.of(2, 3)) {
                for (int index = 0; index + size <= points.length; index++) {
                    add(vector, "n" + size + ":" + new String(points, index, size), 1.0F);
                }
            }
        }
        double norm = 0.0D;
        for (float item : vector) norm += item * item;
        if (norm == 0.0D) return List.of();
        float scale = (float) (1.0D / Math.sqrt(norm));
        List<Float> result = new ArrayList<>(dimension);
        for (float item : vector) result.add(item * scale);
        return List.copyOf(result);
    }

    private static void add(float[] vector, String feature, float weight) {
        int hash = 0x811C9DC5;
        for (byte current : feature.getBytes(StandardCharsets.UTF_8)) {
            hash ^= current & 0xFF;
            hash *= 0x01000193;
        }
        vector[Math.floorMod(hash, vector.length)] += (hash & 1) == 0 ? weight : -weight;
    }
}
