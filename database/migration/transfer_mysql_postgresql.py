#!/usr/bin/env python3
"""Copy ChatChat API/MCP rows between pre-initialized MySQL and PostgreSQL databases."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo


IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
MODULE_ANCHORS = {
    "api": {"knowledge_ir_unit", "skill_config", "resource_grant"},
    "mcp": {"mcp_service_config", "mcp_sql_datasource", "mcp_business_category"},
}


def required_identifier(value: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise ValueError(f"Unsupported SQL identifier: {value!r}")
    return value


def mysql_identifier(value: str) -> str:
    return f"`{required_identifier(value)}`"


def secret(name: str) -> str:
    file_path = os.getenv(f"{name}_FILE")
    if file_path:
        return Path(file_path).read_text(encoding="utf-8").rstrip("\r\n")
    return os.getenv(name, "")


def connect_mysql(module: str):
    try:
        import pymysql
    except ImportError as exc:
        raise RuntimeError("Install dependency: python3 -m pip install PyMySQL") from exc
    settings = {
        "host": os.getenv("MYSQL_HOST", "127.0.0.1"),
        "port": int(os.getenv("MYSQL_PORT", "3306")),
        "user": os.getenv("MYSQL_USER", f"chatchat_{module}"),
        "password": secret("MYSQL_PASSWORD") or os.getenv("MYSQL_PWD", ""),
        "database": os.getenv("MYSQL_DATABASE", f"live_runtime_{module}"),
        "charset": "utf8mb4",
        "autocommit": False,
    }
    if os.getenv("MYSQL_SSL_CA"):
        settings["ssl"] = {"ca": os.environ["MYSQL_SSL_CA"]}
    return pymysql.connect(**settings)


def connect_postgresql(module: str):
    try:
        import psycopg
    except ImportError as exc:
        raise RuntimeError("Install dependency: python3 -m pip install 'psycopg[binary]'") from exc
    return psycopg.connect(
        host=os.getenv("PGHOST", "127.0.0.1"),
        port=int(os.getenv("PGPORT", "5432")),
        user=os.getenv("PGUSER", f"chatchat_{module}"),
        password=secret("PGPASSWORD"),
        dbname=os.getenv("PGDATABASE", f"live_runtime_{module}"),
        sslmode=os.getenv("PGSSLMODE", "prefer"),
        options="-c timezone=UTC",
        autocommit=False,
    )


def execute_scalar(connection, query: str, params=()):
    with connection.cursor() as cursor:
        cursor.execute(query, params)
        return cursor.fetchone()[0]


def database_name(connection, engine: str) -> str:
    return execute_scalar(connection, "select database()" if engine == "mysql" else "select current_database()")


def list_tables(connection, engine: str, schema: str) -> set[str]:
    query = (
        "select table_name from information_schema.tables "
        "where table_schema=%s and table_type='BASE TABLE'"
    )
    with connection.cursor() as cursor:
        cursor.execute(query, (database_name(connection, engine) if engine == "mysql" else schema,))
        return {row[0] for row in cursor.fetchall()}


def columns(connection, engine: str, schema: str, table: str) -> dict[str, str]:
    if engine == "mysql":
        query = (
            "select column_name, data_type, extra from information_schema.columns "
            "where table_schema=%s and table_name=%s order by ordinal_position"
        )
        params = (database_name(connection, engine), table)
    else:
        query = (
            "select column_name, data_type, is_generated from information_schema.columns "
            "where table_schema=%s and table_name=%s order by ordinal_position"
        )
        params = (schema, table)
    with connection.cursor() as cursor:
        cursor.execute(query, params)
        rows = cursor.fetchall()
    return {
        name: data_type.lower()
        for name, data_type, generated in rows
        if not (engine == "mysql" and "generated" in generated.lower())
        and not (engine == "postgresql" and generated != "NEVER")
    }


def foreign_key_dependencies(connection, engine: str, schema: str) -> dict[str, set[str]]:
    if engine == "mysql":
        query = (
            "select table_name, referenced_table_name from information_schema.key_column_usage "
            "where table_schema=%s and referenced_table_name is not null"
        )
        params = (database_name(connection, engine),)
    else:
        query = (
            "select child.relname, parent.relname from pg_constraint fk "
            "join pg_class child on child.oid=fk.conrelid "
            "join pg_namespace child_ns on child_ns.oid=child.relnamespace "
            "join pg_class parent on parent.oid=fk.confrelid "
            "where fk.contype='f' and child_ns.nspname=%s"
        )
        params = (schema,)
    with connection.cursor() as cursor:
        cursor.execute(query, params)
        rows = cursor.fetchall()
    dependencies = defaultdict(set)
    for child, parent in rows:
        if child == parent:
            raise ValueError(f"Self-referencing foreign key requires manual migration: {child}")
        dependencies[child].add(parent)
    return dependencies


def parent_first_order(tables: set[str], dependencies: dict[str, set[str]]) -> list[str]:
    remaining = {table: dependencies.get(table, set()) & tables for table in tables}
    result = []
    while remaining:
        ready = sorted(table for table, parents in remaining.items() if not parents)
        if not ready:
            raise ValueError(f"Foreign-key cycle requires manual migration: {', '.join(sorted(remaining))}")
        result.extend(ready)
        for table in ready:
            del remaining[table]
        for parents in remaining.values():
            parents.difference_update(ready)
    return result


def quoted_table(engine: str, schema: str, table: str):
    if engine == "mysql":
        return mysql_identifier(table)
    from psycopg import sql
    return sql.SQL("{}.{}").format(sql.Identifier(schema), sql.Identifier(table))


def row_exists(connection, engine: str, schema: str, table: str) -> bool:
    if engine == "mysql":
        query = f"select 1 from {mysql_identifier(table)} limit 1"
    else:
        from psycopg import sql
        query = sql.SQL("select 1 from {} limit 1").format(quoted_table(engine, schema, table))
    with connection.cursor() as cursor:
        cursor.execute(query)
        return cursor.fetchone() is not None


def row_count(connection, engine: str, schema: str, table: str) -> int:
    if engine == "mysql":
        query = f"select count(*) from {mysql_identifier(table)}"
    else:
        from psycopg import sql
        query = sql.SQL("select count(*) from {}").format(quoted_table(engine, schema, table))
    return execute_scalar(connection, query)


def convert(value, source_engine: str, target_type: str, mysql_zone: ZoneInfo):
    if value is None:
        return None
    if source_engine == "mysql" and target_type == "boolean":
        return bool(int.from_bytes(value, "big")) if isinstance(value, bytes) else bool(value)
    if source_engine == "postgresql" and target_type == "bit" and isinstance(value, bool):
        return int(value)
    if isinstance(value, datetime):
        if source_engine == "mysql" and target_type == "timestamp with time zone":
            return (value if value.tzinfo else value.replace(tzinfo=mysql_zone)).astimezone(timezone.utc)
        if source_engine == "postgresql" and target_type in {"datetime", "timestamp"} and value.tzinfo:
            return value.astimezone(mysql_zone).replace(tzinfo=None)
    if isinstance(value, memoryview):
        return value.tobytes()
    if target_type in {"json", "jsonb"} and source_engine == "mysql":
        from psycopg.types.json import Json, Jsonb
        parsed = json.loads(value) if isinstance(value, str) else value
        return Jsonb(parsed) if target_type == "jsonb" else Json(parsed)
    if source_engine == "postgresql" and isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return value


def copy_table(source, target, source_engine: str, target_engine: str, schema: str,
               table: str, column_types: dict[str, str], batch_size: int, mysql_zone: ZoneInfo) -> int:
    names = list(column_types)
    if source_engine == "mysql":
        import pymysql
        source_cursor = source.cursor(pymysql.cursors.SSCursor)
        source_query = f"select {', '.join(map(mysql_identifier, names))} from {mysql_identifier(table)}"
    else:
        from psycopg import sql
        source_cursor = source.cursor(name=f"transfer_{table}")
        source_query = sql.SQL("select {} from {}").format(
            sql.SQL(", ").join(map(sql.Identifier, names)), quoted_table(source_engine, schema, table)
        )
    copied = 0
    try:
        source_cursor.execute(source_query)
        if target_engine == "postgresql":
            from psycopg import sql
            statement = sql.SQL("copy {} ({}) from stdin").format(
                quoted_table(target_engine, schema, table), sql.SQL(", ").join(map(sql.Identifier, names))
            )
            with target.cursor() as target_cursor, target_cursor.copy(statement) as writer:
                while rows := source_cursor.fetchmany(batch_size):
                    for row in rows:
                        writer.write_row(tuple(convert(value, source_engine, column_types[name], mysql_zone)
                                               for name, value in zip(names, row)))
                    copied += len(rows)
        else:
            placeholders = ", ".join(["%s"] * len(names))
            statement = (f"insert into {mysql_identifier(table)} "
                         f"({', '.join(map(mysql_identifier, names))}) values ({placeholders})")
            with target.cursor() as target_cursor:
                while rows := source_cursor.fetchmany(batch_size):
                    converted = [tuple(convert(value, source_engine, column_types[name], mysql_zone)
                                       for name, value in zip(names, row)) for row in rows]
                    target_cursor.executemany(statement, converted)
                    copied += len(rows)
    finally:
        source_cursor.close()
    actual = row_count(target, target_engine, schema, table)
    if copied != actual:
        raise RuntimeError(f"Row-count mismatch for {table}: copied={copied}, target={actual}")
    return copied


def reset_postgresql_sequences(target, schema: str, tables: list[str]) -> None:
    from psycopg import sql
    for table in tables:
        with target.cursor() as cursor:
            cursor.execute(
                "select column_name from information_schema.columns "
                "where table_schema=%s and table_name=%s and identity_generation is not null",
                (schema, table),
            )
            identity_columns = [row[0] for row in cursor.fetchall()]
            for column in identity_columns:
                cursor.execute("select pg_get_serial_sequence(%s, %s)",
                               (sql.Identifier(schema, table).as_string(target), column))
                sequence = cursor.fetchone()[0]
                if not sequence:
                    continue
                cursor.execute(sql.SQL("select max({}) from {}").format(
                    sql.Identifier(column), quoted_table("postgresql", schema, table)
                ))
                maximum = cursor.fetchone()[0]
                cursor.execute("select setval(%s::regclass, %s, %s)",
                               (sequence, maximum if maximum is not None else 1, maximum is not None))
def migrate(args) -> None:
    source_engine, target_engine = (
        ("mysql", "postgresql") if args.direction == "mysql-to-postgresql"
        else ("postgresql", "mysql")
    )
    schema = required_identifier(os.getenv("PGSCHEMA", "public"))
    mysql_zone = ZoneInfo(args.mysql_timezone)
    source = connect_mysql(args.module) if source_engine == "mysql" else connect_postgresql(args.module)
    target = connect_mysql(args.module) if target_engine == "mysql" else connect_postgresql(args.module)
    try:
        target_name = database_name(target, target_engine)
        if source_engine == "mysql":
            with source.cursor() as cursor:
                cursor.execute("set session transaction isolation level repeatable read")
                cursor.execute("start transaction with consistent snapshot")
        else:
            source.rollback()
            with source.cursor() as cursor:
                cursor.execute("set transaction isolation level repeatable read read only")
        source_name = database_name(source, source_engine)
        source_tables = list_tables(source, source_engine, schema)
        target_tables = list_tables(target, target_engine, schema)
        anchors = MODULE_ANCHORS[args.module]
        if not anchors.issubset(source_tables) or not anchors.issubset(target_tables):
            raise ValueError(f"{args.module} schema anchors are missing from source or target: {sorted(anchors)}")
        if source_tables != target_tables:
            raise ValueError(
                f"Schema tables differ; initialize/migrate both sides to the same version. "
                f"Only in source: {sorted(source_tables - target_tables)}; "
                f"only in target: {sorted(target_tables - source_tables)}"
            )
        table_types = {}
        for table in sorted(source_tables):
            required_identifier(table)
            source_columns = columns(source, source_engine, schema, table)
            target_columns = columns(target, target_engine, schema, table)
            if set(source_columns) != set(target_columns):
                raise ValueError(
                    f"Columns differ for {table}; only in source: {sorted(set(source_columns) - set(target_columns))}; "
                    f"only in target: {sorted(set(target_columns) - set(source_columns))}"
                )
            table_types[table] = {name: target_columns[name] for name in source_columns}
        order = parent_first_order(source_tables, foreign_key_dependencies(target, target_engine, schema))
        nonempty = [table for table in order if row_exists(target, target_engine, schema, table)]
        target.rollback()
        print(f"{source_engine}:{source_name} -> {target_engine}:{target_name}; "
              f"module={args.module}; tables={len(order)}", flush=True)
        if args.dry_run:
            if nonempty:
                print(f"Target already contains data in {len(nonempty)} tables; a write run will require --replace-target.",
                      flush=True)
            for table in order:
                print(f"{table}: source={row_count(source, source_engine, schema, table)}", flush=True)
            print("Dry run completed; target unchanged.", flush=True)
            return
        if nonempty and not args.replace_target:
            raise ValueError(
                f"Target contains data in {len(nonempty)} tables (first: {nonempty[0]}). "
                "Use --replace-target for a deliberate return migration."
            )
        if args.replace_target:
            for table in reversed(order):
                if target_engine == "mysql":
                    statement = f"delete from {mysql_identifier(table)}"
                else:
                    from psycopg import sql
                    statement = sql.SQL("delete from {}").format(quoted_table(target_engine, schema, table))
                with target.cursor() as cursor:
                    cursor.execute(statement)
        total = 0
        for table in order:
            copied = copy_table(source, target, source_engine, target_engine, schema, table,
                                table_types[table], args.batch_size, mysql_zone)
            total += copied
            print(f"{table}: {copied} rows", flush=True)
        if target_engine == "postgresql":
            reset_postgresql_sequences(target, schema, order)
        target.commit()
        print(f"Migration completed: {total} rows across {len(order)} tables.", flush=True)
    except Exception:
        target.rollback()
        raise
    finally:
        source.rollback()
        source.close()
        target.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--direction", choices=("mysql-to-postgresql", "postgresql-to-mysql"), required=True)
    parser.add_argument("--module", choices=("api", "mcp"), required=True)
    parser.add_argument("--replace-target", action="store_true", help="Delete target rows before copying; explicit return migration")
    parser.add_argument("--dry-run", action="store_true", help="Check schemas and show source row counts without writing")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--mysql-timezone", default="Asia/Shanghai", help="Zone used for MySQL DATETIME values")
    args = parser.parse_args()
    if args.batch_size < 1:
        parser.error("--batch-size must be positive")
    try:
        migrate(args)
    except Exception as exc:
        print(f"Migration failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
