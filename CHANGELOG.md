# Changelog

## 0.2.2

- Show source in Why this cost in a native read-only code preview instead of inline HTML.
- Preserve indentation and line breaks, use IDE syntax colors and editor fonts, and wrap long lines.
- Apply the same preview to Generated code, with line numbers and normal text selection.

## 0.2.1

- Save documents from the tool-window Record button in the IDE's write-safe, nonmodal EDT context.
- Reset the recording state if saving documents fails so the action can be retried.
- Apply the IDE locking contract to editor navigation, inlay toggling and mouse hit testing.

## 0.2.0

- Project overview with elapsed time, CPU, peak RSS, compiler memory and phase timings.
- Clickable source badges with measured evidence, nested spans and Vue-generated code.
- Automatic TypeScript/Vue detection, Nuxt type preparation and fresh trace/type generation.
- Independent solution-project capture and preserved node-based Vue typecheck wrappers.
- Searchable paginated hotspot and file views, navigation, project settings and persisted inlay preference.
- Source fingerprint validation; atomic profile replacement after successful capture.
- Public MIT source, portable fixtures and build/verification workflow.
