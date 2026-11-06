import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from 'vue/compiler-sfc'
import { costAnalysis, readRelationTypes, describeType } from '../bridge/evidence.mjs'
import { explainEvent, sourceContext } from '../bridge/context.mjs'
import { exportProfile } from '../bridge/export.mjs'
import { indexEvents } from '../bridge/trace.mjs'

test('a Vue span without children never assigns its elapsed time to an inner helper or model variable', () => {
  const source = '<template><Widget v-model:code="value" /></template>'
  const start = source.indexOf('value')
  const context = sourceContext(source, start, start + 5, parse(source).descriptor)
  const generated = 'const event: Partial<__VLS_ResolveEvent<T>> = {}'
  const [event] = indexEvents([{ name: 'checkVariableDeclaration', pid: 1, tid: 1, ts: 0, durationMs: 116,
    args: { pos: 0, end: generated.length } }])
  const result = explainEvent(event, { sourceText: source, mappings: [], compilerText: generated },
    { start, end: start + 5 }, context)
  assert.equal(result.analysis.attribution, 'Measured scope only')
  assert.equal(result.analysis.operations.length, 0)
  assert.match(result.analysis.finding, /cannot be assigned/)
  assert.deepEqual(result.analysis.helpers, ['__VLS_ResolveEvent', 'Partial<'])
  assert.match(result.analysis.actions[0].change, /update:code/)
  assert.match(result.analysis.actions[0].change, /experiment, not a measured cause/)
  assert.match(result.analysis.verification, /whole type-checking phase/)
})

test('type evidence resolves only recorded IDs and bounds large union previews without losing their actual size', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'ts-costs-types-'))
  try {
    const filename = path.join(root, 'types.json')
    const types = Array.from({ length: 20 }, (_, index) => ({ id: index + 1, display: `"case-${index}"` }))
    types.push({ id: 30, unionTypes: types.map(type => type.id), flags: ['Union'] })
    await writeFile(filename, '[\n' + types.map(type => JSON.stringify(type)).join(',\n') + '\n]')
    const loaded = await readRelationTypes(filename, [{ args: { sourceId: 30, targetId: 999 } }])
    const union = describeType(30, loaded)
    assert.equal(union.memberCount, 20)
    assert.equal(union.members.length, 6)
    assert.equal(union.omittedMembers, 14)
    assert.ok(union.members.every(member => member.startsWith('"case-')))
    assert.equal(describeType(999, loaded).resolved, false)
    assert.equal(describeType(999, loaded).label, 'type #999')
    const [event] = indexEvents([{ name: 'structuredTypeRelatedTo', pid: 1, tid: 1, ts: 0, durationMs: 50,
      args: { sourceId: 30, targetId: 999 } }])
    const analysis = costAnalysis(event, null, '', loaded)
    assert.match(analysis.actions[0].basis, /20 members/)
    assert.match(analysis.actions[0].basis, /does not prove/)
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('Vue guidance retains default and dynamic model contracts and marks truncated captured code', () => {
  for (const [directive, expected] of [['v-model', 'update:modelValue'], ['v-model:[field]', 'dynamically selected model prop']]) {
    const source = `<template><Widget ${directive}="value" /></template>`
    const start = source.indexOf('value')
    const context = sourceContext(source, start, start + 5, parse(source).descriptor)
    const [event] = indexEvents([{ name: 'checkVariableDeclaration', pid: 1, tid: 1, ts: 0, durationMs: 50 }])
    assert.match(costAnalysis(event, context, '', new Map()).actions[0].change, new RegExp(expected))
  }
  const source = '<template><Widget :payload="' + 'value.'.repeat(100) + 'code" /></template>'
  const start = source.indexOf('Widget')
  const context = sourceContext(source, start, start + 6, parse(source).descriptor)
  assert.equal(context.sourceSnippet, source.slice(source.indexOf('<Widget'), source.indexOf('/>') + 2))
  const generated = 'const generated = ' + 'T'.repeat(2500)
  const [event] = indexEvents([{ name: 'checkExpression', pid: 1, tid: 1, ts: 0, durationMs: 50, args: { pos: 0, end: generated.length } }])
  const result = explainEvent(event, { sourceText: source, compilerText: generated, mappings: [] }, { start, end: start + 6 }, context)
  assert.equal(result.sourceTruncated, true)
  assert.equal(result.generatedTruncated, true)
  assert.equal(result.sourceSnippet.length, 500)
  assert.equal(result.generatedSnippet.length, 2400)
})

test('operation attribution excludes unrelated threads and spans, retains nesting and reports omitted operations', () => {
  const events = indexEvents([
    { name: 'scope', pid: 1, tid: 1, ts: 0, durationMs: 100 },
    ...Array.from({ length: 16 }, (_, index) => ({ name: 'child-' + index, pid: 1, tid: 1, ts: index * 5000, durationMs: 2 })),
    { name: 'foreign-thread', pid: 1, tid: 2, ts: 1000, durationMs: 10 },
    { name: 'outside-scope', pid: 1, tid: 1, ts: 200000, durationMs: 10 },
  ])
  const analysis = costAnalysis(events[0], null, '', new Map())
  assert.equal(analysis.totalOperations, 16)
  assert.equal(analysis.operations.length, 12)
  assert.equal(analysis.omittedOperations, 4)
  assert.ok(analysis.operations.every(operation => operation.event.startsWith('child-') && operation.depth === 1))
  assert.equal(events[0].childMs, 32)
  assert.equal(events[0].unattributedMs, 68)
})

test('Vue experiments prioritize the mapped component contract and keep dependency types out of proposed edits', () => {
  const types = new Map([
    [1, { id: 1, symbolName: 'Props', intersectionTypes: [2, 3], firstDeclaration: {
      path: '/project/node_modules/vue/runtime.d.ts', start: { line: 10, character: 1 },
    } }],
    [2, { id: 2, symbolName: 'Model' }], [3, { id: 3, symbolName: 'Events' }],
  ])
  const [event] = indexEvents([{ name: 'structuredTypeRelatedTo', pid: 1, tid: 1, ts: 0, durationMs: 10,
    args: { sourceId: 2, targetId: 1 } }])
  const analysis = costAnalysis(event, { directive: 'model', component: 'Widget', binding: 'code', subject: 'Widget · v-model:code' }, '', types)
  assert.match(analysis.actions[0].title, /Widget's model contract/)
  assert.ok(analysis.actions.every(action => action.change.includes('do not edit generated code or node_modules')))
  assert.ok(analysis.actions.some(action => action.title === 'Review the object composition'))
  assert.ok(!analysis.actions.some(action => action.title.includes('union')))
})

test('export joins a measured declaration to its nested type pair and keeps relation endpoint time shared', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'ts-costs-evidence-'))
  try {
    await mkdir(path.join(root, 'node_modules'))
    const base = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
    await symlink(path.join(base, 'node_modules/typescript'), path.join(root, 'node_modules/typescript'), 'dir')
    await writeFile(path.join(root, 'package.json'), '{}')
    const run = path.join(root, 'run')
    await mkdir(path.join(run, 'sources'), { recursive: true })
    await mkdir(path.join(run, 'trace'))
    const file = path.join(root, 'sample.ts')
    const sourceText = 'type Left = { value: string }; type Right = { value: number }; const item: Right = { value: 1 };'
    await writeFile(path.join(run, 'sources/sample.json'), JSON.stringify({ path: file, sourceText, compilerLineStarts: [0] }))
    await writeFile(path.join(run, 'capture.json'), JSON.stringify({ files: { [file]: 'sample.json' }, caseSensitive: true }))
    await writeFile(path.join(run, 'trace/trace.json'), JSON.stringify([
      { ph: 'X', name: 'checkVariableDeclaration', pid: 1, tid: 1, ts: 0, dur: 100000, args: { path: file, pos: 0, end: sourceText.length } },
      { ph: 'X', name: 'structuredTypeRelatedTo', pid: 1, tid: 1, ts: 10000, dur: 40000, args: { sourceId: 10, targetId: 20 } },
      { ph: 'X', name: 'structuredTypeRelatedTo', pid: 1, tid: 2, ts: 10000, dur: 40000, args: { sourceId: 999, targetId: 998 } },
    ]))
    const declaration = (start, end) => ({ path: file, start: { line: 1, character: start + 1 }, end: { line: 1, character: end + 1 } })
    await writeFile(path.join(run, 'trace/types.json'), '[\n' + [
      { id: 10, symbolName: 'Left', firstDeclaration: declaration(0, 29) },
      { id: 20, symbolName: 'Right', firstDeclaration: declaration(30, 60) },
    ].map(type => JSON.stringify(type)).join(',\n') + '\n]')
    const profile = await exportProfile(run, root)
    const hints = profile.files[0].hints
    const measured = hints.find(hint => hint.kind === 'declaration')
    assert.equal(measured.analysis.operations.length, 1)
    assert.equal(measured.analysis.operations[0].sourceType.label, 'Left')
    assert.equal(measured.analysis.operations[0].targetType.label, 'Right')
    assert.equal(measured.analysis.operations[0].ms, 40)
    const endpoints = hints.filter(hint => hint.kind === 'type relation')
    assert.equal(endpoints.length, 2)
    assert.equal(endpoints[0].eventKey, endpoints[1].eventKey)
    assert.equal(profile.summary.uniqueEvents, 2)
    assert.equal(profile.summary.mappedMs, 100)
  } finally { await rm(root, { recursive: true, force: true }) }
})
