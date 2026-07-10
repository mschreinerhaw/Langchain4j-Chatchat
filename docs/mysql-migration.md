# MySQL and OpenSearch Runtime Modes

Runtime mode is selected by configuration files, not startup flags.

Use the preset templates as the entry point:

```text
application-lucene-h2.template
application-opensearch-mysql.template
```

Copy the desired preset to the active Spring Boot config file, for example:

```text
application-dev.yml
application-prod.yml
```

For local development, the current dev configuration is already set to:

```text
API: MySQL + OpenSearch
MCP: MySQL + OpenSearch
```

Passwords should use the encrypted variables where possible:

```text
CHATCHAT_API_MYSQL_ENCRYPTED_PASSWORD
CHATCHAT_MCP_MYSQL_ENCRYPTED_PASSWORD
CHATCHAT_OPENSEARCH_ENCRYPTED_PASSWORD
CHATCHAT_EMBEDDING_ENCRYPTED_API_KEY
```

URL and username remain readable in configuration for easier troubleshooting.

To migrate existing H2 data to MySQL once, enable:

```properties
CHATCHAT_DATASOURCE_MIGRATION_ENABLED=true
```

After migration succeeds, turn it back off.
