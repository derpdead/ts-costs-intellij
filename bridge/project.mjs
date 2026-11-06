import { createRequire } from 'node:module'
import { existsSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'

export function inspectProject(root, configOverride) {
  const require = createRequire(path.join(root, 'package.json'))
  const pkg = existsSync(path.join(root, 'package.json')) ? JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')) : {}
  let ts
  try { ts = require('typescript') } catch { throw new Error('Install this project’s TypeScript dependencies before recording.') }
  if (+ts.versionMajorMinor.split('.')[0] < 5 || +ts.versionMajorMinor.split('.')[0] >= 7) {
    throw new Error('TS Costs requires the JavaScript TypeScript 5 or 6 compiler. Native tsgo tracing is not supported.')
  }
  const dependencies = { ...pkg.dependencies, ...pkg.devDependencies }
  const nuxt = Boolean(dependencies.nuxt || ['ts', 'js', 'mjs'].some(extension => existsSync(path.join(root, `nuxt.config.${extension}`))))
  const vue = nuxt || Boolean(dependencies.vue)
  let cli
  if (vue) {
    try { cli = require.resolve('vue-tsc/bin/vue-tsc.js') }
    catch { throw new Error('Vue projects need vue-tsc installed in their own dependencies. Install it, then record again.') }
  }
  const wrapperMatch = pkg.scripts?.typecheck?.match(/^node\s+([\w./-]+\.[cm]?js)\s*$/)
  const wrapper = vue && !configOverride && wrapperMatch && existsSync(path.resolve(root, wrapperMatch[1])) ? path.resolve(root, wrapperMatch[1]) : null
  let prepare
  if (nuxt && !wrapper) {
    const nuxtPackage = require.resolve('nuxt/package.json')
    const metadata = JSON.parse(readFileSync(nuxtPackage, 'utf8'))
    const bin = typeof metadata.bin === 'string' ? metadata.bin : metadata.bin?.nuxt ?? metadata.bin?.nuxi
    if (!bin) throw new Error('Cannot locate the local Nuxt prepare command.')
    prepare = path.resolve(path.dirname(nuxtPackage), bin)
  }
  const appConfig = path.join(root, '.nuxt/tsconfig.app.json')
  const config = configOverride ? path.resolve(root, configOverride)
    : nuxt && existsSync(appConfig) ? appConfig : path.join(root, 'tsconfig.json')
  return { framework: nuxt ? 'Nuxt' : vue ? 'Vue' : 'TypeScript', compiler: vue ? 'vue' : 'tsc', cli, wrapper, prepare, config }
}

export function projectConfigs(root, config) {
  const ts = createRequire(path.join(root, 'package.json'))('typescript')
  const result = [], seen = new Set()
  function visit(value) {
    const filename = existsSync(value) && statSync(value).isDirectory() ? path.join(value, 'tsconfig.json') : value
    if (seen.has(filename)) return
    seen.add(filename)
    const parsed = ts.readConfigFile(filename, ts.sys.readFile)
    if (parsed.error) throw new Error(ts.flattenDiagnosticMessageText(parsed.error.messageText, '\n'))
    const cfg = parsed.config
    if (cfg.references?.length && cfg.files?.length === 0 && !cfg.include) {
      for (const ref of cfg.references) visit(path.resolve(path.dirname(filename), ref.path))
    } else result.push(filename)
  }
  visit(config)
  if (!result.length) throw new Error('The selected TypeScript solution contains no projects.')
  return result
}
