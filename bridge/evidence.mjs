import { createReadStream } from 'node:fs'
import { createInterface } from 'node:readline'

const performanceGuide = 'https://github.com/microsoft/TypeScript/wiki/Performance'
const operationLimit = 12

export async function readRelationTypes(filename, events) {
  const needed = new Set(events.flatMap(event => [event.args?.sourceId, event.args?.targetId]).filter(Number.isInteger))
  const types = new Map()
  const scan = async ids => {
    if (!ids.size) return
    const lines = createInterface({ input: createReadStream(filename), crlfDelay: Infinity })
    for await (const line of lines) {
      const json = line.trim().replace(/^\[/, '').replace(/\]$/, '').replace(/,$/, '')
      const id = json.match(/^\{"id":(\d+)/)?.[1]
      if (id && ids.has(Number(id))) {
        const type = JSON.parse(json)
        types.set(type.id, type)
      }
    }
  }
  await scan(needed)
  const members = new Set()
  for (const type of types.values()) {
    for (const id of [...(type.unionTypes ?? []), ...(type.intersectionTypes ?? []), ...(type.typeArguments ?? [])].slice(0, 6)) {
      if (!types.has(id)) members.add(id)
    }
  }
  await scan(members)
  return types
}

export function describeType(id, types) {
  if (!Number.isInteger(id)) return null
  const type = types.get(id)
  const name = item => item?.symbolName ?? item?.intrinsicName ?? item?.display
  const kind = type?.unionTypes ? 'union' : type?.intersectionTypes ? 'intersection'
    : type?.flags?.includes('Conditional') ? 'conditional' : 'type'
  const members = type?.unionTypes ?? type?.intersectionTypes ?? type?.typeArguments ?? []
  const memberNames = members.slice(0, 6).map(member => (name(types.get(member)) ?? `type #${member}`).slice(0, 100))
  const label = name(type) ?? (kind === 'union' ? memberNames.join(' | ')
    : kind === 'intersection' ? memberNames.join(' & ') : `type #${id}`)
  const declaration = type?.firstDeclaration
  return {
    id, label: label.slice(0, 180), kind, memberCount: members.length,
    members: memberNames, omittedMembers: Math.max(0, members.length - memberNames.length),
    declaration: declaration?.path && declaration.start
      ? `${declaration.path}:${declaration.start.line}:${declaration.start.character}` : null,
    resolved: Boolean(type),
  }
}

export function costAnalysis(event, context, generated, types) {
  const operations = []
  let totalOperations = event.descendantCount ?? 0
  const visit = (node, depth) => {
    if (operations.length >= operationLimit || depth > 3) return
    operations.push({
      event: node.name, ms: node.durationMs, depth,
      sourceType: describeType(node.args?.sourceId, types),
      targetType: describeType(node.args?.targetId, types),
    })
    for (const child of [...(node.children ?? [])].sort((a, b) => b.durationMs - a.durationMs)) visit(child, depth + 1)
  }
  if (event.name === 'structuredTypeRelatedTo') { visit(event, 0); totalOperations += 1 }
  else for (const child of [...(event.children ?? [])].sort((a, b) => b.durationMs - a.durationMs)) visit(child, 1)
  const related = operations.flatMap(operation => [operation.sourceType, operation.targetType]).filter(Boolean)
  const actions = []
  const add = (title, basis, change, anchor) => actions.push({ title, basis, change,
    reference: anchor ? `${performanceGuide}#${anchor}` : null })
  const union = related.find(type => type.kind === 'union' && type.memberCount >= 8)
  const intersection = related.find(type => type.kind === 'intersection' && type.memberCount >= 2)
  const conditional = related.find(type => type.kind === 'conditional')
  if (union) add('Reduce the union at the measured boundary',
    `A recorded compatibility check involves ${union.label}, a union with ${union.memberCount} members. Membership alone does not prove it dominates the span.`,
    'Try narrowing to the relevant discriminated branch before this assignment or call. If the API permits, pass a smaller shared base contract. Preserve the set of valid values.',
    'preferring-base-types-over-unions')
  if (intersection) add('Review the object composition',
    `A recorded compatibility check involves an intersection with ${intersection.memberCount} constituents (${intersection.label}).`,
    'For object shapes you own, try a named interface with extends instead of repeated intersections. Check conflicting properties: the two forms are not interchangeable in every case.',
    'preferring-interfaces-over-intersections')
  if (conditional) add('Name the repeated conditional type',
    `The recorded relation includes the conditional type ${conditional.label}.`,
    'If this conditional is repeated inline in a generic signature, extract a named type alias and use an explicit boundary annotation. Measure whether it reduces repeated checking.',
    'naming-complex-types')
  if (context?.directive === 'model') add(`Inspect ${context.component}'s model contract`,
    `The same-run Vue source map associates this span with ${context.subject}. The span covers the generated declaration, not just the model variable.`,
    `Compare ${context.binding ? `the declared ${context.binding} prop with the update:${context.binding} event payload` : 'the dynamically selected model prop with its matching update-event payload'} in ${context.component}. If inference is broad, try a small explicit shared payload type and a named, typed update handler. This is an experiment, not a measured cause.`)
  else if (context?.directive === 'on') add(`Inspect ${context.component}'s event signature`,
    `The source map points to ${context.subject}.`,
    'Compare the emitted payload with the handler parameter. Try a named handler with an explicit parameter type to isolate inference at this boundary; retain the component event contract.')
  else if (context) add(`Inspect ${context.component}'s props boundary`,
    `The source map places the measured scope at ${context.subject}.`,
    'Review the declared prop type and the value passed here. If the component accepts a much larger domain object than it uses, test a smaller explicit props contract.')
  if (context) actions.unshift(actions.pop())
  if (related.some(type => type.declaration?.replaceAll('\\', '/').includes('/node_modules/'))) {
    for (const action of actions) action.change += ' A compared type comes from a dependency: prefer changing your own boundary or adapter; do not edit generated code or node_modules.'
  }
  if (!actions.length) add('Isolate this check before changing types',
    related.length ? 'The recorded operations identify the compared types below, but establish no single expensive type pattern.'
      : 'This trace does not identify a specific inner type responsible for the delay.',
    'Inspect the declaration or call shown in Recorded source. Try an explicit type annotation at its boundary, then isolate a minimal reproduction if the cost persists.',
    'using-type-annotations')
  const helpers = ['__VLS_ResolveEvent', '__VLS_EventArgs', '__VLS_FunctionalComponentProps', 'Partial<']
    .filter(helper => generated.includes(helper))
  return {
    attribution: totalOperations ? 'Recorded operations available' : 'Measured scope only',
    finding: totalOperations
      ? 'The operations below ran inside this span. Their elapsed times include nested work; they do not identify an intrinsic cost of a type.'
      : 'No nested operations were recorded. The full span is measured, but its internal cost cannot be assigned to a particular type or to the highlighted variable.',
    operations, totalOperations, omittedOperations: totalOperations - operations.length,
    helpers, actions: actions.slice(0, 3),
    verification: 'Change one boundary at a time. Record the same project and compiler configuration again; repeat to check run-to-run variation. Compare the whole type-checking phase as well as this hotspot, since lazy work can move to another line. These suggestions are hypotheses; no speedup has been measured.',
  }
}
