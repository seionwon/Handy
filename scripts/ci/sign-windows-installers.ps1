param([Parameter(Mandatory = $true)][string]$Directory)
$ErrorActionPreference = "Stop"
if (-not $env:HANDY_WINDOWS_CERT_PFX -or -not $env:HANDY_WINDOWS_CERT_PASSWORD) {
    throw "Configure the Windows release signing certificate and password before publishing installers."
}
$packages = @(Get-ChildItem -LiteralPath $Directory -File | Where-Object { $_.Extension -in ".exe", ".msi" })
if ($packages.Count -ne 2 -or @($packages.Extension | Sort-Object -Unique).Count -ne 2) {
    throw "Expected one EXE and one MSI installer."
}
$signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.0.*\x64\signtool.exe" |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $signtool) { throw "Windows SDK signtool is required." }
$pfx = New-TemporaryFile
$cert = $null
try {
    [IO.File]::WriteAllBytes($pfx.FullName, [Convert]::FromBase64String($env:HANDY_WINDOWS_CERT_PFX))
    $password = ConvertTo-SecureString $env:HANDY_WINDOWS_CERT_PASSWORD -AsPlainText -Force
    $cert = Import-PfxCertificate -FilePath $pfx.FullName -CertStoreLocation Cert:\CurrentUser\My -Password $password |
        Where-Object { $_.HasPrivateKey } | Select-Object -First 1
    if (-not $cert) { throw "A release certificate with a private key is required." }
    $checksums = foreach ($package in $packages) {
        & $signtool.FullName sign /sha1 $cert.Thumbprint /fd SHA256 /tr https://timestamp.digicert.com /td SHA256 $package.FullName
        if ($LASTEXITCODE -ne 0) { throw "Signing failed for $($package.Name)" }
        $signature = Get-AuthenticodeSignature -FilePath $package.FullName
        if ($signature.Status -ne "Valid" -or $signature.SignerCertificate.Thumbprint -ne $cert.Thumbprint) {
            throw "Unverified publisher for $($package.Name)"
        }
        "$((Get-FileHash -Algorithm SHA256 $package.FullName).Hash.ToLowerInvariant())  $($package.Name)"
    }
    $checksums | Set-Content (Join-Path $Directory "SHA256SUMS.txt") -Encoding utf8
} finally {
    Remove-Item -LiteralPath $pfx.FullName -Force
    if ($cert) { Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($cert.Thumbprint)" -DeleteKey }
}
