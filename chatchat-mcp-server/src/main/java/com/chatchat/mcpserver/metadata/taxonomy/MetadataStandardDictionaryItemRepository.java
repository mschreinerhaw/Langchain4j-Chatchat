package com.chatchat.mcpserver.metadata.taxonomy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MetadataStandardDictionaryItemRepository
    extends JpaRepository<MetadataStandardDictionaryItem, String> {

    List<MetadataStandardDictionaryItem> findByDictionaryIdIn(Collection<String> dictionaryIds);
}
