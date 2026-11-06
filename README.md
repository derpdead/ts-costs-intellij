# TS Costs for IntelliJ

Find expensive TypeScript checks beside the source that triggered them, including Vue single-file components.

TS Costs records a compiler trace and maps it back to your current source snapshot. It adds clickable editor badges, a project overview, file rankings and searchable hotspots. Vue checks include the generated declaration so you can see what the compiler actually checked.

## Install

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

The source mapping may point to just `value`, even though the measured declaration includes that generated type machinery. The **Why this cost** pane explains the scope; **Generated code** shows the exact same-run snippet. Lazy resolution of a component's types can contribute on first use. Without nested trace evidence, the plugin does not claim which inner type caused the delay or promise a specific optimization.

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

Python 3.10+, Node.js 22+ and an IDE SDK with JBR javac 21+ are required. Output: `dist/ts-costs-0.2.0.zip` and a source ZIP. The JAR bundles only plugin classes, resources and the dependency-free Node bridge. Project dependencies are resolved from the package you profile.

CI downloads the official JetBrains SDK, runs the integration tests, compiles the plugin and runs the official JetBrains Plugin Verifier. Marketplace publication is a separate vendor submission and moderation step.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [MIT](LICENSE).

TypeScript trace formats and Volar compiler hooks are internal and can change. Unsupported versions should fail visibly while preserving the previous profile. Please report the IDE, Node, TypeScript, Vue and vue-tsc versions with a minimal public reproduction.
