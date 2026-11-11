package com.chatchat.api.runtime.coding.analysis;
import com.chatchat.api.runtime.coding.knowledge.CodingResourceReader;
import com.chatchat.common.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class LocalMarketWorkflowTest {
 @Test void unresolvedSecurityDeliversTheActualFullAuthorizedDayInsteadOfAnEmptyDirectoryResult(){
  var reader=mock(CodingResourceReader.class);var auth=mock(ResourceAuthorizationPort.class);var scopes=mock(AgentResourceScopePort.class);
  when(auth.allowedIds(anyString(),anyString(),anyString(),anySet(),anySet())).thenReturn(Set.of(AgentResourcePolicy.MARKET));
  when(reader.read(eq("market_resolve_security"),any())).thenReturn(Map.of("items",List.of()));
  when(reader.read(eq("market_search"),any())).thenReturn(Map.of("items",List.of(Map.of("dataset_code","collected_quotes"))));
  when(reader.read(eq("market_get_snapshot"),any())).thenReturn(Map.of("items",List.of(Map.of("source_id",17,"observation_date","2026-11-10"))));
  when(reader.read(eq("market_get_day_snapshot"),any())).thenReturn(Map.of("complete",true,"items",List.of(Map.of("name","A","close",2),Map.of("name","B","close",3))));
  var data=new LocalMarketWorkflow(reader,auth,scopes,new ObjectMapper()).supply("tenant","user",null,"分析本地行情");
  assertThat(data.get("market:snapshot:collected_quotes:17:2026-11-10").recordCount()).isEqualTo(2);
  verify(reader).read(eq("market_get_day_snapshot"),argThat(a->a.path("source_id").asInt()==17));
 }
 @Test void missingGrantRejectsBeforeAnyMarketRead(){
  var reader=mock(CodingResourceReader.class);var auth=mock(ResourceAuthorizationPort.class);when(auth.allowedIds(anyString(),anyString(),anyString(),anySet(),anySet())).thenReturn(Set.of());
  var workflow=new LocalMarketWorkflow(reader,auth,mock(AgentResourceScopePort.class),new ObjectMapper());
  assertThatThrownBy(()->workflow.supply("tenant","user",null,"分析行情")).isInstanceOf(SecurityException.class);verifyNoInteractions(reader);
 }
}
