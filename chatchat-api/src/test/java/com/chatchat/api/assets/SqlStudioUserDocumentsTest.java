package com.chatchat.api.assets;

import com.chatchat.api.datascience.sql.SqlStudioUserDocuments;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.knowledgebase.search.document.infrastructure.extract.DocumentTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class SqlStudioUserDocumentsTest {
    UserAssetService assets;SqlStudioUserDocuments documents;RuntimeAssetArchive archive;
    final UserAssetService.Owner owner=new UserAssetService.Owner("tenant","alice");
    final KernelDataScope scope=new KernelDataScope("tenant","alice",null,"session","sql-generation-one",null,Map.of());
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:sql-docs-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);
        var jdbc=new JdbcTemplate(db);assets=new UserAssetService(jdbc,new DataSourceTransactionManager(db),new UserAssetServiceTest.MemoryBlobs(),new AssetStorageProperties());
        archive=new RuntimeAssetArchive(assets,jdbc,new ObjectMapper());documents=new SqlStudioUserDocuments(assets,new DocumentTextExtractor(),archive);
    }
    @Test void ruleAndEvidenceSnapshotsRetainCompleteContentAndExactArchivedOriginals(){
        byte[] sql="-- Rules\r\nSELECT 1 AS required_alias;\r\n".getBytes(StandardCharsets.UTF_8);
        var rule=assets.upload(owner,"conventions.sql",sql);
        var data=assets.upload(owner,"definitions.json","{\"table\":\"sales_fact\"}".getBytes(StandardCharsets.UTF_8));
        var snapshots=documents.capture(owner,List.of(new SqlStudioUserDocuments.Selection(rule.id(),"RULES"),new SqlStudioUserDocuments.Selection(data.id(),"EVIDENCE")));
        assertThat(snapshots).extracting(SqlStudioUserDocuments.Snapshot::purpose).containsExactly("RULES","EVIDENCE");
        assertThat(snapshots.get(0).content()).contains("required_alias");assertThat(snapshots.get(1).content()).contains("sales_fact");
        documents.archive(scope,"sql-coding-one",snapshots);
        assets.delete(owner,rule.id());assets.delete(owner,data.id());
        documents.archive(scope,"sql-coding-one",snapshots); // Recovery uses the task-bound original, not a new user-wide lookup.
        assertThat(archive.read(scope,"sql-coding-one","sql-reference:"+rule.id()+":"+rule.sha256()).orElseThrow()).isEqualTo(sql);
        assertThat(assets.list(owner,null,"sql-coding-one",10)).hasSize(2);
    }
    @Test void foreignOwnerAndTenantCannotReadReferences(){
        var file=assets.upload(owner,"private.md","private reference".getBytes(StandardCharsets.UTF_8));
        for(var other:List.of(new UserAssetService.Owner("tenant","bob"),new UserAssetService.Owner("other","alice")))
            assertThatThrownBy(()->documents.capture(other,List.of(new SqlStudioUserDocuments.Selection(file.id(),"RULES")))).hasMessageContaining("404");
    }
    @Test void duplicatePurposesUnsupportedFilesAndExcessiveTextFailExplicitly(){
        var file=assets.upload(owner,"rules.md","x".repeat(180001).getBytes(StandardCharsets.UTF_8));
        var selection=new SqlStudioUserDocuments.Selection(file.id(),"RULES");
        assertThatThrownBy(()->documents.capture(owner,List.of(selection))).hasMessageContaining("180000");
        assertThatThrownBy(()->documents.capture(owner,List.of(selection,selection))).hasMessageContaining("unique");
        assertThatThrownBy(()->documents.capture(owner,List.of(new SqlStudioUserDocuments.Selection(file.id(),"SYSTEM")))).hasMessageContaining("purpose");
        var code=assets.store(owner,UserAssetService.Kind.CODE,"task","run","code","main.sql","text/plain",new byte[]{1});
        assertThatThrownBy(()->documents.capture(owner,List.of(new SqlStudioUserDocuments.Selection(code.id(),"RULES")))).hasMessageContaining("documents");
    }
}
