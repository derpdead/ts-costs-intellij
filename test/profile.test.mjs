import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, mkdir, writeFile, readFile, symlink, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { parse } from 'vue/compiler-sfc'
import { indexEvents, intervalCoverage, parseDiagnostics } from '../bridge/trace.mjs'
import { sourceContext } from '../bridge/context.mjs'
import { mergeProfiles } from '../bridge/export.mjs'

const base = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const config = { compilerOptions: { noEmit: true, strict: true, skipLibCheck: true, target: 'ESNext', module: 'ESNext', moduleResolution: 'Bundler', types: [] }, include: ['*.ts', '*.vue'] }

async function fixture(files, dependencies = {}, version = 'typescript') {
  const root = await mkdtemp(path.join(tmpdir(), 'ts-costs-profile-'))
  await mkdir(path.join(root, 'node_modules'))
  for (const name of ['typescript', 'vue', 'vue-tsc', 'nuxt']) {
    await symlink(path.join(base, 'node_modules', name === 'typescript' ? version : name), path.join(root, 'node_modules', name), 'dir')
  }
  await writeFile(path.join(root, 'package.json'), JSON.stringify({ private: true, type: 'module', dependencies }))
  for (const [name, text] of Object.entries({ 'tsconfig.json': JSON.stringify(config), ...files })) {
    await mkdir(path.dirname(path.join(root, name)), { recursive: true })
    await writeFile(path.join(root, name), text)
  }
  return root
}
function record(root, ...args) {
  return spawnSync(process.execPath, [path.join(base, 'bridge/profile.mjs'), '--project', root, ...args], { encoding: 'utf8', timeout: 90000, maxBuffer: 8 * 1024 * 1024 })
}
async function profile(root) { return JSON.parse(await readFile(path.join(root, 'node_modules/.cache/ts-costs/latest-profile.json'), 'utf8')) }

test('summary unions nested spans and relation endpoints instead of summing badges', () => {
  const events = indexEvents([
    { name: 'outer', pid: 1, tid: 1, ts: 0, durationMs: 10 },
    { name: 'inner', pid: 1, tid: 1, ts: 1000, durationMs: 5 },
    { name: 'sibling', pid: 1, tid: 1, ts: 7000, durationMs: 2 },
  ])
  assert.equal(intervalCoverage([...events, events[1]]), 10)
  assert.equal(events[0].unattributedMs, 3)
  assert.equal(events[1].parent, events[0])
  assert.deepEqual(parseDiagnostics('Memory used: 1024K\nCheck time: 1.23s\nTypes: 99'), { 'Memory used': 1048576, 'Check time': 1230, Types: 99 })
  const part = { files: [{ path: 'a.ts', sha256: 'a', fileMs: 10, mappedMs: 10, hints: [{ eventKey: 'e' }] }], summary: { cpuMs: 12, peakRssBytes: 100, uniqueEvents: 1, mappedMs: 10 }, unmappedEvents: 0 }
  const merged = mergeProfiles([part, part], { wallMs: 50, prepareMs: 5 })
  assert.equal(merged.summary.cpuMs, 24)
  assert.equal(merged.summary.peakRssBytes, 100)
  assert.equal(merged.files[0].fileMs, 20)
  assert.notEqual(merged.files[0].hints[0].eventKey, merged.files[0].hints[1].eventKey)
})

test('Vue model attribution explains generated work at the exact source directive', () => {
  const source = '<script setup lang="ts">const value=1</script>\n<template><Widget v-model:code="value" /></template>'
  const start = source.lastIndexOf('value')
  const context = sourceContext(source, start, start + 5, parse(source).descriptor)
  assert.equal(context.title, 'Model event check')
  assert.equal(context.subject, 'Widget · v-model:code')
  assert.equal(context.sourceSnippet, 'v-model:code="value"')
})

for (const version of ['typescript', 'typescript6']) {
  test(`Record captures actual CPU, peak RSS and fresh types with ${version}`, async () => {
    const root = await fixture({ 'sample.ts': 'type Deep<T> = { [K in keyof T]: T[K] }; const item: Deep<{code:number}> = {code:1}\n' }, {}, version)
    try {
      const first = record(root)
      assert.equal(first.status, 0, first.stdout + first.stderr)
      const data = await profile(root)
      if (version === 'typescript') {
        await mkdir(path.join(base, 'build'), { recursive: true })
        await writeFile(path.join(base, 'build/test-profile.json'), JSON.stringify(data))
      }
      assert.equal(data.schemaVersion, 2)
      assert.ok(data.summary.cpuMs > 0)
      assert.ok(data.summary.peakRssBytes > 0)
      assert.ok(data.summary.wallMs >= data.summary.compilerWallMs)
      assert.ok(data.files.some(file => file.path === 'sample.ts'))
      const types = JSON.parse(await readFile(path.join(path.dirname(data.tracePath), 'types.json'), 'utf8'))
      assert.ok(types.length > 0)
      await writeFile(path.join(root, 'sample.ts'), 'const item: number = "invalid"\n')
      const second = record(root)
      assert.notEqual(second.status, 0)
      const errors = await profile(root)
      assert.ok(errors.summary.exitCode > 0)
      assert.notEqual(data.files[0].sha256, errors.files[0].sha256)
      const before = await readFile(path.join(root, 'node_modules/.cache/ts-costs/latest-profile.json'), 'utf8')
      const failed = record(root, '--config', 'missing.json')
      assert.notEqual(failed.status, 0)
      assert.equal(await readFile(path.join(root, 'node_modules/.cache/ts-costs/latest-profile.json'), 'utf8'), before)
    } finally { await rm(root, { recursive: true, force: true }) }
  })
}

test('Record expands a solution config into separately measured projects', async () => {
  const root = await fixture({
    'tsconfig.json': '{"files":[],"references":[{"path":"./one"},{"path":"./two"}]}',
    'one/tsconfig.json': JSON.stringify({ ...config, include: ['*.ts'] }),
    'two/tsconfig.json': JSON.stringify({ ...config, include: ['*.ts'] }),
    'one/a.ts': 'export type A = { value: number }',
    'two/b.ts': 'export type B = { text: string }',
  })
  try {
    const result = record(root)
    assert.equal(result.status, 0, result.stdout + result.stderr)
    const data = await profile(root)
    assert.equal(data.summary.projects, 2)
    assert.deepEqual(data.files.map(file => file.path).sort(), ['one/a.ts', 'two/b.ts'])
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('Record regenerates Nuxt types and captures Vue generated code automatically', async () => {
  const root = await fixture({
    'nuxt.config.mjs': 'export default { telemetry: false, devtools: { enabled: false } }',
    'app.vue': '<script setup lang="ts">const greeting: string = "hello"</script>\n<template><span>{{ greeting }}</span></template>',
    'tsconfig.json': '{"extends":"./.nuxt/tsconfig.json"}',
  }, { nuxt: '4.6.4', vue: '3.5.45', 'vue-tsc': '3.3.14', typescript: '5.9.3' })
  try {
    const result = record(root)
    assert.equal(result.status, 0, result.stdout + result.stderr)
    const data = await profile(root)
    assert.equal(data.summary.framework, 'Nuxt')
    assert.ok(data.summary.prepareMs > 0)
    assert.ok((await readFile(path.join(root, '.nuxt/nuxt.d.ts'), 'utf8')).length > 0)
    assert.ok(data.files.some(file => file.path === 'app.vue'))
    const capture = JSON.parse(await readFile(path.join(path.dirname(path.dirname(data.tracePath)), 'capture.json'), 'utf8'))
    const entry = Object.entries(capture.files).find(([name]) => name.endsWith('/app.vue'))
    const snapshot = JSON.parse(await readFile(path.join(path.dirname(path.dirname(data.tracePath)), 'sources', entry[1]), 'utf8'))
    assert.ok(snapshot.compilerText.includes('__VLS_'))
    assert.ok(snapshot.mappings.length > 0)
  } finally { await rm(root, { recursive: true, force: true }) }
})
