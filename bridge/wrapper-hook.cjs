const childProcess = require('node:child_process')
const { writeFileSync } = require('node:fs')
const { syncBuiltinESMExports } = require('node:module')
const path = require('node:path')

const output = process.env.TS_COSTS_RUN
if (output) {
  const original = childProcess.spawnSync
  let captured = false
  childProcess.spawnSync = function (command, args, options = {}) {
    if (!args?.includes('vue-tsc')) return original.call(this, command, args, options)
    if (captured) throw new Error('This wrapper launches multiple compilers. Select an explicit tsconfig in TS Costs settings instead.')
    captured = true
    const compilerArgs = [...args]
    const index = compilerArgs.indexOf('--tsBuildInfoFile')
    if (index >= 0) compilerArgs[index + 1] = path.join(output, 'profile.tsbuildinfo')
    else compilerArgs.push('--incremental', '--tsBuildInfoFile', path.join(output, 'profile.tsbuildinfo'))
    compilerArgs.push('--generateTrace', path.join(output, 'trace'), '--generateCpuProfile', path.join(output, 'compiler.cpuprofile'), '--extendedDiagnostics', '--pretty', 'false')
    const env = { ...process.env, ...options.env }
    const started = performance.now()
    const result = original.call(this, command, compilerArgs, {
      ...options, env: { ...env, TS_COSTS_CAPTURE_DIR: output,
        NODE_OPTIONS: `${env.NODE_OPTIONS ?? ''} --require ${JSON.stringify(path.join(__dirname, 'capture.cjs'))}` },
    })
    writeFileSync(path.join(output, 'compiler-output.log'), `${result.stdout ?? ''}${result.stderr ?? ''}`)
    writeFileSync(path.join(output, 'compiler-result.json'), JSON.stringify({
      exitCode: result.status, signal: result.signal, wallMs: performance.now() - started, error: result.error?.message,
    }))
    return result
  }
  syncBuiltinESMExports()
}
