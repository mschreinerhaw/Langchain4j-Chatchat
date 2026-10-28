"""Trusted SQL parser entrypoint. Reads JSON on stdin; never executes generated SQL."""
import json
import sys

import sqlglot
from sqlglot import exp
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import traverse_scope
from sqlglot.schema import MappingSchema


def failure(file, error):
    """Bounded source locations; no source excerpt or duplicated history."""
    result = {"path": file.get("path", ""), "status": "FAILED",
              "category": "SYNTAX" if isinstance(error, (SyntaxError, sqlglot.errors.ParseError)) else "VALIDATION",
              "errorType": type(error).__name__, "message": str(error)[:2000]}
    line, column = getattr(error, "lineno", None), getattr(error, "offset", None)
    if isinstance(error, sqlglot.errors.ParseError) and error.errors:
        line, column = error.errors[0].get("line"), error.errors[0].get("col")
    if isinstance(line, int) and line > 0:
        result["line"] = line
    if isinstance(column, int) and column > 0:
        result["column"] = column
    return result


def validate(request):
    dialect = request.get("dialect")
    if dialect == "python":
        import ast
        diagnostics = []
        files = request.get("files")
        if not isinstance(files, list) or not 1 <= len(files) <= 32:
            raise ValueError("Expected 1 to 32 Python files")
        for file in files:
            try:
                # Parse source only. Never import or execute generated Python.
                ast.parse(file["content"], filename=file["path"])
                diagnostics.append({"path": file["path"], "status": "PARSED"})
            except (SyntaxError, ValueError, TypeError) as error:
                diagnostics.append(failure(file, error))
        return {"parser": "python-ast", "version": sqlglot.__version__, "sqlExecuted": False,
                "codeExecuted": False, "valid": all(item["status"] == "PARSED" for item in diagnostics),
                "fieldValidation": "NOT_EVALUATED", "diagnostics": diagnostics, "normalizedFiles": []}
    dialect = {"postgresql": "postgres", "ansi": ""}.get(dialect, dialect)
    if dialect not in {"spark", "hive", "postgres", "mysql", "oracle", "tsql", "trino", ""}:
        raise ValueError("Unsupported SQL dialect")
    files = request.get("files")
    if not isinstance(files, list) or not 1 <= len(files) <= 32:
        raise ValueError("Expected 1 to 32 SQL files")
    schema = request.get("schema") or {}
    require_business_evidence = isinstance(schema, dict) and schema.get("requireBusinessEvidence") is True
    derived, business_literals = False, set()
    if "tables" in schema:
        mapped = MappingSchema(dialect=dialect or None)
        for table in schema["tables"]:
            database, namespace = table.get("database"), table.get("schema")
            identifier = exp.Table(this=exp.to_identifier(table["tableName"], quoted=True))
            if namespace or database:
                identifier.set("db", exp.to_identifier(namespace or database, quoted=True))
            if database and namespace and database != namespace:
                identifier.set("catalog", exp.to_identifier(database, quoted=True))
            mapped.add_table(identifier, {column["name"]: "UNKNOWN" for column in table["columns"]})
        schema = mapped
    if schema and not isinstance(schema, MappingSchema):
        schema = MappingSchema(schema, dialect=dialect or None)
    diagnostics = []
    normalized = []
    for file in files:
        try:
            source = file["content"]
            if not isinstance(source, str) or not source.strip() or len(source) > 200000:
                raise ValueError("Invalid SQL source")
            trees = sqlglot.parse(source, read=dialect or None, error_level=sqlglot.ErrorLevel.RAISE)
            trees = [tree for tree in trees if tree is not None]
            if not trees or any(tree.find(exp.Command) for tree in trees):
                raise ValueError("Statement is not supported by the SQL parser")
            if require_business_evidence:
                for tree in trees:
                    analytical = any(tree.find(kind) for kind in (exp.AggFunc, exp.Case, exp.If, exp.Div, exp.Join))
                    analytical |= any(isinstance(node, exp.Func) and node.sql_name().upper() in {
                        "UNIX_TO_TIME", "TIME_TO_UNIX", "TIMESTAMP_DIFF", "DATE_DIFF", "DATEDIFF", "TIME_DIFF"
                    } for node in tree.walk())
                    if not analytical:
                        continue
                    derived = True
                    for literal in tree.find_all(exp.Literal):
                        # Limits, offsets and interval sizes do not define business outcomes.
                        if literal.find_ancestor(exp.Limit, exp.Offset, exp.Interval):
                            continue
                        parent = literal.parent
                        if not literal.is_string and literal.this in {"0", "1"} and (
                            isinstance(parent, exp.If) and literal in (parent.args.get("true"), parent.args.get("false"))
                            or isinstance(parent, exp.Case) and literal is parent.args.get("default")
                            or isinstance(parent, exp.Nullif)
                        ):
                            continue
                        business_literals.add(str(literal.this))
            if schema:
                # The caller supplies only authorized, versioned metadata. No DB discovery here.
                for tree in trees:
                    for scope in traverse_scope(tree):
                        for source in scope.sources.values():
                            if isinstance(source, exp.Table) and not schema.find(source):
                                raise ValueError("Table is outside the delivered metadata snapshot: " + source.sql())
                    qualify(tree.copy(), dialect=dialect or None, schema=schema,
                            validate_qualify_columns=True, identify=False)
            normalized.append({"path": file["path"], "content": ";\n".join(
                tree.sql(dialect=dialect or None, pretty=True) for tree in trees) + ";\n"})
            diagnostics.append({"path": file["path"], "status": "PARSED",
                                "fields": "CHECKED" if schema else "NOT_EVALUATED"})
        except Exception as error:
            diagnostics.append(failure(file, error))
    return {"parser": "sqlglot", "version": sqlglot.__version__, "sqlExecuted": False,
            "valid": all(item["status"] == "PARSED" for item in diagnostics),
            "fieldValidation": "CHECKED" if schema else "NOT_EVALUATED",
            "diagnostics": diagnostics, "normalizedFiles": normalized,
            "requiresBusinessEvidence": derived, "businessLiteralValues": sorted(business_literals)}


if __name__ == "__main__":
    raw = sys.stdin.buffer.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError("SQL toolchain input exceeds limit")
    sys.stdout.write(json.dumps(validate(json.loads(raw)), ensure_ascii=True))
