$RepoRoot = Split-Path -Parent $PSScriptRoot
$PreviousPythonPath = $env:PYTHONPATH
$ExitCode = 1

try {
    $env:PYTHONPATH = Join-Path $RepoRoot "python"
    $Python = Get-Command python -CommandType Application -ErrorAction Stop
    & $Python.Source -m henyo.cli @args
    $ExitCode = $LASTEXITCODE
}
finally {
    $env:PYTHONPATH = $PreviousPythonPath
}

exit $ExitCode
