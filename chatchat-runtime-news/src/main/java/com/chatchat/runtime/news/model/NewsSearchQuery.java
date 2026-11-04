package com.chatchat.runtime.news.model;

import java.time.Instant;
import java.util.List;

public record NewsSearchQuery(
    String query,
    List<Long> sourceIds,
    Instant startTime,
    Instant endTime,
    List<String> categories,
    int size,
    String securityCode,
    boolean governedRead,
    String industry
) {
    public NewsSearchQuery(String query,List<Long> sourceIds,Instant startTime,Instant endTime,List<String> categories,int size) {
        this(query,sourceIds,startTime,endTime,categories,size,null,false,null);
    }
    public NewsSearchQuery(String query,List<Long> sourceIds,Instant startTime,Instant endTime,List<String> categories,int size,String securityCode,boolean governedRead) {
        this(query,sourceIds,startTime,endTime,categories,size,securityCode,governedRead,null);
    }
}
