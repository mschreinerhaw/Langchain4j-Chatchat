# Python system example data

The CSV, JSON, TXT, LOG, XLS, XLSX, Parquet and ZIP fixtures are small synthetic
datasets generated for this project. `data/sample_sales.orc` is the Apache ORC
`examples/TestOrcFile.test1.orc` interoperability fixture, renamed for the
system example catalog. Its upstream project is licensed under Apache-2.0:

https://github.com/apache/orc/blob/main/examples/TestOrcFile.test1.orc

These files are application resources. Importing one copies it through the same
tenant/user-scoped MCP upload path used by normal user data.
