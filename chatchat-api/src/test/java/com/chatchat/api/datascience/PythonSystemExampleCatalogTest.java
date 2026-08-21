package com.chatchat.api.datascience;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PythonSystemExampleCatalogTest {
    private final PythonSystemExampleCatalog catalog=new PythonSystemExampleCatalog();

    @Test void providesExecutableReferenceForEverySupportedFormat(){
        assertThat(catalog.list()).hasSize(9);
        Set<String> formats=catalog.list().stream().map(PythonSystemExampleCatalog.Example::format).collect(Collectors.toSet());
        assertThat(formats).containsExactlyInAnyOrder("CSV","XLS","XLSX","JSON","TXT","LOG","PARQUET","ORC","ZIP");
        assertThat(catalog.list()).allSatisfy(example->{
            assertThat(example.sourceCode()).contains("CHATCHAT_INPUT_JSON","source_file","json.dumps");
            assertThat(example.inputSchema()).contains("\"type\":\"FILE\"");
            assertThat(catalog.data(example.id())).isNotEmpty();
        });
    }

    @Test void binaryExamplesContainRealFormatSignatures(){
        assertThat(new String(catalog.data("parquet"),0,4,StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        assertThat(new String(catalog.data("orc"),0,3,StandardCharsets.US_ASCII)).isEqualTo("ORC");
        assertThat(new String(catalog.data("zip"),0,2,StandardCharsets.US_ASCII)).isEqualTo("PK");
        assertThat(new String(catalog.data("xlsx"),0,2,StandardCharsets.US_ASCII)).isEqualTo("PK");
    }
}
