import { costAnalysis } from './evidence.mjs'

export function sourceContext(source, start, end, vueDescriptor) {
  if (!vueDescriptor?.template || start < vueDescriptor.template.loc.start.offset) return null
  const template = vueDescriptor.template
  const nodes = [template.ast]
  while (nodes.length) {
    const node = nodes.pop()
    if (!node) continue
    if (node.type === 1) {
      for (const property of node.props ?? []) {
        if (start < property.loc.start.offset || end > property.loc.end.offset) continue
        const directive = property.type === 7 ? property.name : 'prop'
        const argument = property.arg?.isStatic === false ? `[${property.arg.content}]` : property.arg?.content ?? property.name ?? ''
        const name = directive === 'model' ? `v-model${property.arg ? ':' + argument : ''}`
          : directive === 'on' ? '@' + argument : directive === 'bind' ? ':' + argument : argument
        return {
          title: directive === 'model' ? 'Model event check' : directive === 'on' ? 'Event handler check' : 'Component binding check',
          subject: `${node.tag} · ${name}`,
          directive,
          component: node.tag,
          binding: property.arg?.isStatic === false ? null : property.arg?.content ?? 'modelValue',
          sourceSnippet: source.slice(property.loc.start.offset, property.loc.end.offset),
          explanation: directive === 'model'
            ? 'Vue generated a component update-event handler. TypeScript checks the event payload, inferred handler arguments and assignment back to the model. The measured scope can include resolving component types on first use.'
            : directive === 'on'
              ? 'TypeScript checks the generated listener against the component event signature, including inference and assignability.'
              : 'TypeScript checks this component binding in Vue-generated code, including inference and compatibility with component props.',
        }
      }
      if (node.loc?.start.offset <= start && node.loc?.end.offset >= end
        && start <= node.loc.start.offset + node.tag.length + 2) {
        const openingEnd = source.indexOf('>', node.props?.at(-1)?.loc.end.offset ?? node.loc.start.offset + 1)
        return {
          title: 'Component props check', subject: node.tag, directive: 'component', component: node.tag,
          sourceSnippet: source.slice(node.loc.start.offset, openingEnd < 0 ? node.loc.end.offset : openingEnd + 1),
          explanation: 'TypeScript checks the generated component invocation and its inferred props. This is a generated component check, not a declaration written in the source file.',
        }
      }
    }
    if (node.children) nodes.push(...node.children)
    if (node.branches) nodes.push(...node.branches)
  }
  return null
}

export function explainEvent(event, record, range, context, types = new Map()) {
  const generatedSource = record.mappings && record.compilerText
    ? record.compilerText.slice(event.args?.pos ?? 0, event.args?.end ?? 0).trim() : ''
  const generated = generatedSource.slice(0, 2400)
  const source = context?.sourceSnippet ?? record.sourceText.slice(range.start, range.end)
  const directChildren = [...(event.children ?? [])].sort((a, b) => b.durationMs - a.durationMs).slice(0, 5)
  const childSummary = directChildren.length
    ? directChildren.map(child => `${child.name}: ${child.durationMs.toFixed(2)} ms`).join('\n')
    : 'No nested child spans were recorded. The trace does not identify an inner type responsible for this cost.'
  return {
    title: context?.title ?? (event.name === 'checkVariableDeclaration' ? 'Declaration check' : 'Expression check'),
    subject: context?.subject ?? record.sourceText.slice(range.start, range.end).trim().slice(0, 100),
    explanation: context?.explanation ?? 'Measured compiler work associated with this source region. This includes nested work and may include lazy type resolution; it is not runtime execution of the expression.',
    sourceSnippet: source.slice(0, 500),
    generatedSnippet: generated,
    sourceTruncated: source.length > 500,
    generatedTruncated: generatedSource.length > generated.length,
    evidence: 'Measured compiler span; source location is mapped from the same-run snapshot.',
    limitation: 'Inclusive wall time, not intrinsic type cost. Trace thresholds omit some internal operations. The trace alone is not proof of a specific optimization.',
    children: childSummary,
    analysis: costAnalysis(event, context, generated, types),
  }
}
