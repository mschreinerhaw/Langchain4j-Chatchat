package com.chatchat.mcpserver.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MetadataStandardDictionaryRepository
    extends JpaRepository<MetadataStandardDictionary, String> {
}
