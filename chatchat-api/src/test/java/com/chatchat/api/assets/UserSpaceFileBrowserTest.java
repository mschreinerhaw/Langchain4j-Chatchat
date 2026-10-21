package com.chatchat.api.assets;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class UserSpaceFileBrowserTest {
    UserAssetService assets;UserSpaceOrganization organization;UserSpaceFileBrowser browser;JdbcTemplate jdbc;
    final UserAssetService.Owner owner=new UserAssetService.Owner("tenant","stable-id");
    final Set<String> aliases=Set.of("stable-id","alice");
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:browser-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);jdbc=new JdbcTemplate(db);
        jdbc.execute("create table ds_python_data_file(id varchar primary key,tenant_id varchar,owner_id varchar,file_name varchar,file_size bigint,status varchar,purpose varchar,updated_at timestamp)");
        jdbc.execute("create table ds_python_script(id varchar primary key,tenant_id varchar,owner_id varchar,file_name varchar,source_code varchar,status varchar,updated_at timestamp,asset_id varchar,folder_id varchar,title varchar,current_version int)");
        assets=new UserAssetService(jdbc,new DataSourceTransactionManager(db),new UserAssetServiceTest.MemoryBlobs(),new AssetStorageProperties());
        organization=new UserSpaceOrganization(jdbc,assets);browser=new UserSpaceFileBrowser(jdbc,assets,organization);
    }
    UserSpaceFileBrowser.Page page(String folder,String q,String sort,int page,int size){return browser.list(owner,aliases,folder,q,sort,page,size);}
    void legacy(){
        jdbc.update("insert into ds_python_data_file values('legacy','tenant','alice','old.csv',3,'AVAILABLE',null,CURRENT_TIMESTAMP)");
        jdbc.update("insert into ds_python_data_file values('cache','tenant','alice','copy.csv',3,'AVAILABLE','user-space:abc',CURRENT_TIMESTAMP)");
        jdbc.update("insert into ds_python_script values('script','tenant','alice','work.py','print(1)','DRAFT',CURRENT_TIMESTAMP,'asset','','Work',2)");
        jdbc.update("insert into ds_python_script values('foreign','tenant','bob','secret.py','secret','DRAFT',CURRENT_TIMESTAMP,'asset','','Secret',1)");
    }
    @Test void paginatesMixedUserFilesBeforeReturningPayloadsAndExcludesAllRuntimeKinds(){
        for(int i=0;i<37;i++)assets.upload(owner,"input-"+String.format("%02d",i)+".csv",new byte[]{65});
        for(var kind:UserAssetService.Kind.values()){
            assets.store(owner,kind,"task","run","upload","generated-"+kind+".txt","text/plain",new byte[]{66});
            if(kind!=UserAssetService.Kind.DOCUMENT)assets.store(owner,kind,"","","system","internal-"+kind+".txt","text/plain",new byte[]{67});
        }
        legacy();
        var first=page("all","","name",1,25);var second=page("all","","name",2,25);
        assertThat(first.total()).isEqualTo(39);assertThat(first.totalFiles()).isEqualTo(39);assertThat(first.items()).hasSize(25);assertThat(second.items()).hasSize(14);
        assertThat(first.items()).extracting(UserSpaceFileBrowser.Item::sourceId).doesNotContainAnyElementsOf(second.items().stream().map(UserSpaceFileBrowser.Item::sourceId).toList());
        assertThat(first.folderCounts()).containsEntry("data",38L).containsEntry("scripts",1L).doesNotContainKeys("logs","history","artifacts");
        assertThat(page("all","generated","updated",1,10).total()).isZero();
        // Hidden files are retained and authorized reads still work for task recovery.
        assertThat(assets.list(owner,null,"task",100)).hasSize(6);
    }
    @Test void folderAndSearchApplyBeforeCountingPagingAndSortingAndDeletionClampsPage(){
        var folder=organization.create(owner,"data","Monthly");var input=assets.upload(owner,"special.csv",new byte[]{65});legacy();
        organization.move(owner,folder.id(),List.of(new UserSpaceOrganization.Entry("asset",input.id()),new UserSpaceOrganization.Entry("python-data","legacy")),aliases);
        var result=page(folder.id(),"special","size",99,10);
        assertThat(result.total()).isEqualTo(1);assertThat(result.page()).isEqualTo(1);assertThat(result.items().get(0).sourceId()).isEqualTo(input.id());
        assertThat(result.folderCounts()).containsEntry(folder.id(),2L);
        assets.delete(owner,input.id());assertThat(page(folder.id(),"special","updated",99,10).items()).isEmpty();
    }
    @Test void paginationCannotExposeAnotherOwnersFolderOrFilesAndPayloadHasNoStoragePath(){
        var foreign=new UserAssetService.Owner("tenant","bob");var folder=organization.create(foreign,"root","Private");
        assets.upload(foreign,"secret.md",new byte[]{65});legacy();
        assertThatThrownBy(()->page(folder.id(),"","updated",1,25)).hasMessageContaining("404");
        assertThat(page("all","secret","updated",1,25).items()).isEmpty();
        assertThat(page("data","","updated",1,25).items().get(0).original().toString()).doesNotContain("storagePath","pythonPath");
        assertThat(browser.list(new UserAssetService.Owner("other","stable-id"),aliases,"all","","updated",1,25).total()).isZero();
    }
}
