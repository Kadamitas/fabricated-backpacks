[CmdletBinding()]
param(
    [string]$RunId = [Guid]::NewGuid().ToString('D'),
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$PrepareOnly,
    [switch]$SkipPrepare,
    [ValidateRange(120, 3600)][int]$TimeoutSeconds = 900
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verification = Join-Path $repository 'build/verification'
$gate = Join-Path $verification 'multiplayer.json'
# A failed actual retry must not leave a previous passing receipt, including
# failures during preparation or argument validation. PrepareOnly never claims a run.
if (-not $PrepareOnly) {
    [IO.Directory]::CreateDirectory($verification) | Out-Null
    if (Test-Path -LiteralPath $gate) { Remove-Item -LiteralPath $gate -Force }
}
$canonicalId = [Guid]::ParseExact($RunId, 'D').ToString('D')
if ($RunId -cne $canonicalId) { throw 'RunId must be a lowercase canonical UUID.' }
$evidenceRoot = Join-Path $repository '.codex-local/client-evidence'
$launchRoot = Join-Path $evidenceRoot "multiplayer-launch-$RunId"
$testRoot = Join-Path $evidenceRoot "multiplayer-$RunId"
$manifestPath = Join-Path $launchRoot 'launch.json'
$processes = @{}

function Write-NewJson([string]$Path, $Value) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
        try { $writer.WriteLine(($Value | ConvertTo-Json -Depth 20)) }
        finally { $writer.Dispose() }
    } finally { $stream.Dispose() }
}

function Find-JavaHome {
    if ($JavaHome -and (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf)) { return $JavaHome }
    $cached = Join-Path $env:USERPROFILE '.gradle/jdks'
    if (Test-Path -LiteralPath $cached -PathType Container) {
        foreach ($directory in (Get-ChildItem -LiteralPath $cached -Directory | Sort-Object Name -Descending)) {
            $release = Join-Path $directory.FullName 'release'
            if ((Test-Path -LiteralPath $release -PathType Leaf) -and
                (Get-Content -LiteralPath $release | Select-String '^JAVA_VERSION="25[.+]') -and
                (Test-Path -LiteralPath (Join-Path $directory.FullName 'bin/java.exe') -PathType Leaf)) { return $directory.FullName }
        }
    }
    throw 'Set JAVA_HOME or pass -JavaHome with a JDK 25 installation before preparing the clients.'
}

function Assert-PreparedCommand([string]$Role, $Command) {
    foreach ($property in @('directory', 'argument_file', 'probe_file', 'configuration_file', 'stdout', 'stderr')) {
        $path = [IO.Path]::GetFullPath([string]$Command.$property)
        if (-not $path.StartsWith($launchRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Prepared $Role $property escapes this run's launch directory."
        }
    }
    if ([IO.Path]::GetFullPath([string]$Command.directory) -ne [IO.Path]::GetFullPath((Join-Path $launchRoot $Role))) {
        throw "The $Role client has an unexpected working directory."
    }
    if (-not (Test-Path -LiteralPath $Command.java -PathType Leaf)) { throw "Missing prepared Java executable: $($Command.java)" }
    if ((Get-FileHash -LiteralPath $Command.argument_file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Command.argument_sha256) {
        throw "The prepared $Role argument file has changed."
    }
    if ((Get-FileHash -LiteralPath $Command.probe_file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Command.probe_sha256 -or
        (Get-FileHash -LiteralPath $Command.configuration_file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Command.configuration_sha256) {
        throw "The prepared $Role probe or dev-launch configuration has changed."
    }
    $tokens = @($Command.java_arguments)
    $usernameTokens = @($tokens | Where-Object { $_ -ceq '--username' })
    if ($usernameTokens.Count -ne 1) { throw "The $Role command must contain one username." }
    $expectedScenario = "-Dfabricated.backpacks.clientScenario=multiplayer_$Role"
    if (-not ($tokens -ccontains $expectedScenario) -or -not ($tokens -ccontains "-Dfabricated.backpacks.multiplayerRunId=$RunId")) {
        throw "The prepared $Role command does not select this acceptance scenario and run ID."
    }
}

function Read-Phase([string]$Phase) {
    $path = Join-Path $testRoot "$Phase.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    if ((Get-Item -LiteralPath $path).Length -ge 65536) { throw "Oversized coordination record: $Phase" }
    $value = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($value.run_id -cne $RunId -or $value.phase -cne $Phase) { throw "Stale or mismatched coordination record: $Phase" }
    return $value
}

function Start-PreparedClient([string]$Role, $Command) {
    $processRecord = Join-Path $launchRoot "$Role-process.json"
    if (Test-Path -LiteralPath $processRecord) { throw "The $Role command was already launched; use a new run ID." }
    $previousEnvironment = @{}
    try {
        foreach ($property in $Command.environment.PSObject.Properties) {
            $previousEnvironment[$property.Name] = [Environment]::GetEnvironmentVariable($property.Name, 'Process')
            [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
        }
        $argument = '"@' + $Command.argument_file + '"'
        $process = Start-Process -FilePath $Command.java -ArgumentList $argument -WorkingDirectory $Command.directory `
            -WindowStyle Hidden -RedirectStandardOutput $Command.stdout -RedirectStandardError $Command.stderr -PassThru
        $processes[$Role] = $process
        Write-NewJson $processRecord ([ordered]@{
            run_id = $RunId; role = $Role; pid = $process.Id
            started_at = $process.StartTime.ToUniversalTime().ToString('O')
            java = $Command.java; argument_file = $Command.argument_file; directory = $Command.directory
        })
        return $process
    } finally {
        foreach ($name in $previousEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}

$previousJavaHome = $env:JAVA_HOME
try {
    if (-not $SkipPrepare) {
        if ((Test-Path -LiteralPath $launchRoot) -or (Test-Path -LiteralPath $testRoot)) {
            throw 'This run ID already exists. Choose a fresh UUID, or use -SkipPrepare for an unused prepared command.'
        }
        $env:JAVA_HOME = Find-JavaHome
        [IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
        $prepareLog = Join-Path $evidenceRoot "multiplayer-prepare-$RunId.log"
        if (Test-Path -LiteralPath $prepareLog) { throw 'A preparation log already exists for this run ID.' }
        Push-Location -LiteralPath $repository
        try {
            & (Join-Path $repository 'gradlew.bat') --no-daemon --console=plain prepareMultiplayerClients "-PmultiplayerRunId=$RunId" 2>&1 |
                Tee-Object -FilePath $prepareLog | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "Gradle preparation failed with exit code $LASTEXITCODE. See $prepareLog" }
        } finally { Pop-Location }
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.schema -ne 2 -or $manifest.run_id -cne $RunId -or
        [IO.Path]::GetFullPath([string]$manifest.project) -ne $repository -or
        [IO.Path]::GetFullPath([string]$manifest.evidence) -ne [IO.Path]::GetFullPath($testRoot)) {
        throw 'The prepared launch manifest does not belong to this project and fresh run ID.'
    }
    Assert-PreparedCommand 'host' $manifest.commands.host
    Assert-PreparedCommand 'guest' $manifest.commands.guest
    if ($manifest.commands.host.username -ceq $manifest.commands.guest.username) { throw 'The two clients need distinct usernames.' }
    $inputs = @(Get-Content -LiteralPath (Join-Path $launchRoot 'classpath-inputs.json') -Raw | ConvertFrom-Json)
    foreach ($inputFile in $inputs) {
        if ($inputFile.PSObject.Properties['directory'] -and $inputFile.directory) {
            if (-not (Test-Path -LiteralPath $inputFile.path -PathType Container)) { throw "A runtime directory disappeared: $($inputFile.path)" }
            $expectedMembers = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach ($member in @($inputFile.files)) { [void]$expectedMembers.Add([string]$member) }
            $actualMembers = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            Get-ChildItem -LiteralPath $inputFile.path -Recurse -File | ForEach-Object { [void]$actualMembers.Add($_.FullName) }
            if (-not $expectedMembers.SetEquals($actualMembers)) {
                throw "Runtime directory membership changed after preparation: $($inputFile.path). Prepare a new run before launching."
            }
            continue
        }
        if ($inputFile.PSObject.Properties['missing'] -and $inputFile.missing) {
            if (Test-Path -LiteralPath $inputFile.path) { throw "A previously absent runtime directory appeared: $($inputFile.path)" }
            continue
        }
        if (-not (Test-Path -LiteralPath $inputFile.path -PathType Leaf) -or
            (Get-FileHash -LiteralPath $inputFile.path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $inputFile.sha256) {
            throw "A runtime input changed after preparation: $($inputFile.path). Prepare a new run before launching."
        }
    }
    $probeId = [Guid]::NewGuid().ToString('D')
    $probeResults = [ordered]@{ run_id = $RunId; probe_id = $probeId }
    foreach ($role in @('host', 'guest')) {
        $command = $manifest.commands.$role
        $probeLog = Join-Path $launchRoot "$role-java-probe-$probeId.log"
        $probe = Start-Process -FilePath $command.java -ArgumentList ('"@' + $command.probe_file + '"') `
            -WorkingDirectory $command.directory -WindowStyle Hidden -PassThru -Wait `
            -RedirectStandardOutput (Join-Path $launchRoot "$role-java-probe-$probeId.stdout") -RedirectStandardError $probeLog
        if ($probe.ExitCode -ne 0) { throw "The $role Java argument-file probe failed. See $probeLog" }
        $probeResults[$role] = [ordered]@{ exit_code = $probe.ExitCode; log = $probeLog; argument_sha256 = $command.argument_sha256 }
    }
    Write-NewJson (Join-Path $launchRoot "java-probes-$probeId.json") $probeResults
    if ($PrepareOnly) {
        Write-Output "Prepared and argument-file probes verified; no Minecraft client was launched: $manifestPath"
        return
    }
    if (Test-Path -LiteralPath $testRoot) { throw 'The actual test evidence directory already exists; this run cannot be replayed.' }
    $hostProcess = Start-PreparedClient 'host' $manifest.commands.host
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $ready = $null
    while ($null -eq $ready) {
        $failure = Read-Phase 'host-failure'
        if ($null -ne $failure) { throw "Host acceptance failed: $($failure.failure)" }
        $hostProcess.Refresh()
        if ($hostProcess.HasExited) { throw 'Host exited before publishing its real TCP listener. Inspect host-stdout.log and host-stderr.log.' }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Timed out waiting for the host TCP listener.' }
        $ready = Read-Phase 'ready'
        if ($null -eq $ready) { Start-Sleep -Milliseconds 200 }
    }
    if ($ready.pid -ne $hostProcess.Id -or $ready.role -cne 'host' -or $ready.port -lt 1 -or $ready.port -gt 65535) {
        throw 'The host readiness record does not match the JVM this launcher started.'
    }
    $guestProcess = Start-PreparedClient 'guest' $manifest.commands.guest
    if ($guestProcess.Id -eq $hostProcess.Id) { throw 'Host and guest must be different processes.' }
    while ($true) {
        foreach ($role in @('host', 'guest')) {
            $failure = Read-Phase "$role-failure"
            if ($null -ne $failure) { throw "$role acceptance failed: $($failure.failure)" }
        }
        $hostProcess.Refresh()
        $guestProcess.Refresh()
        if ($hostProcess.HasExited -and $guestProcess.HasExited) { break }
        if ($hostProcess.HasExited -and $hostProcess.ExitCode -ne 0) { throw "Host JVM exited with $($hostProcess.ExitCode)." }
        if ($guestProcess.HasExited -and $guestProcess.ExitCode -ne 0) { throw "Guest JVM exited with $($guestProcess.ExitCode)." }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Timed out waiting for both real clients to complete.' }
        Start-Sleep -Milliseconds 200
    }
    $hostProcess.WaitForExit()
    $guestProcess.WaitForExit()
    $hostPass = Read-Phase 'host-pass'
    $guestPass = Read-Phase 'guest-pass'
    if ($hostProcess.ExitCode -ne 0 -or $guestProcess.ExitCode -ne 0 -or $null -eq $hostPass -or $null -eq $guestPass) {
        throw 'Both JVMs must exit successfully and publish their own acceptance reports.'
    }
    if ($hostPass.pid -ne $hostProcess.Id -or $guestPass.pid -ne $guestProcess.Id -or
        $hostPass.guest_pid -ne $guestProcess.Id -or $guestPass.host_pid -ne $hostProcess.Id) {
        throw 'Acceptance reports do not identify the two actual launched JVMs.'
    }
    $result = [ordered]@{
        run_id = $RunId; host_pid = $hostProcess.Id; guest_pid = $guestProcess.Id
        evidence_dir = $testRoot; host_exit = $hostProcess.ExitCode; guest_exit = $guestProcess.ExitCode
        passed = $true; completed_at = [DateTime]::UtcNow.ToString('O'); launch_manifest = $manifestPath
    }
    Write-NewJson (Join-Path $launchRoot 'multiplayer-result.json') $result
    $temporaryGate = Join-Path $verification ".multiplayer-$RunId.tmp"
    Write-NewJson $temporaryGate $result
    [IO.File]::Move($temporaryGate, $gate)
    Write-Output "MULTIPLAYER_PASS $testRoot"
} catch {
    if (Test-Path -LiteralPath $launchRoot -PathType Container) {
        $failurePath = Join-Path $launchRoot 'controller-failure.json'
        if (-not (Test-Path -LiteralPath $failurePath)) {
            Write-NewJson $failurePath ([ordered]@{ run_id = $RunId; passed = $false; failure = $_.ToString() })
        }
    }
    throw
} finally {
    foreach ($process in $processes.Values) {
        $process.Refresh()
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
    $env:JAVA_HOME = $previousJavaHome
}
