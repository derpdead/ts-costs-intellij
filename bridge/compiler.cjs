const { createRequire, Module } = require('node:module')
const { readFileSync, existsSync } = require('node:fs')
const path = require('node:path')

const projectRequire = createRequire(path.join(process.env.TS_COSTS_PROJECT, 'package.json'))
let filename = projectRequire.resolve('typescript/lib/tsc.js')
const actual = path.join(path.dirname(filename), '_tsc.js')
if (existsSync(actual)) filename = actual
let source = readFileSync(filename, 'utf8')
const signature = /function createProgram\(([^)]*)\) \{/
if (!signature.test(source)) throw new Error('Unsupported TypeScript compiler internals. TS Costs supports the JavaScript TypeScript 5/6 compiler.')
source = source.replace(signature, (_, parameters) =>
  `function createProgram(...args) { const program = __tsCostsCreateProgram(...args); require(${JSON.stringify(path.join(__dirname, 'capture.cjs'))}).captureProgram(program); return program; }\nfunction __tsCostsCreateProgram(${parameters}) {`)
const compiler = new Module(filename, module)
compiler.filename = filename
compiler.paths = Module._nodeModulePaths(path.dirname(filename))
compiler._compile(source, filename)
