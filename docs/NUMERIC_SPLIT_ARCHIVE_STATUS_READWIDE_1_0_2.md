# Numeric `.001` Split Archive Boundary — Readwide 1.0.2

Readwide has a narrow compatibility path for generic raw binary split chains such as:

```text
book.zip.001
book.zip.002
book.zip.003
```

This path concatenates contiguous numeric parts into a temporary payload before the normal archive reader opens it. It is intended for raw split files where the original archive bytes were divided into `.001`, `.002`, ... chunks.

## Covered

- The selected file must be the first numeric part, ending in `.001`.
- Parts are collected in strict contiguous order: `.001`, `.002`, `.003`, ... .
- The resulting combined payload is then handled by the normal ZIP/TAR/single-compressor path inferred from the base name.

## Guarded failure cases

- If `.001` and a later part such as `.003` exist but `.002` is missing, Readwide now stops with a corrupt/incomplete split-chain error.
- A gapped chain is not silently combined as a shorter archive.
- A gapped chain must not be converted into a password prompt.

## Not claimed

- ZIP spanned archives using `.z01` / `.z02` naming are not part of this generic `.001` raw-split path.
- Non-contiguous, missing, damaged, or renamed split chains are not repaired.
- This does not broaden RAR or 7z split support; those formats have dedicated boundaries documented separately.
