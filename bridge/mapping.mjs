import { createHash } from 'node:crypto'

export const normalizeText = text => text.replace(/^\uFEFF/, '').replaceAll('\r\n', '\n')
export const sourceHash = text => createHash('sha256').update(normalizeText(text)).digest('hex')

export function mapRange(record, start, end) {
  if (!Number.isInteger(start) || !Number.isInteger(end) || end <= start) return null
  if (!record.mappings) {
    if (start < 0 || end > record.sourceText.length) return null
    return { start, end, mapping: 'source' }
  }
  const generatedStart = start - record.mappingOffset
  const generatedEnd = end - record.mappingOffset
  if (generatedStart < 0) return null
  const candidates = []
  for (const mapping of record.mappings) {
    for (let i = 0; i < mapping.generatedOffsets.length; i += 1) {
      const g = mapping.generatedOffsets[i]
      const sourceLength = mapping.lengths[i]
      const generatedLength = mapping.generatedLengths?.[i] ?? sourceLength
      if (!sourceLength || !generatedLength) continue
      const left = Math.max(g, generatedStart)
      const right = Math.min(g + generatedLength, generatedEnd)
      if (right <= left) continue
      if (generatedLength !== sourceLength && (left !== g || right !== g + generatedLength)) continue
      candidates.push({
        start: mapping.sourceOffsets[i] + (generatedLength === sourceLength ? left - g : 0),
        end: mapping.sourceOffsets[i] + (generatedLength === sourceLength ? right - g : sourceLength),
      })
    }
  }
  if (!candidates.length) return null
  candidates.sort((a, b) => a.start - b.start || a.end - b.end)
  let sourceStart = candidates[0].start
  let sourceEnd = candidates[0].end
  for (const next of candidates.slice(1)) {
    if (next.start > sourceEnd && record.sourceText.slice(sourceEnd, next.start).trim()) return null
    sourceEnd = Math.max(sourceEnd, next.end)
  }
  if (sourceStart < 0 || sourceEnd > record.sourceText.length || sourceEnd <= sourceStart) return null
  if (record.sourceText.slice(sourceStart, sourceEnd).split('\n').length > 4) return null
  return { start: sourceStart, end: sourceEnd, mapping: 'vue-source-map' }
}

export function compilerPosition(record, position) {
  return record.compilerLineStarts[position.line - 1] === undefined
    ? null : record.compilerLineStarts[position.line - 1] + position.character - 1
}

export function completedEvents(raw) {
  const events = Array.isArray(raw) ? raw : raw.traceEvents
  if (!Array.isArray(events)) throw new Error('Unsupported trace format')
  const completed = []
  const stacks = new Map()
  for (const event of events) {
    const thread = `${event.pid}:${event.tid}`
    if (event.ph === 'X') completed.push({ ...event, durationMs: event.dur / 1000 })
    if (event.ph === 'B') {
      if (!stacks.has(thread)) stacks.set(thread, [])
      stacks.get(thread).push(event)
    }
    if (event.ph === 'E') {
      const begin = stacks.get(thread)?.pop()
      if (!begin || (event.name && event.name !== begin.name)) throw new Error('Incomplete trace event pair')
      completed.push({ ...begin, args: { ...begin.args, ...event.args }, durationMs: (event.ts - begin.ts) / 1000 })
    }
  }
  if ([...stacks.values()].some(stack => stack.length)) throw new Error('Incomplete trace')
  if (completed.some(event => !Number.isFinite(event.durationMs) || event.durationMs < 0)) throw new Error('Invalid event duration')
  return completed
}
