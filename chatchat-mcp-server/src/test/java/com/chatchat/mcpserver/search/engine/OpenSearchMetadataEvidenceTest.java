package com.chatchat.mcpserver.search.engine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenSearchMetadataEvidenceTest {
    @Test void queriesTheExistingIndexWithAssetAndCurrentTableAllowlistBeforeReturningFullVersionedSchema() throws Exception {
        var json=new ObjectMapper();var captured=new AtomicReference<String>();var uri=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var columns=java.util.stream.IntStream.range(0,80).mapToObj(i->Map.of("name","field_"+i)).toList();
        server.createContext("/",exchange->{
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));uri.set(exchange.getRequestURI().toString());
            var evidence=Map.of("tableRef","authorized-table","tableName","ld_dataservice_api","snapshotId","version-one","columns",columns);
            var body=json.writeValueAsBytes(Map.of("hits",Map.of("hits",List.of(Map.of("_score",12,"_source",Map.of("metadataEvidence",evidence))))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var properties=new LuceneSearchProperties();properties.setEngine("opensearch");properties.getOpenSearch().setUrl("http://127.0.0.1:"+server.getAddress().getPort());
        var service=new OpenSearchMcpSearchService(properties,new McpEmbeddingClient(properties,json),json);
        try{
            var result=service.searchMetadataEvidence("authorized-source",List.of("authorized-table"),"service sql","ld_dataservice_api");
            assertThat(uri.get()).contains("assets-sql-datasource/_search");
            var query=json.readTree(captured.get()).path("query").toString();
            assertThat(query).contains("authorized-source","authorized-table","metadataTableRef","metadata_table","ld_dataservice_api");
            assertThat((List<?>)result.get(0).get("columns")).hasSize(80);
            assertThat(result.get(0)).containsEntry("snapshotId","version-one").containsEntry("evidenceSource","OPENSEARCH");
        }finally{service.close();server.stop(0);}
    }
}
