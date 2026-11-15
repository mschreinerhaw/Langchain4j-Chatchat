# Embedded H2 transition

The default manager database is a local H2 file, not an external PostgreSQL instance. Fresh installations require no database service. The bundled H2 engine is version 2.3.232; JDBC uses synchronous writes and normal file locking. No H2 TCP server or Web console is enabled. MODE=PostgreSQL in the H2 file URL is a SQL compatibility setting; it does not connect to PostgreSQL.

## Existing PostgreSQL manager installations

1. Preserve the old Compose file, .env, Web image and dedicated PostgreSQL volume. Back up the old management database before switching.
2. Build the H2 candidate image offline and validate migration into an isolated empty volume first.
3. Stop only the manager Web container to freeze management writes. Do not stop unrelated application services.
4. Run MigratePg from the candidate image with LEGACY_DB_URL pointing to the OLD manager database, DB_PASSWORD from the protected environment, and /data mounted to a NEW empty management volume. MigratePg refuses a nonempty target. Never use it against an application database.
5. The tool copies users, nodes, entries and audit records, verifies counts, and resets identity sequences. It does not modify the old database. Sessions are intentionally invalidated; sign in again with the existing account.
6. Start the H2 Web service and verify login, permissions, record counts, discovery and persistence after restart. Only after verification, stop the old dedicated manager PostgreSQL container. Keep its data and backup for recovery.

The old Web image requires its OLD Compose configuration and PostgreSQL database to roll back. Do not point an old PostgreSQL-era image at the H2 volume or use ordinary image-only recovery across this engine transition. Future H2-to-H2 upgrades use the normal lifecycle script and matched backups.

## File backup

lifecycle.sh backup stops Web and executor, copies the closed H2 data directory plus environment/journals, creates a COMPLETE marker, and restarts services. This entails a short management outage but no application container outage. An incomplete backup directory is not a successful backup.

VerifyBackup requires an existing read-only snapshot URL (ACCESS_MODE_DATA=r and IFEXISTS=TRUE). Run it in a separate container with the backup data bind-mounted read-only. Never disable locking on the live database, share its data file between Web instances, or restore files while the Web process has them open.

Database file backups and engine versions must be matched. The local H2 password is not a substitute for protecting host storage and credential-containing backups.
