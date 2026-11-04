param([Parameter(Mandatory=$true)][string]$Project)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Project = [IO.Path]::GetFullPath($Project)
$Output = Join-Path $Project 'output'
[IO.Directory]::CreateDirectory($Output) | Out-Null
$SourceZip = Join-Path $Output 'Q20Messenger-source.zip'
$PatchZip = Join-Path $Output 'Q20Messenger-server-v2-patch.zip'
$WorkRoot = Split-Path (Split-Path $Project -Parent) -Parent
$ServerRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'server'
$StagingZip = Join-Path $Output 'Q20Messenger-nokia4ever-v2-staging-source.zip'
foreach ($Path in @($SourceZip, $PatchZip, $StagingZip)) { if ([IO.File]::Exists($Path)) { [IO.File]::Delete($Path) } }

$Archive = [IO.Compression.ZipFile]::Open($SourceZip, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($File in Get-ChildItem -LiteralPath $Project -Recurse -File) {
        $Relative = $File.FullName.Substring($Project.Length + 1).Replace('\', '/')
        if ($Relative -match 'q20-private-config\.json$' -or $Relative -match '^(app/build|\.gradle|output)/' -or $Relative -eq 'local.properties' -or $Relative -match '(^|/)\.DS_Store$') { continue }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $File.FullName, ('Q20Messenger/' + $Relative), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $Archive.Dispose() }

# Reviewable patched server source; explicitly excludes installed packages,
# fixture databases/media, auth folders, environment files and smoke-test state.
$Archive = [IO.Compression.ZipFile]::Open($StagingZip, [IO.Compression.ZipArchiveMode]::Create)
try {
    $ServerNames = @(
        'package.json','package-lock.json','connect.js','messageRouter.js','server.js',
        'WhatsappClient.js','q20SchemaV2.js','q20HistorySupport.js','messageRouterV2.js','q20CallSupport.js','q20ContactCache.js','q20RichMessages.js','q20MediaVariants.js','q20Config.js',
        'server.js.before-q20-v2-patch','WhatsappClient.js.before-q20-patch'
    )
    foreach ($Name in $ServerNames) {
        $File = Join-Path $ServerRoot $Name
        if (-not [IO.File]::Exists($File)) { throw "Missing staged server source file: $File" }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $File, ('nokia4ever-source/' + $Name), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    $Readme = Join-Path $Project 'server-patches\README.md'
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $Readme, 'nokia4ever-source/Q20-v2-patch-notes.md', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    foreach ($Name in @('THIRD_PARTY_NOTICES.txt','Apache-2.0.txt')) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, (Join-Path $Project ('server-patches\' + $Name)), ('nokia4ever-source/' + $Name), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $Archive.Dispose() }

$Archive = [IO.Compression.ZipFile]::Open($PatchZip, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($Name in @('README.md','patch_whatsappclient.js','THIRD_PARTY_NOTICES.txt','Apache-2.0.txt')) {
        $File = Join-Path $Project ('server-patches\' + $Name)
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $File, ('Q20Messenger-server-v2-patch/' + $Name), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    foreach ($Name in @('package.json','package-lock.json','server.js','WhatsappClient.js','q20SchemaV2.js','q20HistorySupport.js','messageRouterV2.js','q20CallSupport.js','q20ContactCache.js','q20RichMessages.js','q20MediaVariants.js','q20Config.js','q20-private-config.json')) {
        $File = Join-Path $Project ('server-patches\payload\' + $Name)
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $File, ('Q20Messenger-server-v2-patch/payload/' + $Name), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    foreach ($Name in @('API_DOCUMENTATION.md','HISTORY_IMPORT_REPORT.md','TEST_REPORT.md')) {
        $File = Join-Path $Project $Name
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $File, ('Q20Messenger-server-v2-patch/' + $Name), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $Archive.Dispose() }

foreach ($Name in @('build_apk.sh','API_DOCUMENTATION.md','HISTORY_IMPORT_REPORT.md','TEST_REPORT.md','IMPROVEMENTS_REPORT.md','INSTALLATION.md','BUILD_INSTRUCTIONS.md','RESEARCH_AND_REVAMP.md')) {
    Copy-Item -LiteralPath (Join-Path $Project $Name) -Destination (Join-Path $Output $Name) -Force
}
$ObsoletePatch = Join-Path $Output 'Q20Messenger-server-patch.zip'
if ([IO.File]::Exists($ObsoletePatch)) { [IO.File]::Delete($ObsoletePatch) }
$Template = Join-Path $Project 'server-patches\update_server.template.sh'
if ([IO.File]::Exists($Template)) {
    $PayloadArchive = Join-Path $WorkRoot 'q20-current-server-payload.tar.gz'
    & tar --format=ustar --no-xattrs --no-acls -czf $PayloadArchive -C (Join-Path $Project 'server-patches\payload') .
    if ($LASTEXITCODE -ne 0) { throw 'Failed to archive complete server payload' }
    $Encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($PayloadArchive))
    $Encoded = [regex]::Replace($Encoded, '.{1,100}', '$0' + "`n").TrimEnd()
    $Script = [IO.File]::ReadAllText($Template).Replace('__Q20_PAYLOAD__', $Encoded).Replace("`r`n", "`n")
    $Updater = Join-Path $Output 'Q20Messenger-update-server.sh'
    [IO.File]::WriteAllText($Updater, $Script, [Text.UTF8Encoding]::new($false))
    $Archive = [IO.Compression.ZipFile]::Open($PatchZip, [IO.Compression.ZipArchiveMode]::Update)
    try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $Updater, 'Q20Messenger-server-v2-patch/Q20Messenger-update-server.sh', [IO.Compression.CompressionLevel]::Optimal) | Out-Null }
    finally { $Archive.Dispose() }
}
Get-ChildItem -LiteralPath $Output -File | Select-Object Name, Length, LastWriteTime


