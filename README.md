# papyrus-export-diagrams (GitHub action)

Runs Papyrus Desktop under Xvfb, and writes one image file per diagram.

Tested against Papyrus-Desktop **7.1.0** (Eclipse 2025-06).

## Quick start

In any consuming repo, drop a workflow like this in
`.github/workflows/export-diagrams.yml`:

```yaml
name: Export Papyrus diagrams
on:
  push:
    paths: ['model/**']
permissions:
  contents: write
jobs:
  export:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - id: papyrus
        uses: thomas-worm/setup-papyrus@v1
      - uses: thomas-worm/papyrus-export-diagrams@v1
        with:
          papyrus-home: ${{ steps.papyrus.outputs.papyrus-home }}
          model-dir:    model
          output-dir:   model_images
      - run: |
          git config user.name  "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add model_images
          git diff --staged --quiet || \
            (git commit -m "chore: re-export diagrams [skip ci]" && \
             git push origin HEAD:${GITHUB_REF#refs/heads/})
```

## Inputs

| Input            | Default | Description |
| ---------------- | ------- | --- |
| `papyrus-home`   | —       | From `setup-papyrus`. |
| `model-dir`      | —       | Scanned recursively for `*.di` files. |
| `output-dir`     | —       | Created if missing. |
| `format`         | `SVG`   | One of `SVG`, `PNG`, `JPEG`, `BMP`, `GIF`, `PDF`. |
| `naming`         | `xmiId` | `xmiId` (notation `xmi:id`, always unique, cryptic) or `name` (user-facing diagram name; you keep them unique). |
| `fail-on-error`  | `true`  | If `true`, the step fails when any single diagram fails to export. Set to `false` if you'd rather get partial output. |

## Outputs

| Output           | Description |
| ---------------- | --- |
| `exported-count` | Number of diagrams successfully exported. |
| `failed-count`   | Number of diagrams that could not be exported. |

## What gets exported

Two export pipelines run in sequence over `model-dir`:

- **Legacy GMF diagrams** (`*.notation` next to `*.di`) are rendered through
  GMF's `CopyToImageUtil`. SVG, PNG, JPEG, BMP, GIF, PDF all supported.
- **Sirius representations** (`*.aird`) are opened via Sirius (to refresh the
  representation against the current semantic model), then the backing GMF
  `Diagram` is fed into the same `CopyToImageUtil`. Avoids a cast bug in
  Sirius's own SVG generator that triggers whenever a representation
  contains embedded SVG figures. Full SVG/PNG/JPEG/BMP/GIF/PDF support.

Filenames combine both pipelines into the same flat `output-dir`. With
`naming: xmiId` you get notation `xmi:id`s for GMF diagrams and Sirius
descriptor UUIDs for representations — both are stable, both are unique.
With `naming: name` you get sanitised human-readable names; you stay
responsible for keeping diagram names unique across the model.
