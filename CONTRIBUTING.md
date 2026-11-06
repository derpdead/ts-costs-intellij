# Contributing

Use a small, public reproduction. Never commit or upload real project captures, source snapshots, local logs, tokens or generated traces.

Run `npm ci --ignore-scripts`, `npm test` and `python3 build.py --ide /path/to/idea`.
Test mapping changes against actual compiler runs, including Vue template offsets and source fingerprints. Test aggregation with overlapping spans and duplicate relation endpoints.

Keep CPU/memory claims scoped to compiler processes. Missing evidence must remain visible; do not infer intrinsic type cost from an inclusive span or invent per-line resource attribution.

The Java UI uses the IntelliJ Platform SDK only. The runtime bridge uses Node built-ins and the profiled project's own compiler dependencies. Development dependencies are not bundled.

For panel-action changes, verify Record from the actual tool-window button (including accessibility activation), not only from the Tools action. A valid new profile must appear and the IDE log must remain free of write-unsafe / missing-lock errors. Also verify source navigation and toggling inlays. Compiler integration tests and Plugin Verifier do not exercise this UI threading contract.

Marketplace releases are uploaded by the vendor after compatibility verification. Changes must not add automatic profile uploads or telemetry.
