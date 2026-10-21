package com.chatchat.api.assets;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssetAnalysisInputsTest {
    @Test void twoRegisteredExcelReferencesAreDescribedWithoutReadingTheirContents() {
        byte[] first={1,2},second={3,4};
        when(assets.get(owner,id)).thenReturn(new UserAssetService.File(id,"DATASET","first.xlsx","application/xlsx",UserAssetService.hash(first),2,"AVAILABLE","","","upload",1));
        when(assets.get(owner,other)).thenReturn(new UserAssetService.File(other,"DATASET","second.xlsx","application/xlsx",UserAssetService.hash(second),2,"AVAILABLE","","","upload",1));
        var manifest=inputs.describe(owner,List.of(id,other));
        assertThat(manifest).extracting(com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileInput::fileName).containsExactly("first.xlsx","second.xlsx");
        assertThat(manifest).extracting(com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileInput::reference)
            .containsExactly("user-asset:"+id+":"+UserAssetService.hash(first),"user-asset:"+other+":"+UserAssetService.hash(second));
        verify(assets,never()).read(any(),anyString());
        when(assets.read(owner,id)).thenReturn(first);when(assets.read(owner,other)).thenReturn(second);
        assertThat(inputs.load(owner,List.of(id,other))).containsOnlyKeys(manifest.get(0).reference(),manifest.get(1).reference());
    }
    final UserAssetService assets=mock(UserAssetService.class);
    final AssetAnalysisInputs inputs=new AssetAnalysisInputs(assets);
    final UserAssetService.Owner owner=new UserAssetService.Owner("tenant","alice");
    final String id="a".repeat(64),other="b".repeat(64);
    UserAssetService.File file(String id,byte[] bytes) {
        return new UserAssetService.File(id,"DOCUMENT","data.csv","text/csv",UserAssetService.hash(bytes),bytes.length,"AVAILABLE","","","upload",1);
    }
    @Test void multiChunkOriginalReassemblesByteForByteWithoutLibraryExpansion() {
        byte[] bytes=new byte[1024*1024+23];new Random(42).nextBytes(bytes);
        when(assets.get(owner,id)).thenReturn(file(id,bytes));when(assets.read(owner,id)).thenReturn(bytes);
        var sources=inputs.load(owner,List.of(id));var dataset=sources.values().iterator().next();
        var rows=dataset.handle().readPage(0,10).rows();assertThat(rows).hasSize(2);
        var output=new java.io.ByteArrayOutputStream();
        for(var row:rows) {
            byte[] chunk=Base64.getDecoder().decode((String)row.get("contentBase64"));
            assertThat(row).containsEntry("chunkSha256",UserAssetService.hash(chunk)).containsEntry("sha256",UserAssetService.hash(bytes));
            output.writeBytes(chunk);
        }
        assertThat(output.toByteArray()).isEqualTo(bytes);
        verify(assets,never()).list(any(),any(),any(),anyInt());
    }
    @Test void allOwnersAreCheckedBeforeReadingAnyObject() {
        when(assets.get(owner,id)).thenReturn(file(id,new byte[2]));
        when(assets.get(owner,other)).thenThrow(new SecurityException("foreign"));
        assertThatThrownBy(()->inputs.load(owner,List.of(id,other))).isInstanceOf(SecurityException.class);
        verify(assets,never()).read(any(),anyString());
    }
    @Test void duplicateOversizeAndTaskCodeAreRejected() {
        assertThatThrownBy(()->inputs.validate(owner,List.of(id,id))).isInstanceOf(IllegalArgumentException.class);
        when(assets.get(owner,id)).thenReturn(file(id,new byte[16*1024*1024+1]));
        assertThatThrownBy(()->inputs.load(owner,List.of(id))).hasMessageContaining("16 MiB");
        when(assets.get(owner,id)).thenReturn(new UserAssetService.File(id,"CODE","main.py","text/plain",id,1,"AVAILABLE","task","run","code",1));
        assertThatThrownBy(()->inputs.load(owner,List.of(id))).hasMessageContaining("supported analysis files");
        verify(assets,never()).read(any(),anyString());
    }
    @Test void imageInterpretationKeepsItsOriginalHashAndIsExplicitlyDerived() {
        byte[] bytes={1,2,3};String hash=UserAssetService.hash(bytes);
        when(assets.get(owner,id)).thenReturn(new UserAssetService.File(id,"DOCUMENT","image.png","image/png",hash,bytes.length,"AVAILABLE","","","upload",1));
        when(assets.read(owner,id)).thenReturn(bytes);
        var vision=mock(com.chatchat.chat.image.ImageUnderstandingService.class);
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("vision",vision);
        org.springframework.test.util.ReflectionTestUtils.setField(inputs,"images",beans.getBeanProvider(com.chatchat.chat.image.ImageUnderstandingService.class));
        when(vision.toAnalysisView(any())).thenReturn(new com.chatchat.chat.image.ImageUnderstandingService.ImageAnalysisView(
            "interpretation",id,"tenant","alice","question","auto","other","","Visible image",Map.of(),0.8,"vision","COMPLETED",java.time.Instant.now(),java.time.Instant.now()));
        var sources=inputs.load(owner,List.of(id),"selected-model","question");
        verify(vision).analyzeStoredBytes(id,"tenant","alice","image.png","image/png",hash,bytes,"question","selected-model");
        assertThat(sources).hasSize(2);
        var derived=sources.get("user-asset:"+id+":"+hash+":interpretation").handle().readPage(0,1).rows().get(0);
        assertThat(derived).containsEntry("sourceSha256",hash).containsEntry("role","UNTRUSTED_IMAGE_INTERPRETATION_REQUIRES_ORIGINAL_VERIFICATION");
        verify(assets,never()).store(any(),any(),any(),any(),any(),any(),any(),any());
    }
}
