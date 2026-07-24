$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$driveLetter = "R"
$drive = "${driveLetter}:"

if (Test-Path "${drive}\") {
    throw "Drive ${drive} is already in use. Change driveLetter in this script."
}

try {
    subst $drive $projectRoot
    Push-Location "${drive}\"
    & ".\gradlew.bat" clean test bootJar --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle verification failed with exit code $LASTEXITCODE."
    }
}
finally {
    if ((Get-Location).Path.StartsWith($drive)) {
        Pop-Location
    }
    subst $drive /d
}
