package com.chatchat.common.runtime.coding;

import java.util.List;
import java.util.HashSet;

/** One immutable dataset representation; the existing task binds the complete (1..8) dataset set. */
public record CodingInputManifest(String schemaVersion, String sourceDatasetRef, Format format,
        String schemaFileId, String logicalContentSha256, long rowCount, long logicalBytes,
        List<Column> columns, List<Part> parts, List<NativeFile> nativeFiles) {
    public static final String VERSION = "runtime_os.coding.input.v1";
    public enum Format { JSON_LINES, PARQUET, CSV }
    public enum Type { BOOLEAN, INT64, FLOAT64, DECIMAL, STRING, JSON, NULL }
    /** Original bytes, mounted by the same immutable file delivery as table parts. */
    public record NativeFile(String fileId, String fileName, String originalName, String sourceDatasetRef, String sha256, long sizeBytes) {
        public NativeFile {
            CodingInputManifest.fileId(fileId); CodingInputManifest.fileName(fileName); hash(sha256);
            if (sourceDatasetRef==null || sourceDatasetRef.isBlank() || sourceDatasetRef.length()>2048
                    || sizeBytes < 0 || originalName == null || originalName.isBlank() || originalName.length() > 1024
                    || originalName.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid native input file");
        }
        @com.fasterxml.jackson.annotation.JsonProperty(value="virtualPath",access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String virtualPath(){return "/data/input/"+fileId+"/"+fileName;}
        @com.fasterxml.jackson.annotation.JsonProperty(value="readOnly",access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean readOnly(){return true;}
        @com.fasterxml.jackson.annotation.JsonProperty(value="format",access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String format(){
            String extension=fileName.substring(fileName.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
            return switch(extension){
                case "txt","log","md","markdown","sql","yaml","yml","xml"->"TEXT";
                case "jsonl"->"JSON_LINES";
                default->extension.toUpperCase(java.util.Locale.ROOT);
            };
        }
    }
    public CodingInputManifest(String schemaVersion, String sourceDatasetRef, Format format, String schemaFileId,
            String logicalContentSha256, long rowCount, long logicalBytes, List<Column> columns, List<Part> parts) {
        this(schemaVersion,sourceDatasetRef,format,schemaFileId,logicalContentSha256,rowCount,logicalBytes,columns,parts,List.of());
    }
    public record Column(String name, String storageName, Type type, int precision, int scale,
                         long missingCount, long nullCount) {
        public Column {
            if(name==null || name.isEmpty() || name.length()>1024 || name.chars().anyMatch(Character::isISOControl)
                    || storageName==null || !storageName.matches("c[0-9]{1,4}") || type==null
                    || missingCount<0 || nullCount<0 || precision<0 || precision>38 || scale<0 || scale>precision
                    || type==Type.DECIMAL && precision==0)
                throw new IllegalArgumentException("Invalid input column");
        }
    }
    public record Part(String fileId, String fileName, String sha256, long rowCount, long sizeBytes) {
        public Part {
            CodingInputManifest.fileId(fileId); CodingInputManifest.fileName(fileName); hash(sha256);
            if(rowCount<0 || sizeBytes<0)throw new IllegalArgumentException("Invalid input part size");
        }
    }
    public CodingInputManifest {
        if(!VERSION.equals(schemaVersion) || sourceDatasetRef==null || sourceDatasetRef.isBlank()
                || sourceDatasetRef.length()>2048 || format==null || rowCount<0 || logicalBytes<0)
            throw new IllegalArgumentException("Unsupported or invalid input manifest");
        fileId(schemaFileId); hash(logicalContentSha256);
        columns=List.copyOf(columns);parts=List.copyOf(parts);
        nativeFiles=nativeFiles==null?List.of():List.copyOf(nativeFiles);
        if(nativeFiles.size()>8)throw new IllegalArgumentException("Too many native input files");
        if(columns.size()>256 || parts.isEmpty() || parts.size()>64)
            throw new IllegalArgumentException("Input manifest exceeds existing column/part bounds");
        var names=new HashSet<String>(); var storage=new HashSet<String>(); var ids=new HashSet<String>();
        ids.add(schemaFileId);
        for(var c:columns) {
            if(!names.add(c.name()) || !storage.add(c.storageName()) || c.missingCount()>rowCount
                    || c.nullCount()>rowCount-c.missingCount())throw new IllegalArgumentException("Invalid input column counts/identity");
        }
        long count=0;
        for(var p:parts) {
            if(!ids.add(p.fileId()))throw new IllegalArgumentException("Duplicate input file ID");
            String extension=switch(format){case JSON_LINES->".jsonl";case PARQUET->".parquet";case CSV->".csv";};
            if(!p.fileName().endsWith(extension))throw new IllegalArgumentException("Input format/extension mismatch");
            count=Math.addExact(count,p.rowCount());
        }
        if(count!=rowCount)throw new IllegalArgumentException("Input manifest row count differs from parts");
        for(var file:nativeFiles)if(!ids.add(file.fileId()))throw new IllegalArgumentException("Duplicate native input file ID");
    }
    public static void fileId(String value) {
        if(value==null || !value.matches("c[0-9a-f]{63}"))throw new IllegalArgumentException("Invalid immutable input file ID");
    }
    public static void fileName(String value) {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,170}"))
            throw new IllegalArgumentException("Invalid input file name");
    }
    public static void hash(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid input checksum");
    }
}
