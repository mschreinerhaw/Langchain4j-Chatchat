package com.chatchat.api.assets;

import com.chatchat.api.datascience.python.PythonDataScienceService;
import com.chatchat.api.security.ObjectAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserAssetPythonBridgeTest {
    final UserAssetService assets=mock(UserAssetService.class);final PythonDataScienceService python=mock(PythonDataScienceService.class);
    final ObjectAccessService access=mock(ObjectAccessService.class);final MockHttpServletRequest http=new MockHttpServletRequest();
    final String id="a".repeat(64);final UserAssetService.Owner owner=new UserAssetService.Owner("tenant","stable-user-id");
    UserAssetPythonController controller;
    @BeforeEach void setup(){when(access.tenant(http)).thenReturn("tenant");when(access.user(http)).thenReturn("stable-user-id");when(access.legacyUser(http)).thenReturn("alice");controller=new UserAssetPythonController(assets,access,python);}
    @Test void foreignOriginalIsRejectedBeforeReadingOrCallingMcp(){
        when(assets.get(owner,id)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->controller.prepare(http,id)).hasMessageContaining("404");verifyNoInteractions(python);verify(assets,never()).read(any(),anyString());
    }
    @Test void pythonReceivesExactAuthorizedOriginalAndStableAssetId(){
        byte[] bytes="a,b\n1,2".getBytes();when(assets.get(owner,id)).thenReturn(file(bytes));when(assets.read(owner,id)).thenReturn(bytes);
        controller.prepare(http,id);verify(python).prepareUserSpaceData("tenant","alice",id,"input.csv",bytes);
    }
    @Test void executionCopyFailureCannotSilentlyDeleteTheOriginal(){
        when(assets.get(owner,id)).thenReturn(file(new byte[]{1}));
        var controller=new UserAssetController(assets,access,new AssetStorageProperties());var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("python",python);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"python",beans.getBeanProvider(PythonDataScienceService.class));
        doThrow(new IllegalStateException("MCP unavailable")).when(python).removeUserSpaceData("tenant","alice",id);
        assertThatThrownBy(()->controller.delete(http,id)).hasMessageContaining("MCP unavailable");verify(assets,never()).delete(any(),anyString());
    }
    UserAssetService.File file(byte[] bytes){return new UserAssetService.File(id,"DOCUMENT","input.csv","text/csv",UserAssetService.hash(bytes),bytes.length,"AVAILABLE","","","upload",1);}
}
