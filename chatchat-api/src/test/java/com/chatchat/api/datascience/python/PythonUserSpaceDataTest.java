package com.chatchat.api.datascience.python;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class PythonUserSpaceDataTest {
    final PythonDataFileRepository repo=mock(PythonDataFileRepository.class);
    final McpPythonControlPlanePort mcp=mock(McpPythonControlPlanePort.class);
    final PythonDataScienceService service=new PythonDataScienceService(null,null,null,null,null,null,mcp,null,null,new ObjectMapper(),repo,new PythonDataScienceProperties(),null,null);
    final String id="a".repeat(64);final byte[] bytes="amount\n1.25".getBytes();
    @Test void exactBytesAreTransferredWithOwnerAndStableOriginalRelation(){
        when(repo.findByTenantIdAndOwnerIdOrderByCreatedAtDesc("tenant","alice")).thenReturn(List.of());
        when(repo.saveAndFlush(any())).thenAnswer(call->{PythonDataFileEntity file=call.getArgument(0);file.setId("cache");return file;});
        when(repo.save(any())).thenAnswer(call->call.getArgument(0));
        when(mcp.uploadDataFile("tenant","alice","cache","input.csv",UserAssetService.hash(bytes),bytes))
            .thenReturn(new McpPythonControlPlanePort.DataFileResult("cache","/private","/data/input.csv",bytes.length,UserAssetService.hash(bytes),"AVAILABLE"));
        var result=service.prepareUserSpaceData("tenant","alice",id,"input.csv",bytes);
        assertThat(result.getSourceAssetId()).isEqualTo(id);assertThat(result.getFileHash()).isEqualTo(UserAssetService.hash(bytes));assertThat(result.getStatus()).isEqualTo("AVAILABLE");
        when(repo.findByTenantIdAndOwnerIdOrderByCreatedAtDesc("tenant","alice")).thenReturn(List.of(result));
        assertThat(service.prepareUserSpaceData("tenant","alice",id,"input.csv",bytes)).isSameAs(result);
        verify(mcp,times(1)).uploadDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void unsafeIdentityAndUnsupportedCodeCannotProvisionExecutionData(){
        assertThatThrownBy(()->service.prepareUserSpaceData("tenant","alice","forged","input.csv",bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.prepareUserSpaceData("tenant","alice",id,"script.py",bytes)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mcp,repo);
    }
}
