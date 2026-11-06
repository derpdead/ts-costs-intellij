import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, symlink, writeFile, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { compilerPosition, completedEvents, mapRange, sourceHash } from '../bridge/mapping.mjs'

const base = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const project = base

test('nested compiler spans keep their individual inclusive time', () => {
  const events = completedEvents([
    { ph: 'B', name: 'outer', pid: 1, tid: 1, ts: 0 },
    { ph: 'B', name: 'inner', pid: 1, tid: 1, ts: 1000 },
    { ph: 'E', name: 'inner', pid: 1, tid: 1, ts: 4000 },
    { ph: 'E', name: 'outer', pid: 1, tid: 1, ts: 8000 },
    { ph: 'X', name: 'other thread', pid: 1, tid: 2, ts: 2000, dur: 2000 },
  ])
  assert.equal(events.find(event => event.name === 'outer').durationMs, 8)
  assert.equal(events.find(event => event.name === 'inner').durationMs, 3)
  assert.equal(events.find(event => event.name === 'other thread').durationMs, 2)
  assert.throws(() => completedEvents([{ ph: 'B', name: 'unfinished', pid: 1, tid: 1, ts: 0 }]), /Incomplete/)
})

test('type declaration positions use the trace format’s one-based line and column', () => {
  const record = { compilerLineStarts: [0, 5, 16] }
  assert.equal(compilerPosition(record, { line: 2, character: 3 }), 7)
  assert.equal(compilerPosition(record, { line: 4, character: 1 }), null)
})

test('Vue prefix and disjoint generated regions never become a false source location', () => {
  const record = {
    sourceText: 'first + second', mappingOffset: 80,
    mappings: [{ sourceOffsets: [0, 8], generatedOffsets: [10, 30], lengths: [5, 6] }],
  }
  assert.equal(mapRange(record, 10, 15), null)
  assert.deepEqual(mapRange(record, 90, 95), { start: 0, end: 5, mapping: 'vue-source-map' })
  assert.equal(mapRange(record, 90, 116), null)
  assert.equal(mapRange(record, 100, 105), null)
})

test('snapshot fingerprints tolerate IDE newline normalization and reject changed source', () => {
  assert.equal(sourceHash('\uFEFFtype A = "😀"\r\n'), sourceHash('type A = "😀"\n'))
  assert.notEqual(sourceHash('type A = string\n'), sourceHash('type A = number\n'))
})

test('same-run Vue mappings locate generic script and template tokens in the original SFC', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'ts-costs-vue-'))
  try {
    await symlink(path.join(project, 'node_modules'), path.join(directory, 'node_modules'), 'dir')
    const source = '<!-- 😀 source offset fixture -->\n'
      + '<script setup lang="ts" generic="T extends { code: number }">\n'
      + 'const props = defineProps<{ item: T }>()\n'
      + 'const caption: string = "cost"\n'
      + '</script>\n<template><span>{{ caption }} {{ props.item.code }}</span></template>\n'
    await writeFile(path.join(directory, 'fixture.vue'), source)
    await writeFile(path.join(directory, 'package.json'), '{"private":true,"type":"module"}')
    await writeFile(path.join(directory, 'tsconfig.json'), JSON.stringify({
      compilerOptions: { noEmit: true, strict: true, skipLibCheck: true, target: 'ESNext', module: 'ESNext', moduleResolution: 'Bundler', types: [] },
      include: ['fixture.vue'],
    }))
    const capture = path.join(directory, 'capture')
    await mkdir(capture)
    const require = createRequire(path.join(project, 'package.json'))
    const cli = path.join(path.dirname(require.resolve('vue-tsc/package.json')), 'bin/vue-tsc.js')
    const result = spawnSync(process.execPath, ['--require', path.join(base, 'bridge/capture.cjs'), cli, '-p', path.join(directory, 'tsconfig.json')], {
      cwd: directory, encoding: 'utf8', timeout: 60000,
      env: { ...process.env, TS_COSTS_PROJECT: directory, TS_COSTS_CAPTURE_DIR: capture },
    })
    assert.equal(result.status, 0, result.stdout + result.stderr + (result.error?.message ?? ''))
    const manifest = JSON.parse(await readFile(path.join(capture, 'capture.json'), 'utf8'))
    const filename = path.join(directory, 'fixture.vue')
    const name = manifest.files[manifest.caseSensitive ? filename : filename.toLowerCase()]
    assert.ok(name, 'The same compiler run must capture the Vue source')
    const record = JSON.parse(await readFile(path.join(capture, 'sources', name), 'utf8'))
    assert.equal(record.sourceText, source)
    assert.equal(record.mappingOffset, source.length)
    for (const position of [source.indexOf('caption:'), source.lastIndexOf('caption'), source.lastIndexOf('props.item.code')]) {
      let match
      for (const mapping of record.mappings) {
        for (let i = 0; i < mapping.sourceOffsets.length; i += 1) {
          const offset = mapping.sourceOffsets[i]
          if (position >= offset && position < offset + mapping.lengths[i]) {
            const generated = record.mappingOffset + mapping.generatedOffsets[i] + position - offset
            const mapped = mapRange(record, generated, generated + 1)
            if (mapped?.start === position) match = mapped
          }
        }
      }
      assert.ok(match, `Original source token at ${position} must be reached through a compiler mapping`)
    }
  } finally { await rm(directory, { recursive: true, force: true }) }
})
