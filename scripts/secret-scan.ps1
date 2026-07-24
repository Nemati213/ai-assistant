$ErrorActionPreference = "Stop"

$patterns = @(
    "sk-or-v1-[A-Za-z0-9_-]{20,}",
    "ghp_[A-Za-z0-9_]{20,}",
    "github_pat_[A-Za-z0-9_]{20,}",
    "-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
    "[0-9]{8,10}:[A-Za-z0-9_-]{30,}",
    "AKIA[0-9A-Z]{16}"
)

$trackedFiles = git ls-files

if (-not $trackedFiles) {
    Write-Host "No tracked files to scan."
    exit 0
}

$matches = @()

foreach ($file in $trackedFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        continue
    }

    $content = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue

    if ($null -eq $content) {
        continue
    }

    foreach ($pattern in $patterns) {
        if ($content -match $pattern) {
            $matches += "$file matches $pattern"
        }
    }
}

if ($matches.Count -gt 0) {
    Write-Error ("Potential secrets found in tracked files:`n" + ($matches -join "`n"))
    exit 1
}

Write-Host "Secret scan passed."
