"""Trusted SQL parser entrypoint. Reads JSON on stdin; never executes generated SQL."""
import json
import sys

import sqlglot
from sqlglot import exp
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import traverse_scope
from sqlglot.optimizer.normalize_identifiers import normalize_identifiers
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


def structure_review(tree, tables, dialect, path):
    """Static evidence/risk review only. Absence of keys never proves uniqueness."""
    issues, relationships, grains = [], [], []
    root = normalize_identifiers(tree.copy(), dialect=dialect or None)

    def report(code, message, **detail):
        issues.append(dict(path=path, category="STRUCTURE", severity="WARNING", code=code,
                           message=message, sqlExecuted=False, **detail))

    def resolved(source):
        if not isinstance(source, exp.Table):
            return None
        matches = [t for t in tables if t.get("tableName") == source.name
                   and (not source.db or source.db in (t.get("schema"), t.get("database")))
                   and (not source.catalog or source.catalog == t.get("database"))]
        return matches[0] if len(matches) == 1 else None

    def primary(table):
        return set(table.get("primaryKeys") or []) if table and table.get("primaryKeyStatus") == "COLLECTED_VISIBLE" else set()

    def conjuncts(node):
        if isinstance(node, exp.Paren):
            return conjuncts(node.this)
        if isinstance(node, exp.And):
            return conjuncts(node.this) + conjuncts(node.expression)
        return [node]

    for scope in traverse_scope(root):
        select = scope.expression
        if not isinstance(select, exp.Select):
            continue
        sources = {alias: resolved(source) for alias, (_, source) in scope.selected_sources.items()}
        for alias, table in sources.items():
            key = primary(table)
            grains.append(dict(alias=alias, tableName=table.get("tableName") if table else None,
                               primaryKeys=sorted(key), status="DECLARED_KEY" if key else "UNKNOWN",
                               businessGrain="UNCONFIRMED"))
            # Only producer-declared partition columns are used, never guessed date/id names.
            partitions = set(table.get("partitionKeys") or []) if table and table.get("partitionKeyStatus") == "COLLECTED_VISIBLE" else set()
            if partitions:
                def filtered(node):
                    if isinstance(node, exp.Paren):
                        return filtered(node.this)
                    if isinstance(node, exp.And):
                        return filtered(node.this) | filtered(node.expression)
                    if isinstance(node, exp.Or):
                        return filtered(node.this) & filtered(node.expression)
                    if not isinstance(node, (exp.EQ, exp.GT, exp.GTE, exp.LT, exp.LTE, exp.Between, exp.In)):
                        return set()
                    column = node.this
                    if not isinstance(column, exp.Column) or not (column.table == alias or not column.table and len(sources) == 1):
                        return set()
                    bounds = [v for k, v in node.args.items() if k != "this"]
                    values = [n for v in bounds for n in (v if isinstance(v, list) else [v]) if isinstance(n, exp.Expression)]
                    if any(v.find(exp.Literal, exp.Placeholder, exp.Parameter) for v in values):
                        return {column.name}
                    return set()
                where = select.args.get("where")
                covered = filtered(where.this) if where else set()
                if not partitions.intersection(covered):
                    report("MISSING_PARTITION_FILTER", "Declared partition columns have no guaranteed bounded WHERE predicate; review scan scope.",
                           alias=alias, columns=sorted(partitions))
        for join in select.args.get("joins") or []:
            right = join.this.alias_or_name
            on = join.args.get("on")
            pairs = []
            for term in conjuncts(on):
                if isinstance(term, exp.EQ) and isinstance(term.this, exp.Column) and isinstance(term.expression, exp.Column):
                    a, b = term.this, term.expression
                    if a.table and b.table and a.table != b.table and right in (a.table, b.table):
                        pairs.append((a, b))
            if not pairs:
                report("JOIN_RELATION_UNCONFIRMED", "JOIN has no explicit, unambiguous equality-key evidence; review relationship and row grain.", alias=right)
                relationships.append(dict(alias=right, status="UNCONFIRMED", evidenceSource="NONE"))
                continue
            for left in sorted({c.table for pair in pairs for c in pair if c.table != right}):
                selected = [(a, b) if a.table == left else (b, a) for a, b in pairs if {a.table, b.table} == {left, right}]
                columns = {left: {a.name for a, _ in selected}, right: {b.name for _, b in selected}}
                verified = False
                for owner, target in [(left, right), (right, left)]:
                    own, other = sources.get(owner), sources.get(target)
                    if not own or not other or own.get("bindingId") != other.get("bindingId"):
                        continue
                    groups = {}
                    for fk in own.get("foreignKeys") or []:
                        if fk.get("source") == "JDBC_IMPORTED_KEYS" and fk.get("constraintRef"):
                            groups.setdefault(fk["constraintRef"], []).append(fk)
                    actual = {(a.name, b.name) if owner == left else (b.name, a.name) for a, b in selected}
                    for keys in groups.values():
                        if all(k.get("referencedTable") == other.get("tableName")
                               and k.get("referencedSchema") == other.get("schema")
                               and k.get("referencedDatabase") == other.get("database") for k in keys):
                            verified |= bool(keys) and {(k["column"], k["referencedColumn"]) for k in keys}.issubset(actual)
                relationships.append(dict(left=left, right=right, status="DECLARED_FOREIGN_KEY" if verified else "INFERRED",
                                          evidenceSource="JDBC_IMPORTED_KEYS" if verified else "SQL_EQUALITY_ONLY"))
                if not verified:
                    report("JOIN_RELATION_UNCONFIRMED", "Equality columns alone do not establish a business relationship; no complete visible foreign-key evidence matches.", left=left, right=right)
                for owner, other in [(left, right), (right, left)]:
                    unique = primary(sources.get(other))
                    preserves = bool(unique) and unique.issubset(columns[other])
                    aggregates = [n for projection in select.expressions for n in projection.find_all(exp.Sum, exp.Avg, exp.Count)
                                  if not isinstance(n.this, exp.Distinct)]
                    measures_owner = any(isinstance(n, exp.Count) and isinstance(n.this, exp.Star)
                                         or any(c.table == owner or not c.table for c in n.find_all(exp.Column)) for n in aggregates)
                    if not preserves and measures_owner:
                        report("JOIN_FANOUT_AGGREGATION_RISK", "The joined side is not proven unique on these keys. Aggregation may repeat source amounts; confirm grain or pre-aggregate before joining.",
                               measureSource=owner, joinedSource=other, cardinality="UNCONFIRMED")
    return issues, relationships, grains


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
    table_evidence = schema.get("tables", []) if isinstance(schema, dict) else []
    structural_diagnostics, relationships, grains = [], [], []
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
    references = []
    temporary_tables = {}
    for file in files:
        try:
            source = file["content"]
            if not isinstance(source, str) or not source.strip() or len(source) > 200000:
                raise ValueError("Invalid SQL source")
            trees = sqlglot.parse(source, read=dialect or None, error_level=sqlglot.ErrorLevel.RAISE)
            trees = [tree for tree in trees if tree is not None]
            if not trees or any(tree.find(exp.Command) for tree in trees):
                raise ValueError("Statement is not supported by the SQL parser")
            for tree in trees:
                # Locally declared temporary objects are source declarations, never MCP assets.
                declaration = None
                if isinstance(tree, exp.Create) and tree.find(exp.TemporaryProperty):
                    declaration = tree.this.this if isinstance(tree.this, exp.Schema) else tree.this
                into = tree.args.get("into")
                if isinstance(into, exp.Into) and into.args.get("temporary"):
                    declaration = into.this
                if isinstance(declaration, exp.Table):
                    identity = normalize_identifiers(declaration.copy(), dialect=dialect or None)
                    columns = [column.name for column in tree.find_all(exp.ColumnDef)]
                    query = tree.expression if isinstance(tree, exp.Create) else tree
                    if not columns and isinstance(query, exp.Query):
                        columns = query.named_selects
                    if not columns or "*" in columns:
                        raise ValueError("Temporary table requires explicit column names")
                    temporary_tables[identity.name] = (identity, columns)
                for scope in traverse_scope(tree):
                    for _, source in scope.selected_sources.values():
                        if isinstance(source, exp.Table):
                            identity = normalize_identifiers(source.copy(), dialect=dialect or None)
                            if not identity.db and not identity.catalog and identity.name in temporary_tables:
                                continue
                            if source.this.args.get("temporary"):
                                raise ValueError("Temporary table has no local declaration: " + source.sql())
                            reference = {"tableName": identity.name, "schema": identity.db,
                                         "database": identity.catalog,
                                         "quoted": bool(source.this.args.get("quoted"))}
                            if reference not in references:
                                references.append(reference)
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
                for identifier, columns in temporary_tables.values():
                    schema.add_table(identifier, {column: "UNKNOWN" for column in columns})
                # The caller supplies only authorized, versioned metadata. No DB discovery here.
                for tree in trees:
                    for scope in traverse_scope(tree):
                        for _, source in scope.selected_sources.values():
                            if isinstance(source, exp.Table) and not schema.find(source):
                                raise ValueError("Table metadata has not been resolved in the authorized scope: " + source.sql())
                    qualify(tree.copy(), dialect=dialect or None, schema=schema,
                            validate_qualify_columns=True, identify=False)
            for tree in trees:
                review, links, keys = structure_review(tree, table_evidence, dialect, file["path"])
                structural_diagnostics.extend(review); relationships.extend(links); grains.extend(keys)
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
            "requiresBusinessEvidence": derived, "businessLiteralValues": sorted(business_literals),
            "references": references, "structuralDiagnostics": structural_diagnostics,
            "relationships": relationships, "grains": grains,
            "structureReviewRequired": bool(structural_diagnostics), "businessValidation": "NOT_EVALUATED"}


if __name__ == "__main__":
    raw = sys.stdin.buffer.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError("SQL toolchain input exceeds limit")
    sys.stdout.write(json.dumps(validate(json.loads(raw)), ensure_ascii=True))
