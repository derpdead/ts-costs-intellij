# TS Costs for IntelliJ

Find expensive TypeScript checks beside the source that triggered them, including Vue single-file components.

TS Costs records a compiler trace and maps it back to your current source snapshot. It adds clickable editor badges, a project overview, file rankings and searchable hotspots. Vue checks include the generated declaration so you can see what the compiler actually checked.

## Install

[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35858-ts-costs) submission is awaiting moderation.

Download the plugin ZIP from [Releases](https://github.com/derpdead/ts-costs-intellij/releases).
In your JetBrains IDE, open **Settings → Plugins → ⚙ → Install Plugin from Disk**, select the ZIP and follow the IDE's reload instructions.

The initial release targets platform **2026.3 (263)**. It is tested in IntelliJ IDEA 2026.3. Other platform-based IDEs require compatibility verification before support is claimed.

Requirements: **Node.js 22+**, the project's installed **JavaScript TypeScript 5/6** compiler, and **vue-tsc** for Vue/Nuxt. Integration tests cover TypeScript 5.9.3, 6.0.3, Vue 3.5.45, vue-tsc 3.3.14 and Nuxt 4.6.4. The native TypeScript 7 / tsgo compiler is not supported by this trace adapter.

## Record

1. Open **Tools → TS Costs → TS Costs Settings**. In a monorepo, choose the package containing your TypeScript dependencies and tsconfig.
2. Leave Node.js and tsconfig empty for automatic detection, or select the executable/config explicitly.
3. Open the **TS Costs** tool window and press **Record**.
4. Review **Overview**, **Hotspots** and **Files**, or click a badge in the editor.

Record saves open documents, regenerates Nuxt types with the locally installed Nuxt CLI, uses a fresh build-info file and produces a new trace, types dump, CPU profile and same-run Vue source maps. It never changes compiler files in node_modules.

A plain solution tsconfig with an empty files list is expanded into its referenced projects. Projects are checked sequentially; this is not a replacement for a project's declaration build. Referenced libraries that require built declarations must already have those declarations available.

For a Vue project's exact `typecheck: "node path/to/wrapper.mjs"` script, automatic mode preserves the wrapper (including its prepare step, heap options and diagnostic policy) and instruments its single `spawnSync(..., ["exec", "vue-tsc", ...])` invocation. Other wrappers, async launches, shell pipelines and multiple compiler invocations require an explicit tsconfig. An explicit config uses the automatic runner, including Nuxt prepare.

A completed compiler run with type errors still creates a profile and shows its exit status. Failed preparation, an unsupported compiler, cancellation or invalid/incomplete traces preserve the previous profile. Edited files lose their stale badges until a matching profile is recorded.

## What the numbers mean

| Metric | Meaning |
| --- | --- |
| Source badge | Inclusive elapsed compiler time for the recorded event, mapped to original source |
| File check | Union of that file's recorded checkSourceFile spans |
| Mapped coverage | Union of mapped events per compiler thread, with duplicate relation endpoints counted once |
| Record wall time | Preparation, compiler processes and profile export |
| Compiler CPU | User + system CPU for compiler work, including trace instrumentation and type dumping |
| Peak process RSS | Whole-process memory high-water mark |
| TypeScript memory | The compiler's extended-diagnostics “Memory used” reading |
| Outside recorded children | Elapsed time not covered by recorded direct child spans; **not self CPU** |

Nested spans overlap. A type relation can appear at both source and target declarations. **Do not add badge values.** The summary uses interval unions instead. CPU is summed across sequential compiler processes; memory is their maximum, not a sum. Program time includes parsing, so phase values are not additive. No reliable per-line CPU or memory attribution is available from these traces.

Profiling adds overhead and forces a fresh check. Results describe this particular instrumented run, not a benchmark of your normal incremental IDE session. TypeScript omits many short internal spans; “no children” does not mean “no nested work”.

## Why can a simple v-model look expensive?

For `<Widget v-model:code="value" />`, Vue generates an update-event handler and helper types such as `Partial<__VLS_ResolveEvent<…>>` and `__VLS_EventArgs<…>`. TypeScript checks the event payload, inferred handler arguments and assignment to the model.

The source mapping may point to just `value`, even though the measured declaration includes that generated type machinery. The **Why this cost** pane shows the selected event against the whole type-checking phase and explains the scope and shows source in a read-only editor with preserved indentation, syntax highlighting, line numbers and soft wrapping. **Generated code** uses the same preview for the exact same-run TypeScript snippet. Both previews follow your IDE editor font and color scheme. Use **Copy** for the recorded snippet, **Expand…** for a resizable code window, and **Wrap lines** to switch between wrapped lines and horizontal scrolling. These controls change only the preview; captured code stays unchanged. Lazy resolution of a component's types can contribute on first use. Without nested trace evidence, the plugin does not claim which inner type caused the delay or promise a specific optimization.

## Reading Why this cost

New recordings distinguish **Measured scope only** from **Recorded operations available**. The panel lists up to 12 nested operations (up to three levels), retains their inclusive elapsed times, and resolves recorded source/target type IDs to names, union/intersection sizes and declaration locations. Up to six compared types and six members per type are previewed; omitted counts remain visible. A type relation is one shared operation even when it appears at both declarations.

**What to try** offers experiments based on the types actually present in those recorded operations, or on the Vue component boundary identified by the source map. A helper's presence in generated code is an observation, not a measurement of that helper. Missing nested events never become a claim that a particular variable or type caused the delay. The eight-member union threshold only selects a suggestion; it is not a measured performance limit. Suggestions draw on Microsoft's [TypeScript performance guidance](https://github.com/microsoft/TypeScript/wiki/Performance), and the trace limitations are described in [Performance Tracing](https://github.com/microsoft/TypeScript/wiki/Performance-Tracing).

Change one boundary at a time and record again with the same compiler and configuration. Repeat to assess run-to-run variation and compare the complete type-checking phase: lazy type resolution may move the cost to another line. The plugin does not claim a speedup until you measure it. Earlier profiles still load and ask for a new recording to collect this evidence. Truncated source/generated excerpts are labelled; Copy and Expand retain that same captured excerpt.

## Local artifacts and Chrome

Profiles are stored under `<package>/node_modules/.cache/ts-costs/`:

- `latest-profile.json` — the profile loaded by the IDE.
- `record.log` — output from the IDE's Record action.
- `runs/<timestamp>/project-N/trace/trace.json` — open via **Load profile** in Chrome DevTools Performance, or drag into [Perfetto](https://ui.perfetto.dev/).
- `trace/types.json` — TypeScript's type dump.
- `compiler.cpuprofile` — load in Chrome DevTools' JavaScript profiler.
- `capture.json` and `sources/` — source snapshots, generated Vue code and mappings.

These artifacts contain your source code and local paths. They stay local. The plugin has no telemetry, HTTP client, account requirement or automatic uploads. Do not attach private project profiles to public issues.

You can also record from a terminal:

```sh
node bridge/profile.mjs --project /path/to/package
# Optional:
node bridge/profile.mjs --project /path/to/package --config tsconfig.app.json
```

## Build and verify

The build uses an installed JetBrains IDE as the SDK; no Gradle or Marketplace token is required.

```sh
npm ci --ignore-scripts
npm test
python3 build.py --ide "/path/to/idea"
# macOS:
python3 build.py --ide "/Applications/IntelliJ IDEA.app/Contents"
```

Python 3.10+, Node.js 22+ and an IDE SDK with JBR javac 21+ are required. Output: `dist/ts-costs-0.3.0.zip` and a source ZIP. The JAR bundles only plugin classes, resources and the dependency-free Node bridge. Project dependencies are resolved from the package you profile.

CI downloads the official JetBrains SDK, runs the integration tests, compiles the plugin and runs the official JetBrains Plugin Verifier. Marketplace publication is a separate vendor submission and moderation step.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [MIT](LICENSE).

TypeScript trace formats and Volar compiler hooks are internal and can change. Unsupported versions should fail visibly while preserving the previous profile. Please report the IDE, Node, TypeScript, Vue and vue-tsc versions with a minimal public reproduction.
