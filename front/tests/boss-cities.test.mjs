import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../lib/boss-cities.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
const { citySequence, moveCity } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('old single city and ordered city lists retain all cities without duplicates', () => {
  assert.deepEqual(citySequence('广州'), ['广州'])
  assert.deepEqual(citySequence('["深圳", "广州", "深圳", "上海"]'), ['深圳', '广州', '上海'])
  assert.deepEqual(citySequence('101280100，101280600'), ['101280100', '101280600'])
})
test('reordering cities preserves the stored input and neither loses nor duplicates cities', () => {
  const cities = ['广州', '深圳', '上海']
  assert.deepEqual(moveCity(cities, 2, -1), ['广州', '上海', '深圳'])
  assert.deepEqual(moveCity(cities, 0, 1), ['深圳', '广州', '上海'])
  assert.deepEqual(cities, ['广州', '深圳', '上海'])
  assert.equal(moveCity(cities, 0, -1), cities)
})
