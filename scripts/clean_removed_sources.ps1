# Deletes stale files that may remain when a new TextView Reader ZIP is extracted over an older working tree.
# Run from the repository root.
$ErrorActionPreference = "Stop"
$paths = @(
  "app\src\main\java\com\textview\reader\archive\RarJunrarFallback.java"
)
foreach ($p in $paths) {
  if (Test-Path $p) {
    Remove-Item $p -Force
    Write-Host "Deleted stale removed source: $p"
  }
}
