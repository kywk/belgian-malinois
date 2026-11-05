#!/usr/bin/env node
'use strict'

// Validates contract artifacts only. No HTTP, credentials, grants or workflow execution.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const root = path.resolve(__dirname, '..')
const dir = path.join(root, 'docs/plan/contracts/staff-agent-v1')
let Ajv
try {
  Ajv = require(path.join(root, 'bpm-frontend/node_modules/ajv'))
} catch {
  process.stderr.write('Missing existing frontend Ajv dependency; prepare frontend dependencies per README.\n')
  process.exit(1)
}
const read = name => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'))
const schema = read('schemas.json')
const catalog = read('catalog.json')
const examples = read('examples.json')
const ajv = new Ajv({ allErrors: true, strict: true })
ajv.addFormat('utc-date-time', value => {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) return false
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return false
  return date.toISOString() === (value.includes('.') ? value : value.replace('Z', '.000Z'))
})
ajv.addFormat('json-object-string', value => {
  try {
    const parsed = JSON.parse(value)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
  } catch {
    return false
  }
})
ajv.addSchema(schema)
for (const name of Object.keys(schema.definitions)) {
  assert.ok(ajv.getSchema(`${schema.$id}#/definitions/${name}`), `Cannot compile ${name}`)
}
const permissionCodes = catalog.permissions.map(item => item.code)
assert.equal(new Set(permissionCodes).size, permissionCodes.length, 'Duplicate permission code')
assert.ok(permissionCodes.every(code => /^bpm:[a-z0-9_]+:[a-z0-9_]+:[a-z0-9_]+$/.test(code)))
const aliases = catalog.permissions.map(item => item.legacyAuthority).filter(Boolean)
assert.equal(new Set(aliases).size, aliases.length, 'Ambiguous legacy authority mapping')
assert.ok(!aliases.some(value => value === '*' || value.startsWith('ROLE_')))
const roleNames = new Set()
for (const role of catalog.roleTemplates) {
  assert.ok(!roleNames.has(role.name), `Duplicate role ${role.name}`)
  roleNames.add(role.name)
  for (const code of role.permissions) {
    const permission = catalog.permissions.find(item => item.code === code)
    assert.ok(permission, `${role.name}: unknown permission ${code}`)
    assert.ok(!permission.testOnly || role.testOnly, `${role.name}: test grant in production template`)
  }
  if (role.principalType === 'STAFF') assert.ok(role.permissions.includes('bpm:console:access:use'))
  if (role.principalType === 'SERVICE') assert.ok(!role.permissions.includes('bpm:console:access:use'))
}
const errors = new Map(catalog.errors.map(item => [item.code, item]))
assert.equal(errors.size, catalog.errors.length, 'Duplicate error code')
assert.deepEqual(schema.definitions.StaffSession.properties.permissions.items.enum, permissionCodes)
assert.deepEqual(schema.definitions.BpmError.properties.code.enum, [...errors.keys()])
const ids = new Set()
let positive = 0
let negative = 0
for (const example of examples.cases) {
  assert.ok(!ids.has(example.id), `Duplicate example ${example.id}`)
  ids.add(example.id)
  assert.equal(typeof example.expectValid, 'boolean')
  const validate = ajv.getSchema(`${schema.$id}#/definitions/${example.schema}`)
  assert.ok(validate, `Unknown schema ${example.schema}`)
  const actual = validate(example.data)
  assert.equal(actual, example.expectValid,
    `${example.id}: unexpected schema result\n${JSON.stringify(validate.errors, null, 2)}`)
  if (actual) positive++
  else negative++
  if (example.expectValid && errors.has(example.data.code)) {
    const spec = errors.get(example.data.code)
    assert.equal(example.httpStatus, spec.httpStatus, `${example.id}: HTTP mismatch`)
    assert.equal(example.data.retryable, spec.retryable, `${example.id}: retry mismatch`)
  }
  if (example.expectValid && example.data.caseRef && example.data.businessKey) {
    const c = example.data.caseRef
    assert.equal(example.data.businessKey,
      `${c.domain}/${c.applicationId}/${c.submissionId}/${c.workflowRole}`,
      `${example.id}: inconsistent fixture businessKey`)
  }
}
// Keep documentation examples and references reviewable; no remote fetching.
let links = 0
let inlineExamples = 0
for (const file of ['README.md', 'identity-authz.md', 'case-api.md', 'errors-acceptance.md']) {
  const source = fs.readFileSync(path.join(dir, file), 'utf8')
  for (const match of source.matchAll(/\]\(([^)]+)\)/g)) {
    const target = match[1].split('#')[0]
    if (!target || target.includes('://')) continue
    assert.ok(fs.existsSync(path.resolve(dir, target)), `${file}: missing ${target}`)
    links++
  }
  for (const match of source.matchAll(/```json\n([\s\S]*?)\n```/g)) {
    JSON.parse(match[1])
    inlineExamples++
  }
}
const identityDoc = fs.readFileSync(path.join(dir, 'identity-authz.md'), 'utf8')
const errorDoc = fs.readFileSync(path.join(dir, 'errors-acceptance.md'), 'utf8')
for (const code of permissionCodes) assert.ok(identityDoc.includes(code), `Undocumented permission ${code}`)
for (const code of errors.keys()) assert.ok(errorDoc.includes(code), `Undocumented error ${code}`)
const scenarios = [...errorDoc.matchAll(/^\| ((?:ID|AZ|BF|WF|EV)-\d+) \|/gm)].map(match => match[1])
assert.equal(new Set(scenarios).size, scenarios.length, 'Duplicate acceptance scenario')
assert.ok(scenarios.length > 0)
process.stdout.write(`PASS: ${Object.keys(schema.definitions).length} schemas; ${positive} valid + ${negative} invalid examples; ${permissionCodes.length} permissions; ${roleNames.size} role templates; ${errors.size} error codes; ${links} local links; ${inlineExamples} inline JSON blocks.\n`)
process.stdout.write(`Documented ${scenarios.length} behavior scenarios; NOT executed by this validator.\n`)
