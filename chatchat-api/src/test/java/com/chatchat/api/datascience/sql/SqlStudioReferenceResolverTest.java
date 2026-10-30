package com.chatchat.api.datascience.sql;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlStudioReferenceResolverTest {
    final SqlStudioMetadataService metadata=mock(SqlStudioMetadataService.class);
    final SqlGlotToolchain parser=mock(SqlGlotToolchain.class);
    final ObjectProvider<SqlStudioMetadataService> provider=mock(ObjectProvider.class);
    final KernelDataScope scope=new KernelDataScope("tenant","user",null,null,"run",null,Map.of());
    final SqlStudioMetadataService.Context context=new SqlStudioMetadataService.Context("discovery","project","QUERY","oracle",
        List.of(new SqlStudioMetadataService.Binding("binding","service","asset","SOURCE","db","app","oracle")),"READY");
    final List<SqlOutputBundle.Candidate> files=List.of(new SqlOutputBundle.Candidate("query.sql","select ID from app.TZRT_HYXXLS"));
    SqlStudioReferenceResolver resolver;
    @BeforeEach void setup(){
        when(provider.getObject()).thenReturn(metadata);resolver=new SqlStudioReferenceResolver(provider,parser);
        when(parser.validate(eq("oracle"),eq(files),anyMap())).thenReturn(Map.of("valid",true,"references",List.of(
            Map.of("tableName","TZRT_HYXXLS","schema","app","database","","quoted",false))));
    }
    Map<String,Object> table(){return Map.of("tableName","TZRT_HYXXLS","database","db","schema","app","tableRef","ref",
        "columns",List.of(Map.of("name","ID")));}
    void result(List<?> tables){when(metadata.operateScoped(eq(scope),eq(context),any())).thenReturn(
        new SqlStudioMetadataGateway.Result("service",Map.of("status","SUCCEEDED","snapshotId","v1","items",tables)));}
    @Test void discoversAuthorizedTableOutsideInitialSnapshotAndKeepsItsProvenance(){
        result(List.of(table()));var resolved=resolver.resolve(scope,context,"oracle",files,List.of());
        assertThat(resolved).hasSize(1);assertThat(resolved.get(0)).containsEntry("discoveryId","discovery").containsEntry("expectedSnapshotId","v1");
        verify(metadata).operateScoped(eq(scope),eq(context),argThat(op->op.arguments().get("operation").equals("DESCRIBE")&&op.arguments().get("schema").equals("app")));
    }
    @Test void missingTableHasExplicitNotFoundError(){result(List.of());assertThatThrownBy(()->resolver.resolve(scope,context,"oracle",files,List.of())).hasMessageContaining("TABLE_NOT_FOUND");}
    @Test void preservesPermissionDenials(){when(metadata.operateScoped(any(),any(),any())).thenThrow(new SecurityException("denied"));assertThatThrownBy(()->resolver.resolve(scope,context,"oracle",files,List.of())).isInstanceOf(SecurityException.class);}
    @Test void rejectsNamespaceOutsideSelectedBindingBeforeCallingMcp(){
        when(parser.validate(anyString(),anyList(),anyMap())).thenReturn(Map.of("valid",true,"references",List.of(Map.of("tableName","secret","schema","other","database",""))));
        assertThatThrownBy(()->resolver.resolve(scope,context,"oracle",files,List.of())).isInstanceOf(SecurityException.class);verifyNoInteractions(metadata);
    }
    @Test void cachedObjectsAreReauthorizedAndReplacedWithoutDuplicates(){result(List.of(table()));var resolved=resolver.resolve(scope,context,"oracle",files,List.of(table()));assertThat(resolved).hasSize(1);verify(metadata).operateScoped(eq(scope),eq(context),any());}
    @Test void refusesAmbiguousUnqualifiedNames(){result(List.of(table(),table()));assertThatThrownBy(()->resolver.resolve(scope,context,"oracle",files,List.of())).hasMessageContaining("AMBIGUOUS_TABLE");}
    @Test void namespaceAndQuoteMatchingFollowTheTargetDialectCaseSemantics(){
        var table=Map.<String,Object>of("tableName","ORDERS","database","APP","schema","APP");
        assertThat(SqlStudioReferenceResolver.matches(table,Map.of("tableName","orders","schema","app","quoted",true,
            "caseSensitive",false,"schemaQuoted",true,"schemaCaseSensitive",false))).isTrue();
        assertThat(SqlStudioReferenceResolver.matches(table,Map.of("tableName","orders","schema","app","quoted",true,"caseSensitive",true))).isFalse();
    }
}
