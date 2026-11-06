import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, readFileSync, renameSync } from 'node:fs'
import { delimiter, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { exportProfile, mergeProfiles } from './export.mjs'
import { inspectProject, projectConfigs } from './project.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const argument = name => { const index = process.argv.indexOf(name); return index < 0 ? undefined : process.argv[index + 1] }
const root = resolve(argument('--project') ?? process.cwd())
const configOverride = argument('--config')
const startedAt = new Date().toISOString(), started = performance.now()
const base = join(root, 'node_modules/.cache/ts-costs')
const run = join(base, 'runs', startedAt.replaceAll(':', '-'))
mkdirSync(run, { recursive: true })
const env = { ...process.env, TS_COSTS_PROJECT: root, PATH: [dirname(process.execPath), process.env.PATH].filter(Boolean).join(delimiter) }
let project = inspectProject(root, configOverride)
let prepareMs = 0, exitCode = 0
const profiles = []

function step(args, output, name, extraEnv = {}) {
  const before = performance.now()
  const result = spawnSync(process.execPath, args, { cwd: root, env: { ...env, ...extraEnv }, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 })
  const log = `${result.stdout ?? ''}${result.stderr ?? ''}`
  writeFileSync(join(output, name + '.log'), log)
  process.stdout.write(log)
  const measured = { exitCode: result.status, signal: result.signal, wallMs: performance.now() - before, error: result.error?.message }
  writeFileSync(join(output, name + '-result.json'), JSON.stringify(measured))
  if (result.error || result.signal) throw result.error ?? new Error(`Compiler terminated: ${result.signal}. See ${output}`)
  return measured
}

console.log(`TS Costs: ${project.framework} full check, trace, CPU profile and same-run source maps. Output: ${run}`)
if (project.wrapper) {
  const output = join(run, 'project-1')
  mkdirSync(join(output, 'trace'), { recursive: true })
  const result = step(['--require', join(here, 'wrapper-hook.cjs'), project.wrapper], output, 'typecheck', { TS_COSTS_RUN: output })
  if (!existsSync(join(output, 'capture.json'))) throw new Error('The typecheck wrapper did not launch a supported vue-tsc process. Select a tsconfig in TS Costs settings to use the automatic runner.')
  const compiler = JSON.parse(readFileSync(join(output, 'compiler-result.json'), 'utf8'))
  if (compiler.signal || compiler.error || compiler.exitCode == null) throw new Error('Compiler did not complete. The previous profile was preserved.')
  prepareMs = Math.max(0, result.wallMs - compiler.wallMs)
  exitCode = result.exitCode
  profiles.push(await exportProfile(output, root))
} else {
  if (project.prepare) {
    const result = step([project.prepare, 'prepare', root], run, 'prepare')
    if (result.exitCode !== 0) throw new Error(`Nuxt prepare failed. See ${run}`)
    prepareMs = result.wallMs
    project = inspectProject(root, configOverride)
  }
  for (const [index, config] of projectConfigs(root, project.config).entries()) {
    const output = join(run, `project-${index + 1}`)
    mkdirSync(join(output, 'trace'), { recursive: true })
    console.log(`Checking ${config}`)
    const result = step(['--max-old-space-size=6144', '--require', join(here, 'capture.cjs'),
      project.cli ?? join(here, 'compiler.cjs'), '--noEmit', '-p', config, '--incremental',
      '--tsBuildInfoFile', join(output, 'profile.tsbuildinfo'), '--generateTrace', join(output, 'trace'),
      '--generateCpuProfile', join(output, 'compiler.cpuprofile'), '--extendedDiagnostics', '--pretty', 'false'], output, 'compiler-output', { TS_COSTS_CAPTURE_DIR: output })
    writeFileSync(join(output, 'compiler-result.json'), JSON.stringify(result))
    exitCode = Math.max(exitCode, result.exitCode)
    const profile = await exportProfile(output, root)
    profile.config = config
    profiles.push(profile)
  }
}
const profile = mergeProfiles(profiles, { startedAt, wallMs: performance.now() - started, prepareMs, framework: project.framework, exitCode })
const destination = join(base, 'latest-profile.json')
writeFileSync(destination + '.tmp', JSON.stringify(profile))
renameSync(destination + '.tmp', destination)
writeFileSync(join(base, 'latest-run.txt'), run + '\n')
console.log(`Profile ready: ${destination}\n${profile.files.length} files; ${profile.summary.uniqueEvents} unique mapped events. Typecheck exit: ${exitCode}.`)
process.exitCode = exitCode
