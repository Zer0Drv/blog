import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { selectRelease, verifyRun } from './certify-rollback.mjs'

const entry = { tag: 'sha-aaaaaaaa', sha: 'a'.repeat(40), digest: `sha256:${'b'.repeat(64)}`,
  frontendShas: ['c'.repeat(40)], schema: '17', schemaChecksum: 123 }
const database = { version: '17', checksum: 123, failedCount: 0 }
const matrix = { schemaVersion: 1, releases: [entry] }
test('host lock excludes concurrent operations and only its owner can release it', () => {
  const root = mkdtempSync(join(tmpdir(), 'blog-lock-test-'))
  const lock = join(root, 'operation.lock')
  const invoke = (mode, owner) => execFileSync(process.execPath,
    [fileURLToPath(new URL('./deployment-lock.mjs', import.meta.url)), mode, owner],
    { env: { ...process.env, DEPLOYMENT_LOCK: lock }, stdio: 'pipe' })
  try {
    invoke('acquire', 'owner-a')
    assert.throws(() => invoke('acquire', 'owner-b'))
    assert.throws(() => invoke('release', 'owner-b'))
    assert.equal(readFileSync(lock, 'utf8'), 'owner-a')
    invoke('release', 'owner-a')
    assert.equal(existsSync(lock), false)
  } finally {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()))
    rmSync(root, { recursive: true, force: true })
  }
})
test('unknown image, changed frontend and downgraded/changed schema fail before deployment', () => {
  assert.equal(selectRelease(matrix, entry.tag, 'c'.repeat(40), database), entry)
  for (const values of [
    ['sha-ffffffff', 'c'.repeat(40), database],
    [entry.tag, 'd'.repeat(40), database],
    [entry.tag, 'c'.repeat(40), { ...database, version: '16' }],
    [entry.tag, 'c'.repeat(40), { ...database, checksum: 124 }],
    [entry.tag, 'c'.repeat(40), { ...database, failedCount: 1 }]
  ]) assert.throws(() => selectRelease(matrix, ...values))
})
test('full CI and original push digest must certify exact successful main release', () => {
  const run = { head_sha: entry.sha, head_branch: 'main', conclusion: 'success', path: '.github/workflows/cd.yml', event: 'push' }
  const jobs = ['ci / build', 'ci / flyway-migration', 'ci / real-dependencies', 'ci / browser-e2e', 'image']
    .map(name => ({ name, conclusion: 'success' }))
  const log = `${entry.tag}: digest: ${entry.digest} size: 2201`
  assert.doesNotThrow(() => verifyRun(entry, run, jobs, log))
  assert.throws(() => verifyRun(entry, run, jobs, 'different push digest'))
  for (const job of jobs) assert.throws(() => verifyRun(entry, run, jobs.filter(other => other !== job), log))
  for (const change of [{ head_sha: 'd'.repeat(40) }, { head_branch: 'feature' }, { conclusion: 'failure' }, { event: 'pull_request' }]) {
    assert.throws(() => verifyRun(entry, { ...run, ...change }, jobs, log))
  }
})
