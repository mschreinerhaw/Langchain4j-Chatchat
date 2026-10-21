package com.chatchat.api.assets;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class UserSpaceOrganizationTest {
    UserAssetService assets;JdbcTemplate jdbc;UserSpaceOrganization organization;
    final UserAssetService.Owner alice=new UserAssetService.Owner("tenant","alice"),bob=new UserAssetService.Owner("tenant","bob");
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:folders-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);jdbc=new JdbcTemplate(db);
        jdbc.execute("create table ds_python_script(id varchar primary key,tenant_id varchar,owner_id varchar)");
        jdbc.execute("create table ds_python_data_file(id varchar primary key,tenant_id varchar,owner_id varchar)");
        assets=new UserAssetService(jdbc,new DataSourceTransactionManager(db),new UserAssetServiceTest.MemoryBlobs(),new AssetStorageProperties());organization=new UserSpaceOrganization(jdbc,assets);
    }
    @Test void foldersAndMixedFilesPersistWithoutMovingOriginalBytes(){
        var folder=organization.create(alice,"documents","Business rules");var nested=organization.create(alice,folder.id(),"Monthly");
        var file=assets.upload(alice,"rules.md",new byte[]{65,66});jdbc.update("insert into ds_python_script values('script','tenant','alice')");
        organization.move(alice,nested.id(),List.of(new UserSpaceOrganization.Entry("asset",file.id()),new UserSpaceOrganization.Entry("python-script","script")));
        var restored=new UserSpaceOrganization(jdbc,assets).list(alice);
        assertThat(restored.folders()).hasSize(2);assertThat(restored.locations()).hasSize(2);
        assertThat(assets.read(alice,file.id())).isEqualTo(new byte[]{65,66});
        assertThatThrownBy(()->organization.remove(alice,folder.id())).hasMessageContaining("409");
        assertThatThrownBy(()->organization.remove(alice,nested.id())).hasMessageContaining("409");
        organization.move(alice,"documents",List.of(new UserSpaceOrganization.Entry("asset",file.id()),new UserSpaceOrganization.Entry("python-script","script")));
        organization.remove(alice,nested.id());organization.remove(alice,folder.id());assertThat(organization.list(alice).folders()).isEmpty();
    }
    @Test void otherOwnersAndTenantsCannotReadFoldersOrMoveNativeAndLegacyFiles(){
        var folder=organization.create(alice,"documents","Private");var file=assets.upload(alice,"private.md",new byte[]{65});
        jdbc.update("insert into ds_python_data_file values('legacy','tenant','alice')");
        assertThat(organization.list(bob).folders()).isEmpty();
        assertThatThrownBy(()->organization.create(bob,folder.id(),"forged")).hasMessageContaining("404");
        for(var owner:List.of(bob,new UserAssetService.Owner("other","alice"))){
            assertThatThrownBy(()->organization.move(owner,"data",List.of(new UserSpaceOrganization.Entry("asset",file.id())))).hasMessageContaining("404");
            assertThatThrownBy(()->organization.move(owner,"data",List.of(new UserSpaceOrganization.Entry("python-data","legacy")))).hasMessageContaining("404");
            assertThatThrownBy(()->organization.remove(owner,folder.id())).hasMessageContaining("404");
        }
    }
    @Test void validationChecksAllEntriesBeforeChangingAnyLocation(){
        var file=assets.upload(alice,"owned.csv",new byte[]{65});var foreign=assets.upload(bob,"foreign.csv",new byte[]{66});
        assertThatThrownBy(()->organization.move(alice,"data",List.of(new UserSpaceOrganization.Entry("asset",file.id()),new UserSpaceOrganization.Entry("asset",foreign.id())))).hasMessageContaining("404");
        assertThat(organization.list(alice).locations()).isEmpty();
        assertThatThrownBy(()->organization.create(alice,"root","../folder")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->organization.remove(alice,"scripts")).isInstanceOf(IllegalArgumentException.class);
        organization.create(alice,"root","Rules");assertThatThrownBy(()->organization.create(alice,"root","rules")).hasMessageContaining("409");
    }
    @Test void legacyNamesAreAcceptedOnlyAsServerResolvedAliasesWhileFolderOwnershipUsesStableId(){
        var stable=new UserAssetService.Owner("tenant","stable-user-id");
        jdbc.update("insert into ds_python_script values('legacy-name-script','tenant','legacy-username')");
        var entry=new UserSpaceOrganization.Entry("python-script","legacy-name-script");
        assertThatThrownBy(()->organization.move(stable,"scripts",List.of(entry))).hasMessageContaining("404");
        organization.move(stable,"scripts",List.of(entry),Set.of("stable-user-id","legacy-username"));
        assertThat(organization.list(stable).locations()).hasSize(1);
        assertThat(organization.list(new UserAssetService.Owner("tenant","legacy-username")).locations()).isEmpty();
    }
}
