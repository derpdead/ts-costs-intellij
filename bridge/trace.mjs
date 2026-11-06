export function unionMilliseconds(intervals) {
  if (!intervals.length) return 0
  const sorted = intervals.map(([start, end]) => [start, end]).sort((a, b) => a[0] - b[0])
  let total = 0
  let [start, end] = sorted[0]
  for (const next of sorted.slice(1)) {
    if (next[0] <= end) end = Math.max(end, next[1])
    else { total += end - start; [start, end] = next }
  }
  return (total + end - start) / 1000
}

export function intervalCoverage(events) {
  const threads = new Map()
  for (const event of events) {
    const key = `${event.pid}:${event.tid}`
    if (!threads.has(key)) threads.set(key, [])
    threads.get(key).push([event.ts, event.ts + event.durationMs * 1000])
  }
  return [...threads.values()].reduce((total, spans) => total + unionMilliseconds(spans), 0)
}

export function indexEvents(events) {
  const threads = new Map()
  for (const [index, event] of events.entries()) {
    event.id = `${event.pid}:${event.tid}:${event.ts}:${event.name}:${index}`
    event.children = []
    event.endTs = event.ts + event.durationMs * 1000
    const key = `${event.pid}:${event.tid}`
    if (!threads.has(key)) threads.set(key, [])
    threads.get(key).push(event)
  }
  for (const thread of threads.values()) {
    thread.sort((a, b) => a.ts - b.ts || b.endTs - a.endTs)
    const stack = []
    for (const event of thread) {
      while (stack.length && event.endTs > stack.at(-1).endTs + 0.001) stack.pop()
      if (stack.length) {
        event.parent = stack.at(-1)
        stack.at(-1).children.push(event)
      }
      stack.push(event)
    }
  }
  for (const event of events) {
    event.childMs = unionMilliseconds(event.children.map(child => [child.ts, child.endTs]))
    event.unattributedMs = Math.max(0, event.durationMs - event.childMs)
  }
  return events
}

export function parseDiagnostics(text) {
  const result = {}
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^([\w /]+):\s+([\d.]+)(K|s)?\s*$/)
    if (match) result[match[1].trim()] = Number(match[2]) * (match[3] === 'K' ? 1024 : match[3] === 's' ? 1000 : 1)
  }
  return result
}
