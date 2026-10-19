package com.chatchat.api.runtime.coding.data;

import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.common.runtime.coding.CodingInputManifest.*;
import com.chatchat.common.runtime.coding.CodingInputManifest.Type;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.csv.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.*;
import org.apache.parquet.hadoop.example.*;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.*;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.*;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.*;

/** Infrastructure conversion only. No inferred business units, dates, identifiers or template-specific rules. */
public final class CodingTableCodec {
    private CodingTableCodec() { }
    public static final String CSV_NULL="\\N",CSV_MISSING="\\M";
    public static String presence(Column column){return "p"+column.storageName().substring(1);}
    public static void write(Path target,Format format,List<Column> columns,List<JsonNode> rows,ObjectMapper json) throws IOException {
        if(format==Format.PARQUET) {
            var schema=parquetSchema(columns);var factory=new SimpleGroupFactory(schema);
            try(var writer=ExampleParquetWriter.builder(new LocalOutputFile(target)).withConf(new PlainParquetConfiguration())
                    .withType(schema).withCompressionCodec(CompressionCodecName.UNCOMPRESSED).withRowGroupSize(4*1024*1024)
                    .withPageSize(64*1024).withDictionaryEncoding(false).build()) {
                for(var row:rows) {
                    Group group=factory.newGroup();
                    if(columns.isEmpty())group.add("_empty",true);
                    for(var c:columns) {
                        var value=row.get(c.name());group.add(presence(c),value!=null);
                        if(value==null || value.isNull())continue;
                        switch(c.type()) {
                            case BOOLEAN->group.add(c.storageName(),value.booleanValue());
                            case INT64->group.add(c.storageName(),value.longValue());
                            case FLOAT64->group.add(c.storageName(),value.doubleValue());
                            case DECIMAL->group.add(c.storageName(),Binary.fromConstantByteArray(
                                value.decimalValue().setScale(c.scale(),RoundingMode.UNNECESSARY).unscaledValue().toByteArray()));
                            case STRING->group.add(c.storageName(),value.textValue());
                            case JSON->group.add(c.storageName(),json.writeValueAsString(value));
                            case NULL->throw new IOException("Null-only column contains a value");
                        }
                    }
                    writer.write(group);
                }
            }
        } else if(format==Format.CSV) {
            try(var out=Files.newBufferedWriter(target,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                var header=columns.stream().map(Column::storageName).toList();csvRow(out,header.isEmpty()?List.of("_empty"):header);
                for(var row:rows) {
                    var cells=new ArrayList<String>();
                    for(var c:columns) {
                        var value=row.get(c.name());
                        String cell=value==null?CSV_MISSING:value.isNull()?CSV_NULL:
                            c.type()==Type.STRING?value.textValue():json.writeValueAsString(value);
                        if(value!=null && !value.isNull() && cell.startsWith("\\"))cell="\\"+cell;
                        cells.add(cell);
                    }
                    csvRow(out,cells.isEmpty()?List.of("true"):cells);
                }
            }
        } else throw new IllegalArgumentException("New table codec requires Parquet or CSV");
    }
    private static MessageType parquetSchema(List<Column> columns) {
        var fields=new ArrayList<org.apache.parquet.schema.Type>();
        if(columns.isEmpty())fields.add(Types.required(BOOLEAN).named("_empty"));
        for(var c:columns) {
            fields.add(Types.required(BOOLEAN).named(presence(c)));
            fields.add(switch(c.type()) {
                case BOOLEAN->Types.optional(BOOLEAN).named(c.storageName());
                case INT64->Types.optional(INT64).named(c.storageName());
                case FLOAT64->Types.optional(DOUBLE).named(c.storageName());
                case DECIMAL->Types.optional(BINARY).as(LogicalTypeAnnotation.decimalType(c.scale(),c.precision())).named(c.storageName());
                case STRING,JSON,NULL->Types.optional(BINARY).as(LogicalTypeAnnotation.stringType()).named(c.storageName());
            });
        }
        return new MessageType("runtime_input",fields);
    }
    private static void csvRow(Writer out,List<String> cells) throws IOException {
        for(int i=0;i<cells.size();i++){if(i>0)out.write(',');out.write('"');out.write(cells.get(i).replace("\"","\"\""));out.write('"');}out.write('\n');
    }
    public static void read(Path file,CodingInputManifest manifest,ObjectMapper json,Consumer<Map<String,Object>> rows) throws IOException {
        var logicalJson=json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        if(manifest.format()==Format.PARQUET) {
            var builder=new ParquetReader.Builder<Group>(new LocalInputFile(file),new PlainParquetConfiguration()) {
                @Override protected ReadSupport<Group> getReadSupport(){return new GroupReadSupport();}
            };
            try(var reader=builder.build()) {
                Group group;
                while((group=reader.read())!=null) {
                    var row=new LinkedHashMap<String,Object>();
                    for(var c:manifest.columns()) {
                        if(!group.getBoolean(presence(c),0))continue;
                        Object value=null;
                        if(group.getFieldRepetitionCount(c.storageName())>0) value=switch(c.type()) {
                            case BOOLEAN->group.getBoolean(c.storageName(),0);
                            case INT64->group.getLong(c.storageName(),0);
                            case FLOAT64->group.getDouble(c.storageName(),0);
                            case DECIMAL->new BigDecimal(new BigInteger(group.getBinary(c.storageName(),0).getBytes()),c.scale());
                            case STRING->group.getBinary(c.storageName(),0).toStringUsingUTF8();
                            case JSON->logicalJson.readValue(group.getBinary(c.storageName(),0).toStringUsingUTF8());
                            case NULL->throw new IOException("Null-only column contains a value");
                        };
                        row.put(c.name(),value);
                    }
                    rows.accept(row);
                }
            }
        } else if(manifest.format()==Format.CSV) {
            var csv=new CsvMapper();
            try(var iterator=csv.readerFor(Map.class).with(CsvSchema.emptySchema().withHeader()).readValues(file.toFile())) {
                while(iterator.hasNextValue()) {
                    @SuppressWarnings("unchecked") var cells=(Map<String,String>)iterator.nextValue();var row=new LinkedHashMap<String,Object>();
                    for(var c:manifest.columns()) {
                        String cell=cells.get(c.storageName());
                        if(cell==null)throw new IOException("Missing CSV storage column");
                        if(CSV_MISSING.equals(cell))continue;
                        Object value=null;
                        if(!CSV_NULL.equals(cell)) {
                            if(cell.startsWith("\\\\"))cell=cell.substring(1);
                            value=switch(c.type()) {
                                case STRING->cell;
                                case BOOLEAN->{if(!cell.equals("true")&&!cell.equals("false"))throw new IOException("Invalid CSV boolean");yield Boolean.valueOf(cell);}
                                case INT64->Long.valueOf(cell);
                                case FLOAT64->Double.valueOf(cell);
                                case DECIMAL->new BigDecimal(cell).setScale(c.scale(),RoundingMode.UNNECESSARY);
                                case JSON->logicalJson.readValue(cell);
                                case NULL->throw new IOException("Null-only column contains a value");
                            };
                        }
                        row.put(c.name(),value);
                    }
                    rows.accept(row);
                }
            }
        } else throw new IOException("Unsupported table input format");
    }
}
