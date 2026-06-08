# Langchain4j-Chatchat

## Production Release Package

Build the formal ChatChat application release package:

```powershell
.\scripts\package-deploy.ps1
```

Skip Maven build and package the existing `chatchat-api/target` jar:

```powershell
.\scripts\package-deploy.ps1 -SkipBuild
```

Reuse an existing `chatchat-api/web-app/dist` frontend build and skip npm execution during Maven packaging:

```powershell
.\scripts\package-deploy.ps1 -SkipWebBuild
```

Generated artifacts:

```text
dist/chatchat-1.0.0-SNAPSHOT/
dist/chatchat-1.0.0-SNAPSHOT.tar.gz
dist/chatchat-1.0.0-SNAPSHOT.tar.gz.sha256
dist/chatchat-1.0.0-SNAPSHOT.zip
dist/chatchat-1.0.0-SNAPSHOT.zip.sha256
```

Package layout:

```text
bin/
config/
data/
logs/
run/
lib/app/chatchat.jar
lib/drivers/
README.md
VERSION
```

Linux:

```bash
chmod +x bin/*.sh
./bin/start.sh
./bin/status.sh
./bin/stop.sh
./bin/restart.sh
```

Windows:

```powershell
.\bin\start.bat
.\bin\status.bat
.\bin\stop.bat
.\bin\restart.bat
```

Use `JAVA_OPTS` for JVM options and `APP_ARGS` for extra Spring Boot arguments. For MySQL deployment, keep `config/application.yml`, configure `config/application-mysql.yml`, and start with:

```powershell
$env:APP_ARGS = "--spring.profiles.active=mysql"
```
