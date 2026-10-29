"""Verify pinned research inputs; exports hashes/locations, never third-party source."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

REVISIONS = {
    "dbeaver": "bd2f6f8a3c350d4f9bb914879bba791c27039a45",
    "cloudbeaver": "5e30bbdc77b92c4b2276cf705d57ac42f0fa3684",
}
DB = "plugins/"
JAVA = "/src/org/jkiss/dbeaver/"
FILES = {
    "dbeaver": [
        (DB+"org.jkiss.dbeaver.model"+JAVA+"model/struct/DBSEntity.java", ["getAttributes(", "getConstraints(", "getAssociations(", "getReferences("]),
        (DB+"org.jkiss.dbeaver.model"+JAVA+"model/struct/DBSEntityAssociation.java", ["getReferencedConstraint(", "getAssociatedEntity("]),
        (DB+"org.jkiss.dbeaver.model"+JAVA+"model/struct/DBSEntityConstraint.java", ["getConstraintType("]),
        (DB+"org.jkiss.dbeaver.model"+JAVA+"model/navigator/DBNDatabaseNode.java", ["getChildren(", "needsInitialization(", "refreshNode("]),
        (DB+"org.jkiss.dbeaver.ui.navigator"+JAVA+"ui/navigator/database/DatabaseNavigatorContentProvider.java", ["getChildren(", "TreeLoadVisualizer.expandChildren"]),
        (DB+"org.jkiss.dbeaver.model.sql"+JAVA+"model/sql/semantics/SQLQueryModelRecognizer.java", ["recognizeQuery(", "collectQueryExpression("]),
        (DB+"org.jkiss.dbeaver.model.sql"+JAVA+"model/sql/semantics/completion/SQLQueryCompletionAnalyzer.java", ["prepareContextfulCompletion(", "prepareReplacementString("]),
        (DB+"org.jkiss.dbeaver.model.sql"+JAVA+"model/sql/semantics/completion/SQLQueryCompletionContext.java", ["prepareProposal(", "preparePrefixedColumnCompletions("]),
        (DB+"org.jkiss.dbeaver.model.ai"+JAVA+"model/ai/impl/AIEngineRequestFactory.java", ["AIEngineRequest build(", "ChatTruncator.builder("]),
        (DB+"org.jkiss.dbeaver.model.ai"+JAVA+"model/ai/impl/AIDatabaseSnapshotService.java", ["createDbSnapshot(", "appendObjectDescription(", "shouldSkipObject("]),
        (DB+"org.jkiss.dbeaver.model.ai"+JAVA+"model/ai/engine/AIDatabaseContext.java", ["class Builder", "setScope("]),
        (DB+"org.jkiss.dbeaver.model.ai"+JAVA+"model/ai/engine/openai/OpenAICompletionEngine.java", ["requestCompletion(", "requestCompletionStream("]),
        (DB+"org.jkiss.dbeaver.ui.ai"+JAVA+"ui/ai/controls/ScopeSelectorControl.java", ["ScopeSelectorControl(", "widgetSelected("]),
        (DB+"org.jkiss.dbeaver.ui.ai"+JAVA+"ui/ai/legacy/AILegacyTranslator.java", ["translateUserInputIntoSql(", "insertSqlCompletion("]),
        (DB+"org.jkiss.dbeaver.ui.editors.sql"+JAVA+"ui/editors/sql/semantics/SQLEditorSemanticMarkersManager.java", ["onScriptItemInvalidated(", "updateMarkers("]),
        (DB+"org.jkiss.dbeaver.model.ai"+JAVA+"model/ai/commands/SQLCommandAI.java", ["handleCommand(", "AICommandRequest("]),
    ],
    "cloudbeaver": [
        ("webapp/packages/core-navigation-tree/src/NodesManager/NavTreeResource.ts", ["refreshTree(", "markTreeOutdated(", "loadNodeChildren(", "insertSlice("]),
        ("webapp/packages/plugin-sql-editor-new/src/SQLEditor/useSqlDialectAutocompletion.ts", ["completionSource(", "insertCompletionText("]),
        ("webapp/packages/plugin-sql-editor/src/SqlEditor/useSqlEditor.ts", ["getHintProposals:", "getExecutingQuery("]),
        ("webapp/packages/plugin-sql-editor/src/SqlEditorService.ts", ["getAutocomplete(", "querySqlCompletionProposals("]),
        ("webapp/packages/core-sdk/src/queries/sql-editor/querySqlCompletionProposals.gql", ["query querySqlCompletionProposals"]),
        ("server/bundles/io.cloudbeaver.server/src/io/cloudbeaver/service/sql/impl/WebServiceSQL.java", ["getCompletionProposals(", "SQLQueryCompletionAnalyzer(", "SQLCompletionAnalyzer("]),
        ("server/bundles/io.cloudbeaver.server/src/io/cloudbeaver/service/sql/WebSQLCompletionContext.java", ["getExecutionContext(", "getSyntaxManager("]),
        ("server/bundles/io.cloudbeaver.server/src/io/cloudbeaver/service/sql/WebSQLCompletionContextScriptParser.java", ["obtainCompletionContext("]),
    ],
}


def git(directory, *args):
    # `git show revision:path` may stat the entire object expression on Windows,
    # hitting MAX_PATH even with a partial clone. Resolve then read the blob.
    if args[0] == "show":
        object_id = subprocess.check_output(["git", "-C", str(directory), "rev-parse", args[1]]).decode().strip()
        return subprocess.check_output(["git", "-C", str(directory), "cat-file", "blob", object_id])
    return subprocess.check_output(["git", "-C", str(directory), *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path("target"))
    parser.add_argument("--output", type=Path, default=Path("docs/sql-studio/27-dbeaver-evidence/upstream-sources.json"))
    args = parser.parse_args()
    manifest = {"tag": "25.2.0", "sourceCopiedIntoProduct": False, "repositories": {}}
    for repo, revision in REVISIONS.items():
        directory = args.source_root / ("upstream-"+repo+"-25.2.0")
        assert git(directory, "rev-parse", "HEAD").decode().strip() == revision
        license_path = "LICENSE.md" if repo == "dbeaver" else "LICENSE"
        license_bytes = git(directory, "show", revision+":"+license_path)
        assert b"Apache License" in license_bytes
        files = []
        for path, markers in FILES[repo]:
            raw = git(directory, "show", revision+":"+path)
            text = raw.decode("utf8")
            header = text[:2000]
            # GraphQL query has no individual license header; record root-license coverage explicitly.
            if not path.endswith(".gql"):
                assert "Licensed under the Apache License, Version 2.0" in header, path
            locations = {}
            for marker in markers:
                lines = [n for n, line in enumerate(text.splitlines(), 1) if marker in line]
                assert lines, (path, marker)
                locations[marker] = lines
            files.append({"path": path, "sha256": hashlib.sha256(raw).hexdigest(), "lines": len(text.splitlines()),
                          "license": "Apache-2.0", "licenseBasis": "root" if path.endswith(".gql") else "file-header+root",
                          "symbols": locations, "url": f"https://github.com/dbeaver/{repo}/blob/{revision}/{path}"})
        manifest["repositories"][repo] = {"sha": revision, "licensePath": license_path,
            "licenseSha256": hashlib.sha256(license_bytes).hexdigest(), "files": files}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf8")
    rows=["# 固定源码文件索引", "", "由 verify-sql-upstream-sources.py 从固定 Git blob 核对并生成。产品没有复制这些文件的源码。", "",
          "| 仓库 | 文件（固定提交） | 关键符号出现行号 | 许可证依据 |", "| --- | --- | --- | --- |"]
    for repo, record in manifest["repositories"].items():
        for file in record["files"]:
            symbols="；".join("`"+name+"` L"+"/".join(map(str,lines)) for name,lines in file["symbols"].items())
            rows.append(f"| {repo} | [{file['path']}]({file['url']}) | {symbols} | Apache-2.0 ({file['licenseBasis']}) |")
    args.output.with_name("source-index.md").write_text("\n".join(rows)+"\n",encoding="utf8")
    print(f"Verified 2 pinned revisions, {sum(len(v['files']) for v in manifest['repositories'].values())} source files and their license bases")


if __name__ == "__main__":
    main()
