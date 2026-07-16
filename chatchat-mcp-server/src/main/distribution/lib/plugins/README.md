# MCP plugin libraries

Place optional shared dependency jars in this directory.

The release startup scripts launch the MCP server through Spring Boot `PropertiesLauncher` and
add `lib/plugins/` to the application class path. You can therefore add or replace an SDK or a
dependency required by an external driver without rebuilding `chatchat-mcp-server.jar`.

Restart the MCP server after changing files in this directory. Runtime replacement of classes in
an already running JVM is not supported.

Keep database driver jars in `lib/drivers/{databaseType}`. Put only dependencies that must be
visible to the application, or intentionally shared by multiple drivers, in `lib/plugins/`.
