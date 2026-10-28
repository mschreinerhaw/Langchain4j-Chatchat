package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.resolution.TableLocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MetadataForeignKeyTest {
    @Test void collectsRealJdbcCompositeForeignKeysAndDistinguishesEmptyFromUnavailable() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            db.createStatement().execute("CREATE TABLE parent (id INT, part INT, PRIMARY KEY(id,part))");
            db.createStatement().execute("CREATE TABLE child (id INT, part INT, CONSTRAINT child_parent FOREIGN KEY(id,part) REFERENCES parent(id,part))");
            var child = new TableLocation("a","PUBLIC","PUBLIC","CHILD","TABLE",null,0);
            var parent = new TableLocation("a","PUBLIC","PUBLIC","PARENT","TABLE",null,0);
            var keys = MetadataIndexService.importedKeys(db.getMetaData(),List.of(child,parent));
            assertThat(keys.get(MetadataIndex.relationshipKey("PUBLIC","PUBLIC","CHILD"))).hasSize(2)
                .extracting(MetadataForeignKey::referencedTable).containsOnly("PARENT");
            assertThat(keys.get(MetadataIndex.relationshipKey("PUBLIC","PUBLIC","PARENT"))).isEmpty();
            assertThat(keys.get(MetadataIndex.relationshipKey("PUBLIC","PUBLIC","CHILD"))).extracting(MetadataForeignKey::keySequence).containsExactly(1,2);
        }
    }
    @Test void legacyStoredIndexesWithoutForeignKeysRemainReadable() throws Exception {
        var json = new ObjectMapper();
        var index = new MetadataIndex("a","mysql",List.of(),Map.of(),Map.of(),Map.of(),List.of(),1,false,null);
        var old = json.readTree(json.writeValueAsString(index)); ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("foreignKeys");
        assertThat(json.treeToValue(old,MetadataIndex.class).foreignKeys()).isEmpty();
    }
}
