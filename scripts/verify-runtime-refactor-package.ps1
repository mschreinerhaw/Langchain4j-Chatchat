param([Parameter(Mandatory=$true)][string]$Jar)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Jar))
try {
    $entry = $archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/chatchat-knowledge-base-.*\.jar$' } | Select-Object -First 1
    if (-not $entry) { throw 'Knowledge module missing from API package' }
    $buffer = [IO.MemoryStream]::new()
    $stream = $entry.Open()
    try { $stream.CopyTo($buffer) } finally { $stream.Dispose() }
    $buffer.Position = 0
    $module = [IO.Compression.ZipArchive]::new($buffer, [IO.Compression.ZipArchiveMode]::Read)
    try {
        $retired = @($module.Entries | Where-Object {
            $_.FullName -match '/(LangChain4jKnowledgeFusion|BgeDocumentCandidateReranker|DocumentCandidateReranker|CandidateRerankStage)(\$[^/]*)?\.class$'
        })
        if ($retired.Count -gt 0) { throw ('Retired RAG classes remain in artifact: ' + ($retired.FullName -join ', ')) }
        foreach ($name in @('KnowledgeContextOrdering','CandidateLimitStage','OpenSearchEmbeddingClient','LangChain4jDomainSkillAdapter')) {
            if (-not ($module.Entries | Where-Object { $_.FullName.EndsWith('/' + $name + '.class') })) {
                throw ('Required retained/replacement component missing: ' + $name)
            }
        }
    } finally { $module.Dispose(); $buffer.Dispose() }
    [ordered]@{status='PASSED';jar=[IO.Path]::GetFullPath($Jar);sha256=(Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash.ToLower();retiredRagClasses=0} | ConvertTo-Json
} finally { $archive.Dispose() }
