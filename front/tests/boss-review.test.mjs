import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../lib/boss-review.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
const { exclusionLines } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('title exclusions retain whole role terms and remove duplicates and empty lines', () => {
  assert.deepEqual(exclusionLines(' 产品经理\r\n销售经理\n产品经理\n\nAI售前顾问 '), ['产品经理', '销售经理', 'AI售前顾问'])
})
