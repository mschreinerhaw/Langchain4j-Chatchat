create table if not exists agent_regression_semantic_profile (
    profile_key varchar(128) not null primary key,
    connector_terms_json text not null,
    relation_rules_json text not null
);

insert into agent_regression_semantic_profile
    (profile_key, connector_terms_json, relation_rules_json)
values (
    'default',
    '["jdbc","filesystem","file system","kafka","hdfs","mysql","spark sql","dataframe","insert into","create table"]',
    '[{"source":"spark sql","target":"jdbc","relation":"uses","sourceLabel":"Spark SQL","targetLabel":"JDBC"},{"source":"spark sql","target":"filesystem","relation":"reads","sourceLabel":"Spark SQL","targetLabel":"FileSystem"}]'
);
