package com.chatchat.mcpserver.sql.template;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StudioPreparedSqlTest {
    @Test void valuesNeverBecomeSqlText(){
        String attack="x' OR 1=1 --";var plan=StudioPreparedSql.compile("SELECT * FROM entries WHERE name={{name}} AND id={{id}}",Map.of("name",attack,"id",2));
        assertEquals("SELECT * FROM entries WHERE name=? AND id=?",plan.sql());assertEquals(List.of(attack,2),plan.values());assertFalse(plan.sql().contains(attack));
    }
    @Test void repeatedNullableValuesAndCommentExamplesAreHandled(){
        var args=new LinkedHashMap<String,Object>();args.put("category",null);
        var plan=StudioPreparedSql.compile("-- {{ignored}}\nSELECT * FROM entries WHERE ({{category}} IS NULL OR category={{category}}) /* {{ignored}} */",args);
        assertEquals(Arrays.asList(null,null),plan.values());assertEquals(List.of("category","category"),plan.names());
        assertTrue(plan.sql().contains("-- {{ignored}}"));assertTrue(plan.sql().contains("category=?)"));
    }
    @Test void wholeQuotedPlaceholdersBecomeBindingsWithoutDoubleQuoting(){
        var p=StudioPreparedSql.compile("SELECT * FROM entries WHERE day='{{day}}' AND label=\"literal\"",Map.of("day","2001-01-01"));
        assertEquals("SELECT * FROM entries WHERE day=? AND label=\"literal\"",p.sql());assertEquals(List.of("2001-01-01"),p.values());
    }
    @Test void identifiersAndEmbeddedFragmentsCannotBeBound(){
        assertThrows(IllegalArgumentException.class,()->StudioPreparedSql.compile("SELECT `{{field}}` FROM entries",Map.of("field","name")));
        assertThrows(IllegalArgumentException.class,()->StudioPreparedSql.compile("SELECT * FROM entries WHERE name LIKE '%{{name}}%'",Map.of("name","x")));
        assertThrows(IllegalArgumentException.class,()->StudioPreparedSql.compile("SELECT {{missing}}",Map.of()));
    }
    @Test void collectionsExpandToScalarPlaceholdersAndCannotContainObjects(){
        var p=StudioPreparedSql.compile("SELECT * FROM entries WHERE id IN ({{ids}})",Map.of("ids",List.of(1,2,3)));
        assertEquals("SELECT * FROM entries WHERE id IN (?, ?, ?)",p.sql());assertEquals(List.of(1,2,3),p.values());
        assertThrows(IllegalArgumentException.class,()->StudioPreparedSql.compile("SELECT {{obj}}",Map.of("obj",Map.of("sql","SELECT 1"))));
        assertThrows(IllegalArgumentException.class,()->StudioPreparedSql.compile("SELECT {{n}}",Map.of("n",Double.NaN)));
    }
    @Test void escapedSqlLiteralsArePreserved(){
        var p=StudioPreparedSql.compile("SELECT 'it''s safe', 'a\\\'b', {{id}}",Map.of("id",4));
        assertEquals("SELECT 'it''s safe', 'a\\\'b', ?",p.sql());assertEquals(List.of(4),p.values());
    }
}
