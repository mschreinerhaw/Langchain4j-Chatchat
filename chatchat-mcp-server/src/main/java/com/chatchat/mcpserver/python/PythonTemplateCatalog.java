package com.chatchat.mcpserver.python;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PythonTemplateCatalog {

    private final PythonTemplateAssetRepository templates;

    public List<PythonTemplate> listPublished() {
        return templates.findByStatus("PUBLISHED");
    }
}
