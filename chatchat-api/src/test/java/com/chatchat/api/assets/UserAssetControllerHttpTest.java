package com.chatchat.api.assets;

import com.chatchat.api.security.ObjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP argument binding + SQL owner policy. The fixture supplies authenticated principal attributes. */
class UserAssetControllerHttpTest {
    MockMvc mvc;UserAssetService assets;
    final String route="/api/v1/data-science/user-assets";
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:asset-http-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);
        var config=new AssetStorageProperties();assets=new UserAssetService(new JdbcTemplate(db),new DataSourceTransactionManager(db),new UserAssetServiceTest.MemoryBlobs(),config);
        var access=mock(ObjectAccessService.class);
        when(access.tenant(any())).thenReturn("tenant");
        when(access.user(any())).thenAnswer(call->((HttpServletRequest)call.getArgument(0)).getHeader("X-Test-Principal"));
        mvc=MockMvcBuilders.standaloneSetup(new UserAssetController(assets,access,config)).build();
    }
    @Test void uploadListFilterInfoDownloadDeleteBindRealHttpParameters()throws Exception{
        byte[] data="a,b\n1,2".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(multipart(route).file(new MockMultipartFile("file","input.csv","text/csv",data)).accept("application/json").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("AVAILABLE"));
        var file=assets.list(new UserAssetService.Owner("tenant","alice"),null,null,10).get(0);
        mvc.perform(get(route).param("kind","DOCUMENT").param("limit","10").accept("application/json").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(file.id()));
        mvc.perform(get(route+"/"+file.id()).header("X-Test-Principal","alice")).andExpect(status().isOk());
        mvc.perform(get(route+"/"+file.id()+"/content").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(content().bytes(data)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(delete(route+"/"+file.id()).header("X-Test-Principal","alice")).andExpect(status().isOk());
        mvc.perform(get(route+"/"+file.id()+"/content").header("X-Test-Principal","alice")).andExpect(status().isNotFound());
    }
    @Test void forgedUserQueryCannotOverrideAuthenticatedOwner()throws Exception{
        var file=assets.upload(new UserAssetService.Owner("tenant","alice"),"input.csv",new byte[]{65});
        mvc.perform(get(route+"/"+file.id()+"/content").param("userId","alice").header("X-Test-Principal","bob")).andExpect(status().isNotFound());
        mvc.perform(get(route+"/"+file.id()+"/content")).andExpect(status().isUnauthorized());
    }
    @Test void chatAliasUsesTheSameOwnerBoundary()throws Exception{
        var file=assets.upload(new UserAssetService.Owner("tenant","alice"),"input.md",new byte[]{65});
        mvc.perform(get("/api/v1/interactions/user-assets/"+file.id()+"/content").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{65}));
        mvc.perform(get("/api/v1/interactions/user-assets/"+file.id()+"/content").header("X-Test-Principal","bob"))
            .andExpect(status().isNotFound());
    }
    @Test void paginationStaysWithinTheAuthenticatedOwner()throws Exception{
        var alice=new UserAssetService.Owner("tenant","alice");
        var first=assets.upload(alice,"first.md",new byte[]{65});var second=assets.upload(alice,"second.md",new byte[]{66});
        assets.upload(new UserAssetService.Owner("tenant","bob"),"private.md",new byte[]{67});
        var ordered=assets.list(alice,null,null,10);
        mvc.perform(get(route).param("limit","1").param("offset","1").accept("application/json").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(ordered.get(1).id()));
    }
    @Test void defaultCatalogHidesRuntimeEvidenceWithoutDeletingOrBreakingExplicitTaskReads()throws Exception{
        var alice=new UserAssetService.Owner("tenant","alice");var upload=assets.upload(alice,"user.md",new byte[]{65});
        var runtime=assets.store(alice,UserAssetService.Kind.DOCUMENT,"task","run","snapshot","input.md","text/plain",new byte[]{66});
        mvc.perform(get(route).accept("application/json").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(upload.id()));
        mvc.perform(get(route).param("personalOnly","false").param("taskId","task").accept("application/json").header("X-Test-Principal","alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(runtime.id()));
        assertThat(assets.read(alice,runtime.id())).isEqualTo(new byte[]{66});
    }
}
