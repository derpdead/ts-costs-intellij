import { createReadStream } from 'node:fs'
import { readFile, rename, writeFile } from 'node:fs/promises'
import { createInterface } from 'node:readline'
import { createRequire } from 'node:module'
import path from 'node:path'
import { compilerPosition, completedEvents, mapRange, normalizeText, sourceHash } from './mapping.mjs'
import { indexEvents, intervalCoverage, parseDiagnostics } from './trace.mjs'
import { sourceContext, explainEvent } from './context.mjs'

export async function exportProfile(run, root, destination) {
  const projectRequire = createRequire(path.join(root, 'package.json'))
  const ts = projectRequire('typescript')
  const parsed = new WeakMap()
  const descriptors = new WeakMap()
  const descriptor = record => {
    if (!record.path.endsWith('.vue')) return null
    if (!descriptors.has(record)) descriptors.set(record, projectRequire('vue/compiler-sfc').parse(record.sourceText).descriptor)
    return descriptors.get(record)
  }
  const namedRange = (record, range, name) => {
    if (!range || !name || name.startsWith('__')) return range
    if (!parsed.has(record)) {
      let parts = [{ text: record.sourceText, offset: 0 }]
      if (record.path.endsWith('.vue')) {
        const sfc = descriptor(record)
        parts = [sfc.script, sfc.scriptSetup].filter(Boolean)
          .map(block => ({ text: block.content, offset: block.loc.start.offset }))
      }
      parsed.set(record, parts.map(part => ({
        ...part, ast: ts.createSourceFile(record.path + '.ts', part.text, ts.ScriptTarget.Latest, true),
      })))
    }
    let found
    for (const part of parsed.get(record)) {
      const visit = node => {
        if (node.name && ts.isIdentifier(node.name) && node.name.text === name) {
          const start = part.offset + node.name.getStart(part.ast)
          const end = part.offset + node.name.end
          if (start >= range.start && end <= range.end) found = { ...range, start, end }
        }
        ts.forEachChild(node, visit)
      }
      visit(part.ast)
    }
    return found ?? range
  }
  const capture = JSON.parse(await readFile(path.join(run, 'capture.json'), 'utf8'))
  const events = indexEvents(completedEvents(JSON.parse(await readFile(path.join(run, 'trace/trace.json'), 'utf8'))))
  const files = new Map()
  const records = new Map()
  const getRecord = async file => {
    const absolute = path.resolve(file)
    const key = capture.caseSensitive ? absolute : absolute.toLowerCase()
    if (!records.has(key)) {
      const name = capture.files[key]
      records.set(key, name ? JSON.parse(await readFile(path.join(run, 'sources', name), 'utf8')) : null)
    }
    return records.get(key)
  }
  const fileProfile = record => {
    if (!files.has(record.path)) files.set(record.path, {
      path: path.relative(root, record.path).replaceAll('\\', '/'),
      sha256: sourceHash(record.sourceText), fileMs: 0, mappedMs: 0, hints: [], spans: [], mapped: new Map(),
    })
    return files.get(record.path)
  }
  let unmapped = 0
  const mappedEvents = new Map()
  const add = (record, range, event, kind, detail) => {
    if (!range) { unmapped += 1; return }
    const start = normalizeText(record.sourceText.slice(0, range.start)).length
    const end = normalizeText(record.sourceText.slice(0, range.end)).length
    const source = normalizeText(record.sourceText)
    const context = sourceContext(record.sourceText, range.start, range.end, descriptor(record))
    const explanation = explainEvent(event, record, range, context)
    if (kind === 'type relation') {
      explanation.title = 'Type compatibility check'
      explanation.explanation = detail
    }
    const file = fileProfile(record)
    file.mapped.set(event.id, event)
    mappedEvents.set(event.id, event)
    file.hints.push({
      start, end, line: source.slice(0, start).split('\n').length,
      ms: event.durationMs, kind, event: event.name, mapping: range.mapping,
      detail, snippet: source.slice(start, Math.min(end, start + 160)),
      eventKey: event.id, childMs: event.childMs, unattributedMs: event.unattributedMs,
      ...explanation,
    })
  }
  const relations = []
  for (const event of events) {
    if (event.name === 'checkSourceFile' && event.args?.path) {
      const record = await getRecord(event.args.path)
      if (record) fileProfile(record).spans.push(event)
    }
    if (['checkExpression', 'checkVariableDeclaration', 'checkDeferredNode'].includes(event.name)
      && event.args?.path && event.durationMs >= 1) {
      const record = await getRecord(event.args.path)
      if (record) add(record, mapRange(record, event.args.pos, event.args.end), event,
        event.name === 'checkVariableDeclaration' ? 'declaration' : 'expression',
        'Inclusive check of this source region; nested events overlap.')
    }
    if (event.name === 'structuredTypeRelatedTo' && event.durationMs >= 1) relations.push(event)
  }
  const neededTypes = new Set(relations.flatMap(event => [event.args.sourceId, event.args.targetId]))
  const types = new Map()
  if (neededTypes.size) {
    const lines = createInterface({ input: createReadStream(path.join(run, 'trace/types.json')), crlfDelay: Infinity })
    for await (const line of lines) {
      const json = line.trim().replace(/^\[/, '').replace(/\]$/, '').replace(/,$/, '')
      if (!json) continue
      const id = json.match(/^\{"id":(\d+)/)?.[1]
      if (id && neededTypes.has(Number(id))) {
        const type = JSON.parse(json)
        types.set(type.id, type)
      }
    }
  }
  for (const event of relations) {
    for (const role of ['source', 'target']) {
      const type = types.get(event.args[`${role}Id`])
      const declaration = type?.firstDeclaration
      if (!declaration?.path) continue
      const record = await getRecord(declaration.path)
      if (!record) continue
      const start = compilerPosition(record, declaration.start)
      const end = compilerPosition(record, declaration.end)
      add(record, namedRange(record, mapRange(record, start, end), type.symbolName), event, 'type relation',
        `${role} type ${type.symbolName ?? '#' + type.id}: relation #${event.args.sourceId} → #${event.args.targetId}. Time belongs to the relation, not an intrinsic type cost.`)
    }
  }
  for (const file of files.values()) {
    const unique = new Map()
    for (const hint of file.hints) {
      const key = `${hint.eventKey}:${hint.start}:${hint.end}`
      if (!unique.has(key)) unique.set(key, hint)
    }
    file.hints = [...unique.values()].sort((a, b) => b.ms - a.ms)
    file.fileMs = intervalCoverage(file.spans)
    file.mappedMs = intervalCoverage([...file.mapped.values()])
    delete file.spans
    delete file.mapped
  }
  const compilerResult = JSON.parse(await readFile(path.join(run, 'compiler-result.json'), 'utf8').catch(() => '{}'))
  const diagnostics = parseDiagnostics(await readFile(path.join(run, 'compiler-output.log'), 'utf8').catch(() => ''))
  const result = {
    schemaVersion: 2, measuredAt: capture.measuredAt, projectRoot: root,
    versions: capture.versions, tracePath: path.join(run, 'trace/trace.json'),
    interpretation: 'Inclusive measured wall time; nested events and type-relation endpoints overlap. Never sum inlay labels.',
    unmappedEvents: unmapped, files: [...files.values()],
    summary: {
      compilerWallMs: compilerResult.wallMs ?? capture.metrics?.wallMs ?? null,
      cpuMs: capture.metrics?.cpuMs ?? null, cpuUserMs: capture.metrics?.cpuUserMs ?? null,
      cpuSystemMs: capture.metrics?.cpuSystemMs ?? null, peakRssBytes: capture.metrics?.peakRssBytes ?? null,
      heapUsedAtExitBytes: capture.metrics?.heapUsedAtExitBytes ?? null,
      tsMemoryBytes: diagnostics['Memory used'] ?? null,
      checkMs: diagnostics['Check time'] ?? null, parseMs: diagnostics['Parse time'] ?? null,
      bindMs: diagnostics['Bind time'] ?? null, programMs: diagnostics['Program time'] ?? null,
      dumpMs: diagnostics['Dump types time'] ?? null,
      uniqueEvents: mappedEvents.size, mappedMs: intervalCoverage([...mappedEvents.values()]),
      resourceScope: capture.metrics?.scope ?? 'Resource metrics were not collected by this older capture.',
    },
  }
  if (destination) {
    await writeFile(destination + '.tmp', JSON.stringify(result))
    await rename(destination + '.tmp', destination)
  }
  return result
}

export function mergeProfiles(profiles, run) {
  if (!profiles.length) throw new Error('No compiler profiles were captured.')
  const files = new Map()
  for (const [index, profile] of profiles.entries()) {
    for (const file of profile.files) {
      if (files.has(file.path) && files.get(file.path).sha256 !== file.sha256) {
        throw new Error(`Source changed between compiler runs: ${file.path}. Record again.`)
      }
      if (!files.has(file.path)) files.set(file.path, { ...file, fileMs: 0, mappedMs: 0, hints: [] })
      const combined = files.get(file.path)
      combined.fileMs += file.fileMs
      combined.mappedMs += file.mappedMs
      combined.hints.push(...file.hints.map(hint => ({ ...hint, eventKey: `${index}:${hint.eventKey}` })))
    }
  }
  const summary = { ...profiles[0].summary }
  for (const key of Object.keys(summary)) {
    if (key === 'resourceScope') continue
    const values = profiles.map(profile => profile.summary[key])
    summary[key] = values.some(value => value == null) ? null
      : key.endsWith('Bytes') ? Math.max(...values) : values.reduce((sum, value) => sum + value, 0)
  }
  return { ...profiles[0], measuredAt: run.startedAt, files: [...files.values()],
    unmappedEvents: profiles.reduce((sum, profile) => sum + profile.unmappedEvents, 0),
    summary: { ...summary, wallMs: run.wallMs, prepareMs: run.prepareMs, framework: run.framework,
      exitCode: run.exitCode, projects: profiles.length,
      resourceScope: 'CPU is summed across sequential compiler processes; memory is their maximum, not a sum. Includes trace and type-dump overhead. No per-line CPU or memory attribution.' },
    runs: profiles.map(profile => ({ config: profile.config, tracePath: profile.tracePath, versions: profile.versions })),
  }
}
