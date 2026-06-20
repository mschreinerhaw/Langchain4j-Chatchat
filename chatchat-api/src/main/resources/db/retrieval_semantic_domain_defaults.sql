-- Supplemental default entries extracted from SQL / Spark / Connector semantic materials.
-- Safe to run repeatedly: every insert is guarded by NOT EXISTS.

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'sql', 'query,select,database,table,spark sql', 2, 65, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'sql' AND expand_words = 'query,select,database,table,spark sql');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'ddl', 'create,alter,drop,database,table,view,function', 2, 64, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'ddl' AND expand_words = 'create,alter,drop,database,table,view,function');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'dml', 'insert,update,delete,load data,truncate', 2, 63, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'dml' AND expand_words = 'insert,update,delete,load data,truncate');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'spark', 'distributed,cluster,shuffle,partition,repartition', 2, 62, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'spark' AND expand_words = 'distributed,cluster,shuffle,partition,repartition');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'connector', 'jdbc,filesystem,mongodb,hbase,elasticsearch,file source,database connector', 2, 61, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'connector' AND expand_words = 'jdbc,filesystem,mongodb,hbase,elasticsearch,file source,database connector');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'ingestion', 'load data,import,etl,file ingestion,file input', 2, 60, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'ingestion' AND expand_words = 'load data,import,etl,file ingestion,file input');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'search', 'lucene,fulltext,full text,index,retrieval,inverted index', 2, 59, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'search' AND expand_words = 'lucene,fulltext,full text,index,retrieval,inverted index');

INSERT INTO query_expand_rule (intent, source_word, expand_words, weight, priority, enabled, version, created_at, updated_at)
SELECT 'DATA_ISSUE', 'retrieval', 'document search,scoring,bm25,lucene,index', 2, 58, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM query_expand_rule WHERE source_word = 'retrieval' AND expand_words = 'document search,scoring,bm25,lucene,index');

INSERT INTO chunk_type_rule (chunk_type, keywords, pattern, weight, priority, enabled, version, created_at, updated_at)
SELECT 'sql_chunk', 'create table,alter table,insert into,select query,group by,join,window function,cte', '', 3, 65, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM chunk_type_rule WHERE chunk_type = 'sql_chunk');

INSERT INTO chunk_type_rule (chunk_type, keywords, pattern, weight, priority, enabled, version, created_at, updated_at)
SELECT 'connector_chunk', 'jdbc,filesystem,elasticsearch,hbase,mongodb,ftp,mysql connector,file source', '', 3, 64, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM chunk_type_rule WHERE chunk_type = 'connector_chunk');

INSERT INTO chunk_type_rule (chunk_type, keywords, pattern, weight, priority, enabled, version, created_at, updated_at)
SELECT 'spark_chunk', 'spark sql,partition,shuffle,cluster by,distribute by,repartition', '', 3, 63, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM chunk_type_rule WHERE chunk_type = 'spark_chunk');

INSERT INTO chunk_type_rule (chunk_type, keywords, pattern, weight, priority, enabled, version, created_at, updated_at)
SELECT 'search_chunk', 'lucene,index,full text,bm25,inverted index,retrieval,document search', '', 3, 62, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM chunk_type_rule WHERE chunk_type = 'search_chunk');

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '数据库定义', '数据库定义', 'zh', 'create database', 'ddl_database,创建数据库,修改数据库,删除数据库,alter database,drop database', 'ddl_database', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '数据库定义' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '表定义', '表定义', 'zh', 'create table', 'ddl_table,创建表,修改表,删除表,alter table,drop table', 'ddl_table', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '表定义' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '视图定义', '视图定义', 'zh', 'create view', 'ddl_view,创建视图,修改视图,删除视图,alter view,drop view', 'ddl_view', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '视图定义' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '函数定义', '函数定义', 'zh', 'create function', 'ddl_function,创建函数,删除函数,drop function', 'ddl_function', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '函数定义' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '插入数据', '插入数据', 'zh', 'insert into', 'insert_data,写入数据,导入数据,data insert,data load', 'insert_data', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '插入数据' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '查询数据', '查询数据', 'zh', 'select query', 'select_query,数据检索,data retrieval', 'select_query', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '查询数据' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '聚合', '聚合', 'zh', 'aggregation', 'aggregation,分组统计,group by,sum,count,avg', 'aggregation', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '聚合' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '窗口函数', '窗口函数', 'zh', 'window function', 'window_function,分析函数,over partition', 'window_function', 'sql', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '窗口函数' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT 'spark sql', 'spark sql', 'bilingual', 'structured data processing', 'spark_sql,分布式sql引擎,distributed sql engine', 'spark_sql', 'spark', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = 'spark sql' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '分区', '分区', 'zh', 'partition', 'partitioning,数据分片,distribute by,cluster by', 'partitioning', 'spark', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '分区' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '文件系统连接器', '文件系统连接器', 'zh', 'filesystem connector', 'filesystem_connector,本地文件读取,分布式文件读取,file source', 'filesystem_connector', 'data_source', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '文件系统连接器' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT 'jdbc连接器', 'jdbc连接器', 'bilingual', 'database connector', 'jdbc_connector,数据库连接,jdbc connector', 'jdbc_connector', 'database', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = 'jdbc连接器' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT 'lucene搜索连接器', 'lucene搜索连接器', 'bilingual', 'lucene connector', 'lucene_connector,索引检索,inverted index search', 'lucene_connector', 'search', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = 'lucene搜索连接器' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT '全文检索', '全文检索', 'zh', 'full text search', 'full_text_search,fulltext search', 'full_text_search', 'search', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = '全文检索' AND builtin = TRUE);

INSERT INTO semantic_lexicon_entry
  (term, normalized_term, language, mapped_term, aliases, category, domain, weight, priority, builtin, enabled, version, created_at, updated_at)
SELECT 'elasticsearch连接器', 'elasticsearch连接器', 'bilingual', 'elasticsearch connector', 'elasticsearch_connector,es搜索引擎连接器,search engine sink', 'elasticsearch_connector', 'search', 2, 10, TRUE, TRUE, 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM semantic_lexicon_entry WHERE normalized_term = 'elasticsearch连接器' AND builtin = TRUE);
