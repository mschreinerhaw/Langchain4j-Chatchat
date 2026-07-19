# Logs

Runtime logs are written here.

Production application logs roll daily or at 100 MB and are gzip-compressed into `logs/archive/`.
Compressed logs are retained for 14 days with a 2 GB total size cap. These defaults can be overridden
with JVM properties `LOG_MAX_FILE_SIZE`, `LOG_MAX_HISTORY`, `LOG_TOTAL_SIZE_CAP`, and `LOG_DIR`.
