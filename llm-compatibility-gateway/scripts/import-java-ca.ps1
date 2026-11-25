param([Parameter(Mandatory=$true)][string]$Ca, [Parameter(Mandatory=$true)][string]$TrustStore)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_TRUSTSTORE_PASSWORD) { throw 'Set JAVA_TRUSTSTORE_PASSWORD first' }
if (Test-Path -LiteralPath $TrustStore) { throw 'Use a new TrustStore path' }
$certs = [regex]::Matches([IO.File]::ReadAllText((Resolve-Path -LiteralPath $Ca)), '(?s)-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----')
if (-not $certs.Count) { throw 'No certificate in bundle' }
$work = Join-Path ([IO.Path]::GetTempPath()) ('gateway-ca-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $work
$cert = Join-Path $work 'certificate.pem'
$store = Join-Path $work 'trust.p12'
try {
    for ($i=0; $i -lt $certs.Count; $i++) {
        [IO.File]::WriteAllText($cert, $certs[$i].Value, [Text.Encoding]::ASCII)
        keytool -importcert -noprompt -alias "gateway-$i" -file $cert -storetype PKCS12 -keystore $store -storepass:env JAVA_TRUSTSTORE_PASSWORD
        if ($LASTEXITCODE) { throw 'keytool failed' }
    }
    Copy-Item -LiteralPath $store -Destination $TrustStore
} finally {
    foreach ($file in @($cert, $store)) { if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file } }
    Remove-Item -LiteralPath $work
}
