package com.chatchat.api.assets;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.image.ImageUnderstandingService;
import com.chatchat.chat.image.ImageAnalysisResultEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssetImageControllerTest {
    MockMvc mvc;UserAssetService assets;ImageUnderstandingService images;
    final UserAssetService.Owner alice=new UserAssetService.Owner("tenant","alice");
    final byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aY5cAAAAASUVORK5CYII=");
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:asset-image-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);
        assets=new UserAssetService(new JdbcTemplate(db),new DataSourceTransactionManager(db),new UserAssetServiceTest.MemoryBlobs(),new AssetStorageProperties());
        images=mock(ImageUnderstandingService.class);var access=mock(ObjectAccessService.class);
        when(access.tenant(any())).thenReturn("tenant");when(access.user(any())).thenAnswer(call->((HttpServletRequest)call.getArgument(0)).getHeader("X-Test-Principal"));
        mvc=MockMvcBuilders.standaloneSetup(new AssetImageController(assets,access,images,new ObjectMapper())).build();
    }
    @Test void exactOwnedSiloBytesReachExistingImageServiceAndResultIsArchived()throws Exception{
        var file=assets.upload(alice,"clipboard.png",png);
        var result=new ImageAnalysisResultEntity();
        when(images.analyzeStoredBytes(eq(file.id()),eq("tenant"),eq("alice"),eq("clipboard.png"),eq("image/png"),eq(file.sha256()),aryEq(png),eq("read image"),eq("vision"))).thenReturn(result);
        when(images.toAnalysisView(result)).thenReturn(new ImageUnderstandingService.ImageAnalysisView("analysis",file.id(),"tenant","alice","read image","auto","screenshot","text","summary",Map.of(),0.8,"multimodal_llm","COMPLETED",null,null));
        mvc.perform(post("/api/v1/data-science/user-assets/"+file.id()+"/image-analysis").header("X-Test-Principal","alice").contentType("application/json")
            .accept("application/json").content("{\"question\":\"read image\",\"modelName\":\"vision\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.fileId").value(file.id()));
        var contexts=assets.list(alice,UserAssetService.Kind.CONTEXT,null,10);Assertions.assertEquals(1,contexts.size());
        Assertions.assertTrue(new String(assets.read(alice,contexts.get(0).id()),java.nio.charset.StandardCharsets.UTF_8).contains("summary"));
    }
    @Test void crossOwnerAndForgedIdAreRejectedBeforeAnyModelCall()throws Exception{
        var file=assets.upload(alice,"clipboard.png",png);
        mvc.perform(post("/api/v1/data-science/user-assets/"+file.id()+"/image-analysis?userId=alice").header("X-Test-Principal","bob").contentType("application/json").content("{}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/data-science/user-assets/forged/image-analysis").header("X-Test-Principal","alice").contentType("application/json").content("{}"))
            .andExpect(status().isNotFound());verifyNoInteractions(images);
    }
    @Test void imageSignatureMustMatchTheDeclaredExtension(){
        Assertions.assertThrows(IllegalArgumentException.class,()->assets.upload(alice,"fake.png","text".getBytes()));
    }
}
