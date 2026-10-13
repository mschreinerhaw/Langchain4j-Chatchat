package com.chatchat.api.datascience.sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SqlOutputBundleTest {
    final ObjectMapper mapper=new ObjectMapper();
    String bundle(List<Map<String,Object>> files) throws Exception {return mapper.writeValueAsString(Map.of("schemaVersion","sql-studio.output.v1","files",files));}
    @Test void rawAndFencedBundlesSupportManySafeRelativeSqlPaths() throws Exception {
        String value=bundle(List.of(Map.of("path","sql/a.sql","content","SELECT 1;"),Map.of("path","数据/b.sql","content","SELECT 2;")));
        assertThat(SqlOutputBundle.parse(value,mapper)).hasSize(2);assertThat(SqlOutputBundle.parse("```json\n"+value+"\n```",mapper)).hasSize(2);
    }
    @Test void rejectsInvalidContractPathTypeDuplicateAndEmptySource() throws Exception {
        for(String path:List.of("../x.sql","/x.sql","sql\\x.sql","a/%2f.sql","sql/nul.sql","sql/x.py","a//x.sql")) {
            String value=bundle(List.of(Map.of("path",path,"content","SELECT 1;")));
            assertThatThrownBy(()->SqlOutputBundle.parse(value,mapper)).isInstanceOf(ResponseStatusException.class);
        }
        String duplicate=bundle(List.of(Map.of("path","a.sql","content","SELECT 1;"),Map.of("path","A.SQL","content","SELECT 2;")));
        assertThatThrownBy(()->SqlOutputBundle.parse(duplicate,mapper)).isInstanceOf(ResponseStatusException.class);
        String empty=bundle(List.of(Map.of("path","a.sql","content","")));
        assertThatThrownBy(()->SqlOutputBundle.parse(empty,mapper)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->SqlOutputBundle.parse("SELECT 1;",mapper)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->SqlOutputBundle.parse(duplicate+" {}",mapper)).isInstanceOf(ResponseStatusException.class);
    }
}
