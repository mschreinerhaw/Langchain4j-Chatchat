package com.chatchat.api.datascience.sql;

import com.chatchat.api.assets.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.knowledgebase.search.document.infrastructure.extract.DocumentTextExtractor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Owner-selected reference rules/evidence, frozen with each SQL generation. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class SqlStudioUserDocuments {
    public record Selection(String fileId,String purpose) { }
    public record Snapshot(String fileId,String fileName,String sha256,long size,String purpose,String content) { }
    private final UserAssetService assets;
    private final DocumentTextExtractor extractor;
    private final RuntimeAssetArchive archive;
    public SqlStudioUserDocuments(UserAssetService assets,DocumentTextExtractor extractor,RuntimeAssetArchive archive) {
        this.assets=assets;this.extractor=extractor;this.archive=archive;
    }
    public List<Snapshot> capture(UserAssetService.Owner owner,List<Selection> selected) {
        if(selected==null || selected.isEmpty())return List.of();
        if(selected.size()>8 || selected.stream().anyMatch(s->s==null || s.fileId()==null || !s.fileId().matches("[a-f0-9]{64}")
            || !Set.of("RULES","EVIDENCE").contains(Objects.toString(s.purpose(),"")))
            || selected.stream().map(Selection::fileId).distinct().count()!=selected.size())
            throw new IllegalArgumentException("Choose up to 8 unique user documents and their purpose");
        var files=new ArrayList<UserAssetService.File>();long bytes=0;
        for(var item:selected) {
            var file=assets.get(owner,item.fileId());
            String ext=file.fileName().substring(file.fileName().lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            if(!"DOCUMENT".equals(file.kind()) || !Set.of("pdf","docx","xlsx","csv","txt","md","json","jsonl","sql").contains(ext))
                throw new IllegalArgumentException("Select PDF, DOCX, XLSX, CSV, TXT, MD, JSON, JSONL or SQL documents");
            bytes+=file.size();if(bytes>AssetAnalysisInputs.MAX_BYTES)throw new IllegalArgumentException("Selected documents exceed 16 MiB");
            files.add(file);
        }
        var result=new ArrayList<Snapshot>();long characters=0;
        for(int i=0;i<files.size();i++) {
            var file=files.get(i);byte[] original=assets.read(owner,file.id());
            String text;
            try {
                text=file.fileName().matches("(?i).*\\.(json|jsonl)$")?StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(original)).toString()
                    :extractor.extractText(new ByteArrayInputStream(original),file.fileName());
            }catch(Exception invalid){throw new IllegalArgumentException("Cannot read selected document: "+file.fileName(),invalid);}
            characters+=text.length();
            if(text.isBlank() || characters>180000)throw new IllegalArgumentException("Documents must contain readable text totaling at most 180000 characters; select smaller documents");
            result.add(new Snapshot(file.id(),file.fileName(),file.sha256(),file.size(),selected.get(i).purpose(),text));
        }
        return List.copyOf(result);
    }
    public void archive(KernelDataScope scope,String task,List<Snapshot> snapshots) {
        var owner=UserAssetService.Owner.of(scope);
        for(var snapshot:snapshots) {
            String source="sql-reference:"+snapshot.fileId()+":"+snapshot.sha256();
            if(archive.read(scope,task,source).isPresent())continue;
            var file=assets.get(owner,snapshot.fileId());
            if(!file.sha256().equals(snapshot.sha256()) || file.size()!=snapshot.size())throw new SecurityException("SQL reference source changed");
            archive.save(scope,task,UserAssetService.Kind.DOCUMENT,source,snapshot.fileName(),assets.read(owner,file.id()));
        }
    }
}
