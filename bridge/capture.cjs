const { createRequire } = require('node:module')
const { mkdirSync, writeFileSync } = require('node:fs')
const { createHash } = require('node:crypto')
const path = require('node:path')

const root = process.env.TS_COSTS_PROJECT
const output = process.env.TS_COSTS_CAPTURE_DIR
const records = new Map()
const measuredAt = new Date().toISOString()
const started = performance.now()
const cpuStart = process.cpuUsage()
let caseSensitive = process.platform !== 'win32' && process.platform !== 'darwin'
const versions = { node: process.versions.node }

function captureProgram(program, language) {
  for (const file of program.getSourceFiles()) {
    const absolute = path.resolve(file.fileName)
    const relative = path.relative(root, absolute)
    if (relative.startsWith('..') || path.isAbsolute(relative)
      || /(^|[/\\])(node_modules|\.nuxt|\.cache|\.git)([/\\]|$)/.test(relative)
      || !/\.(vue|[cm]?tsx?)$/.test(relative)) continue
    const script = language?.scripts.get(file.fileName)
    const sourceText = script ? script.snapshot.getText(0, script.snapshot.getLength()) : file.text
    const service = script?.generated?.languagePlugin.typescript?.getServiceScript(script.generated.root)
    records.set(caseSensitive ? absolute : absolute.toLowerCase(), {
      path: absolute, sourceText, compilerText: file.text,
      compilerLineStarts: [0, ...Array.from(file.text.matchAll(/\r\n|\r|\n|\u2028|\u2029/g), match => match.index + match[0].length)],
      mappingOffset: service && !service.preventLeadingOffset ? sourceText.length : 0,
      mappings: service?.code.mappings.map(mapping => ({
        sourceOffsets: mapping.sourceOffsets, generatedOffsets: mapping.generatedOffsets,
        lengths: mapping.lengths, generatedLengths: mapping.generatedLengths,
      })) ?? null,
    })
  }
}
exports.captureProgram = captureProgram

// Package-manager parents inherit NODE_OPTIONS too. Only the compiler writes this capture.
const compilerProcess = process.argv[1]?.includes('vue-tsc') || path.basename(process.argv[1] ?? '') === 'compiler.cjs'
if (root && output && compilerProcess) {
  const projectRequire = createRequire(path.join(root, 'package.json'))
  const ts = projectRequire('typescript')
  caseSensitive = ts.sys.useCaseSensitiveFileNames
  versions.typescript = projectRequire('typescript/package.json').version
  if (process.argv[1].includes('vue-tsc')) {
    const vueRequire = createRequire(projectRequire.resolve('vue-tsc'))
    const proxyModule = vueRequire('@volar/typescript/lib/node/proxyCreateProgram')
    const original = proxyModule.proxyCreateProgram
    versions.vueTsc = projectRequire('vue-tsc/package.json').version
    versions.vueCore = vueRequire('@vue/language-core/package.json').version
    versions.volar = vueRequire('@volar/typescript/package.json').version
    proxyModule.proxyCreateProgram = (typescript, createProgram, createPlugins) => {
      let language
      const proxied = original(typescript, createProgram, (...args) => {
        const created = createPlugins(...args)
        const result = Array.isArray(created) ? { languagePlugins: created } : created
        const setup = result.setup
        return { ...result, setup(value) { language = value; setup?.(value) } }
      })
      return new Proxy(proxied, {
        apply(target, self, args) {
          const program = Reflect.apply(target, self, args)
          captureProgram(program, language)
          return program
        },
      })
    }
  }
  process.on('exit', () => {
    const cpu = process.cpuUsage(cpuStart)
    const memory = process.memoryUsage()
    const metrics = {
      scope: 'Compiler process including trace instrumentation and type dump; excludes snapshot serialization.',
      wallMs: performance.now() - started,
      cpuUserMs: cpu.user / 1000, cpuSystemMs: cpu.system / 1000,
      cpuMs: (cpu.user + cpu.system) / 1000,
      peakRssBytes: process.resourceUsage().maxRSS * 1024,
      heapUsedAtExitBytes: memory.heapUsed, rssAtExitBytes: memory.rss,
    }
    mkdirSync(path.join(output, 'sources'), { recursive: true })
    const files = {}
    for (const [key, record] of records) {
      const name = createHash('sha256').update(key).digest('hex') + '.json'
      writeFileSync(path.join(output, 'sources', name), JSON.stringify(record))
      files[key] = name
    }
    writeFileSync(path.join(output, 'capture.json'), JSON.stringify({ measuredAt, caseSensitive, versions, files, metrics }))
  })
}
