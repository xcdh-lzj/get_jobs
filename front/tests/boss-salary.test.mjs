import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../lib/boss-salary.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
const { customSalaryFromSelection, salaryPolicyError } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('switching to custom salary uses the visible preset and preserves open boundaries', () => {
  assert.deepEqual(customSalaryFromSelection(['10-20K']), { mode: 'custom', minMonthlyK: 10, maxMonthlyK: 20 })
  assert.deepEqual(customSalaryFromSelection(['50K以上']), { mode: 'custom', minMonthlyK: 50, maxMonthlyK: null })
  assert.deepEqual(customSalaryFromSelection(['3K以下']), { mode: 'custom', minMonthlyK: null, maxMonthlyK: 3 })
  assert.deepEqual(customSalaryFromSelection(['不限']), { mode: 'custom', minMonthlyK: null, maxMonthlyK: null })
})

test('custom salary permits decimal and absent bounds while rejecting reversed or invalid amounts', () => {
  for (const bounds of [[12.5, 18.5], [15, null], [null, 20], [null, null]]) assert.equal(salaryPolicyError({ mode: 'custom', minMonthlyK: bounds[0], maxMonthlyK: bounds[1] }), '')
  for (const bounds of [[25, 10], [0, 20], [NaN, null], [null, Infinity]]) assert.ok(salaryPolicyError({ mode: 'custom', minMonthlyK: bounds[0], maxMonthlyK: bounds[1] }))
})

test('posted salary limits validate independently in either salary mode', () => {
  for (const mode of ['selection', 'custom']) {
    const base = { mode, minMonthlyK: null, maxMonthlyK: null }
    assert.equal(salaryPolicyError({ ...base, maxPostedMonthlyK: 20.5 }), '')
    assert.equal(salaryPolicyError({ ...base, minPostedMonthlyK: 12, maxPostedMonthlyK: 12 }), '')
    assert.ok(salaryPolicyError({ ...base, minPostedMonthlyK: 21, maxPostedMonthlyK: 20 }))
    assert.ok(salaryPolicyError({ ...base, minPostedMonthlyK: 0 }))
    assert.ok(salaryPolicyError({ ...base, maxPostedMonthlyK: Infinity }))
  }
})
