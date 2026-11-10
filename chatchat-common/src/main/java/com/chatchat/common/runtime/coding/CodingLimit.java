package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** An explicit unlimited limit, never an enlarged finite ceiling or an unmetered counter. */
public record CodingLimit(long value) {
    public static final long UNLIMITED = -1;
    public CodingLimit {
        if (value < UNLIMITED || value > CodingProtocol.MAX_SAFE_INTEGER)
            throw new IllegalArgumentException("Limit must be unlimited or a nonnegative safe integer");
    }
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static CodingLimit parse(Object value) {
        if (value instanceof CodingLimit limit) return limit;
        if (value == null) throw new IllegalArgumentException("Limit is required");
        String text=value.toString().strip();
        if ("unlimited".equalsIgnoreCase(text)) return new CodingLimit(UNLIMITED);
        if (!text.matches("-1|0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid limit: "+text);
        return new CodingLimit(Long.parseLong(text));
    }
    @JsonValue public Object wire() { return wire(value); }
    public static Object wire(long limit) { return unlimited(limit)?"unlimited":limit; }
    public static boolean unlimited(long limit) { return limit==UNLIMITED; }
    public static boolean permits(long limit,long used) { return used>=0 && (unlimited(limit)||used<=limit); }
    public static boolean hasRoom(long limit,long used) { return used>=0 && (unlimited(limit)||used<limit); }
    public static long remaining(long limit,long used) { return unlimited(limit)?UNLIMITED:Math.max(0,limit-used); }
    public static void validate(long value) { new CodingLimit(value); }
}
