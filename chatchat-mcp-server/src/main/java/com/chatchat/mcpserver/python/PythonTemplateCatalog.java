package com.chatchat.mcpserver.python;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PythonTemplateCatalog {

    private final PythonTemplateAssetRepository templates;
    @org.springframework.beans.factory.annotation.Autowired
    private PythonEnvironmentRepository environments;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chatchat.common.security.InternalCredentialProperties credentials;

    /** Called only after the published template and its backing asset have been authorized. */
    public String readableSource(PythonTemplate template) {
        return com.chatchat.common.security.InternalSecretCipher.decryptIfNecessary(template.getSourceCiphertext(), credentials.resolvedSecret());
    }

    public PythonEnvironment publishedEnvironment(PythonTemplate template) {
        return environments.findById(template.getEnvironmentId()).filter(environment -> "PUBLISHED".equals(environment.getStatus())).orElse(null);
    }

    public List<PythonTemplate> listPublished() {
        return templates.findByStatus("PUBLISHED");
    }
}
