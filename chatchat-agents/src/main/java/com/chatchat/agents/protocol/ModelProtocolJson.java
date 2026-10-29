package com.chatchat.agents.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ModelProtocolJson {

    private static final Gson COMPACT_GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create();

    private static final Gson PRETTY_GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .setPrettyPrinting()
        .create();

    private ModelProtocolJson() {
    }

    public static String compact(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return COMPACT_GSON.toJson(value);
        } catch (RuntimeException ex) {
            return String.valueOf(value);
        }
    }

    public static String pretty(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return PRETTY_GSON.toJson(value);
        } catch (RuntimeException ex) {
            return String.valueOf(value);
        }
    }

    public static String prettyJsonForLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        if (!looksLikeJson(text)) {
            return raw;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return PRETTY_GSON.toJson(parsed);
        } catch (RuntimeException ex) {
            return raw;
        }
    }

    public static String jsonStringContent(String value) {
        if (value == null) {
            return "";
        }
        try {
            String json = COMPACT_GSON.toJson(value);
            return json.length() >= 2 ? json.substring(1, json.length() - 1) : value;
        } catch (RuntimeException ex) {
            return value;
        }
    }

    public static String sha256Hex(Object value) {
        return sha256Hex(compact(value));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** A resource bound, never an instruction extractor. The original message is retained by Runtime. */
    public static java.util.Map<String,Object> userInputView(String value, int maximum) {
        if(maximum<256 || maximum>32768)throw new IllegalArgumentException("Invalid user input view budget");
        String text=value==null?"":value;
        boolean complete=text.length()<=maximum;
        String view=text;
        if(!complete) {
            String marker="\n[Material omitted from this planning view; consult the immutable original before analysis.]\n";
            int head=(maximum-marker.length())/2,tailStart=text.length()-(maximum-marker.length()-head);
            if(head>0 && Character.isHighSurrogate(text.charAt(head-1)))head--;
            if(tailStart<text.length() && Character.isLowSurrogate(text.charAt(tailStart)))tailStart++;
            view=text.substring(0,head)+marker+text.substring(tailStart);
        }
        return java.util.Map.of("text",view,"complete",complete,"originalCharacters",text.length(),
            "originalSha256",sha256Hex(text),"authority","USER_MESSAGE_WITH_UNTRUSTED_MATERIALS");
    }

    private static boolean looksLikeJson(String text) {
        return (text.startsWith("{") && text.endsWith("}"))
            || (text.startsWith("[") && text.endsWith("]"));
    }
}
