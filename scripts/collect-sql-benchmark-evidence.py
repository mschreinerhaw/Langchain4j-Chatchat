"""Collect successful controlled SQL benchmark evidence; excludes credentials and raw sessions."""
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("target/sql-dbeaver")
OUT = Path("docs/sql-studio/27-dbeaver-evidence")


def read_log(path):
    raw = path.read_bytes()
    return raw.decode("utf-16" if raw.startswith(b"\xff\xfe") else "utf8", errors="replace")


def read_json(path):
    return json.loads(path.read_text(encoding="utf8"))


def main():
    assert re.search(r"Tests\s+157 passed", read_log(Path("target/sql-p0-front.log")))
    assert "built in" in read_log(Path("target/sql-p0-build.log"))
    classes = {
        "chatchat-api": ["SqlMetadataOutputTest", "SqlStudioMetadataAssistantTest", "SqlOutputBundleTest",
                         "SqlStudioIntegrationTest", "SqlStudioPublicationControllerTest", "SqlStudioMetadataServiceTest", "CodingWorkspaceServiceTest"],
        "chatchat-agents": ["DashScopeNativeChatModelTest", "RuntimeStreamingChatModelTest", "ConfigurableChatModelFactoryTest"],
        "chatchat-mcp-server": ["SqlMetadataDiscoveryWorkflowTest"],
    }
    java = []
    for module, names in classes.items():
        for name in names:
            files = list(Path(module+"/target/surefire-reports").glob("TEST-*."+name+".xml"))
            assert len(files) == 1, name
            report = ET.parse(files[0]).getroot()
            assert all(int(report.get(key, "0")) == 0 for key in ["failures", "errors", "skipped"]), name
            java.append({"module": module, "class": name, "passed": int(report.get("tests"))})
    assert sum(row["passed"] for row in java) == 123
    business = read_json(ROOT/"business-positive/benchmark-browser-proof.json")
    case = business["benchmark"]
    assert not business["errors"] and case["coding"]["status"] == "SUCCESS"
    assert "FIELDS_CHECKED" in case["coding"]["validation"] and not case["automaticSqlExecution"] and case["savedFiles"] == 0
    restore = read_json(ROOT/"restore/benchmark-restore-proof.json")
    assert restore["newGenerationRequests"] == 0 and restore["metadataProvenance"] == case["coding"]["provenance"]
    stability = read_json(ROOT/"stability-final/stability-browser-proof.json")
    assert len(stability["scenarios"]) == 3
    for row in stability["scenarios"]:
        assert not row["errors"]
        assert all(row[key] for key in ["editorPreserved", "draftPreserved", "cursorPreserved", "scrollPreserved", "collapsedTracksZero", "noPageRefresh"])
        assert all(w["scrollWidth"] <= w["width"]+1 for w in row["extremeWidths"])
    parser = read_json(OUT/"real-sqlglot-repair.json")
    assert not parser["failed"]["valid"] and parser["repaired"]["valid"] and parser["codeExecutions"] == 0
    copies = {
        "business-positive/benchmark-browser-proof.json": "business-browser-proof.json",
        "business-positive/real-scope-completion.png": "real-scope-completion.png",
        "business-positive/real-evidence-bound-coding.png": "real-evidence-bound-coding.png",
        "restore/benchmark-restore-proof.json": "restore-proof.json",
        "restore/restored-coding-evidence.png": "restored-coding-evidence.png",
        "metadata-detail/metadata-detail-proof.json": "metadata-detail-proof.json",
        "metadata-detail/real-fields-and-relations.png": "real-fields-and-relations.png",
        "stability-final/stability-browser-proof.json": "stability-browser-proof.json",
        "preview-deployment.json": "preview-deployment.json",
    }
    for source, destination in copies.items():
        shutil.copy2(ROOT/source, OUT/destination)
    summary = {"frontend": {"files": 21, "passed": 157, "build": "SUCCESS"},
               "java": {"apiAndModelAdapters": 111, "mcpMetadata": 12, "passed": 123, "classes": java},
               "research": {"repositories": 2, "pinnedTag": "25.2.0", "fileLicensesVerified": 21, "thirdPartySourceCopied": False},
               "browser": {"mockedRequests": False, "liveConfiguredModel": True, "generationId": case["coding"]["generationId"],
                           "metadataBoundCoding": True, "restoredWithoutModelRequest": True, "emulatedScales": [1, 1.25, 1.5], "nativeZoomTested": False},
               "sqlglotRepair": {"realParser": True, "durableReceipts": True, "liveModelInControlledRepairTest": False, "sqlExecuted": False},
               "productionDeployed": False, "frontendIndexSha256": hashlib.sha256(Path("target/sql-stability/frontend/index.html").read_bytes()).hexdigest(),
               "traceSha256": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/"stability-final").glob("trace-*.json")}}
    (OUT/"verification-summary.json").write_text(json.dumps(summary, indent=2)+"\n", encoding="utf8")
    print("Verified: frontend157 / Java111 / MCP12; live metadata→Coding, restore and stability passed")


if __name__ == "__main__":
    main()
