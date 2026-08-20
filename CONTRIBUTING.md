# Contributing to croc-app

If you want to help:

1. **Open an issue/discussion for the PR discussion first**
2. Keep pull requests focused and easy to review.
3. Include screenshots or recordings for UI changes when possible.
4. Mention migration impact, behavior changes, and testing notes in the PR description.

## Vendoring Go Dependencies (F-Droid Compliance)

croc-app includes dual croc engines built from vendored source:
- **Current engine** (`v11+`): located in `third_party/croc-src`
- **Legacy engine** (`v10.6.0` - frozen): located in `third_party/croc-src-legacy`

Whenever dependencies are updated or re-vendored, you must commit the `vendor/` output for both trees:

```bash
# Current engine
cd third_party/croc-src
go mod tidy
go mod vendor
go build ./...

# Legacy engine (frozen - only if repairing vendoring)
cd ../croc-src-legacy
go mod tidy
go mod vendor
go build ./...
```

Ensure no prebuilt binaries (`.so`, `.a`, `.jar`, `.exe`, etc.) are committed into `third_party/`.

## Areas where help is especially useful:

- transfer edge cases
- accessibility improvements
- UI refinement
- test coverage
- new quality of life changes
