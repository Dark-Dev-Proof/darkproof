# Builds the DarkProof Windows installer on a Windows PC, by hand, with nothing installed on the PC.
#
# The installer has to be made on Windows. This does on a person's own PC what the build machine did before
# (.github/workflows/desktop-windows.yml): it fetches Java and the DarkProof source of one commit into one work
# folder, builds the app and its installer there, starts the built app's self-test, and puts the installer and a
# short report on the Desktop.
#
# In Windows PowerShell (Start menu, type "PowerShell", Enter), either:
#     irm https://gitlab.com/darkproof-group/darkproof/-/raw/main/desktop/build-windows.ps1 | iex
# or, with this file copied to the PC:
#     powershell -ExecutionPolicy Bypass -File build-windows.ps1
#
# What it touches: the work folder (C:\dp-build, or dp-build in the person's own folder) and two files on the Desktop.
# Nothing is installed and no administrator password is asked for. Delete the work folder afterwards.
# What it fetches: Java 21 (Eclipse Temurin, from adoptium.net), the source (from gitlab.com), and what the build
# itself fetches: its libraries, the AI engine (llama.cpp's server) and the Tor Project's Tor program, the last two
# pinned and checked by SHA-256 in desktop/build.gradle.kts.
# The self-test starts the engine with the internet relays off and only asks Tor for its version: Tor is not
# connected to its network. Windows may ask whether DarkProof may use the network: either answer is fine here.
#
# Another commit or build number: set DARKPROOF_COMMIT or DARKPROOF_BUILD before running. The build number is the
# third number of the version (0.4.<build>); Windows replaces an installed DarkProof only with a higher one.

function Build-DarkProof {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue' # the progress bar makes downloads many times slower
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    $commit = if ($env:DARKPROOF_COMMIT) { $env:DARKPROOF_COMMIT } else { '47c6b134aa8b0a6a4754a796120b3836a5277818' }
    $build = if ($env:DARKPROOF_BUILD) { [int]$env:DARKPROOF_BUILD } else { 14 }
    function Say($text) { Write-Host ''; Write-Host "== $text ==" -ForegroundColor Cyan }

    # The work folder: short, so that no path in the build gets longer than Windows allows.
    $work = 'C:\dp-build'
    try { New-Item -ItemType Directory -Force -Path $work | Out-Null } catch { $work = Join-Path $env:USERPROFILE 'dp-build'; New-Item -ItemType Directory -Force -Path $work | Out-Null }
    $drive = (Get-Item $work).PSDrive
    if ($drive.Free -lt 8GB) { throw "The drive of $work has less than 8 GB free. Free some space and start again." }
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    function Unpack($zip, $into) {
        if (Test-Path $into) { Remove-Item -Recurse -Force $into }
        New-Item -ItemType Directory -Force -Path $into | Out-Null
        if ($tar) { & $tar.Source -xf $zip -C $into; if ($LASTEXITCODE -ne 0) { throw "$zip could not be unpacked" } }
        else { Expand-Archive -Path $zip -DestinationPath $into -Force }
    }
    Say "DarkProof 0.4.$build for Windows, from commit $($commit.Substring(0, 7)), in $work"

    Say 'Java 21 (about 200 MB, kept in the work folder only)'
    $jdkHome = Get-ChildItem (Join-Path $work 'jdk') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdkHome -or -not (Test-Path (Join-Path $jdkHome.FullName 'bin\jpackage.exe'))) {
        $jdkZip = Join-Path $work 'jdk.zip'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $jdkZip
        Unpack $jdkZip (Join-Path $work 'jdk')
        Remove-Item $jdkZip
        $jdkHome = Get-ChildItem (Join-Path $work 'jdk') -Directory -Filter 'jdk-*' | Select-Object -First 1
    }
    if (-not $jdkHome -or -not (Test-Path (Join-Path $jdkHome.FullName 'bin\jpackage.exe'))) { throw 'Java 21 was not unpacked as expected.' }
    $env:JAVA_HOME = $jdkHome.FullName
    $env:Path = (Join-Path $jdkHome.FullName 'bin') + ';' + $env:Path
    $env:GRADLE_USER_HOME = Join-Path $work 'gradle-home' # the build's own downloads stay in the work folder too
    Write-Host "Java: $($jdkHome.Name)"

    Say 'The DarkProof source'
    $srcZip = Join-Path $work 'source.zip'
    Invoke-WebRequest -UseBasicParsing -Uri "https://gitlab.com/darkproof-group/darkproof/-/archive/$commit/darkproof-$commit.zip" -OutFile $srcZip
    Unpack $srcZip (Join-Path $work 'x')
    $src = Join-Path $work 's'
    if (Test-Path $src) { Remove-Item -Recurse -Force $src }
    Move-Item (Join-Path $work "x\darkproof-$commit") $src
    Remove-Item -Recurse -Force (Join-Path $work 'x'); Remove-Item $srcZip
    if (-not (Test-Path (Join-Path $src 'gradlew.bat'))) { throw 'The source was not unpacked as expected.' }

    Say 'Building the app and its installer (10 to 20 minutes the first time)'
    $env:GITHUB_RUN_NUMBER = "$build" # desktop/build.gradle.kts takes the build number from this name
    Push-Location $src
    try {
        & .\gradlew.bat -Pdesktop -PaiEngine -Ptor :desktop:createDistributable :desktop:packageMsi --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "The build failed (exit code $LASTEXITCODE). Copy the red lines above to whoever asked you to run this." }
    } finally { Pop-Location }
    $msi = Get-ChildItem (Join-Path $src 'desktop\build\compose\binaries\main\msi') -Filter *.msi | Select-Object -First 1
    if (-not $msi) { throw 'No installer was built.' }

    Say "The built app's self-test (relays off, Tor not connected)"
    $exe = Get-ChildItem (Join-Path $src 'desktop\build\compose\binaries\main\app') -Recurse -Filter DarkProof.exe | Select-Object -First 1
    if (-not $exe) { throw 'DarkProof.exe was not built.' }
    $env:DARKPROOF_HOME = Join-Path $work 'self test' # a folder with a space in its name, as many people's own folders have
    if (Test-Path $env:DARKPROOF_HOME) { Remove-Item -Recurse -Force $env:DARKPROOF_HOME }
    $p = Start-Process -FilePath $exe.FullName -ArgumentList '--self-test' -PassThru
    $null = $p.Handle # keeps the exit code readable after the program ended
    $selfTest = 'the self-test wrote no report'
    if (-not $p.WaitForExit(240000)) { $p.Kill(); $selfCode = 'did not finish in four minutes' }
    else { $selfCode = "$($p.ExitCode)" }
    $reportFile = Join-Path $env:DARKPROOF_HOME 'self-test.txt'
    if (Test-Path $reportFile) { $selfTest = (Get-Content $reportFile) -join "`r`n" }
    Write-Host $selfTest
    Remove-Item Env:\DARKPROOF_HOME

    Say 'The installer and its report'
    $desktop = [Environment]::GetFolderPath('Desktop')
    $out = Join-Path $desktop "darkproof-0.4.$build-windows.msi"
    Copy-Item $msi.FullName $out -Force
    $sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
    $size = (Get-Item $out).Length
    $os = (Get-CimInstance Win32_OperatingSystem)
    $report = @(
        "DarkProof Windows installer, built $(Get-Date -Format 'yyyy-MM-dd HH:mm') on $($os.Caption) $($os.Version)",
        "file: darkproof-0.4.$build-windows.msi",
        "version: 0.4.$build",
        "commit: $commit",
        "size: $size bytes",
        "sha256: $sha",
        "java: $($jdkHome.Name)",
        "self-test exit code: $selfCode",
        '--- self-test report ---',
        $selfTest
    )
    $reportPath = Join-Path $desktop 'darkproof-windows-build.txt'
    $report | Set-Content -Path $reportPath -Encoding ASCII
    $report[0..7] | ForEach-Object { Write-Host $_ }
    if ($selfCode -ne '0') { Write-Host ''; Write-Host 'THE SELF-TEST DID NOT PASS: the installer was still copied, but say so when you hand it on.' -ForegroundColor Yellow }
    Write-Host ''
    Write-Host 'Done. On your Desktop: the installer and darkproof-windows-build.txt. Bring both back.' -ForegroundColor Green
    Write-Host "The work folder $work can be deleted now."
}
Build-DarkProof
