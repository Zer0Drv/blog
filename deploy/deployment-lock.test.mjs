import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { promisify } from 'node:util'
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const script = fileURLToPath(new URL('./deployment-lock.mjs', import.meta.url))
const exec = promisify(execFile)
async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'blog-lock-contention-'))
  const lock = join(root, 'operation.lock')
  const env = { ...process.env, DEPLOYMENT_LOCK: lock, DEPLOYMENT_LOCK_TIMEOUT_MS: '150', DEPLOYMENT_LOCK_POLL_MS: '10' }
  const invoke = (mode, owner, extra = {}) => exec(process.execPath, [script, mode, owner], { env: { ...env, ...extra }, timeout: 5000 })
  try { await run({ root, lock, env, invoke }) } finally { await rm(root, { recursive: true, force: true }) }
}

test('contending deployment waits for legitimate release and acquires the same atomic lock', async () => fixture(async ({ lock, env, invoke }) => {
  await invoke('acquire', 'frontend')
  const child = spawn(process.execPath, [script, 'acquire', 'backend'], {
    env: { ...env, DEPLOYMENT_LOCK_TIMEOUT_MS: '3000' }, windowsHide: true
  })
  const finished = new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(new Error(`exit ${code}`))) })
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('waiter did not start')), 4000)
      child.stdout.once('data', () => { clearTimeout(timer); resolve() })
    })
    assert.equal(await readFile(lock, 'utf8'), 'frontend')
    await invoke('release', 'frontend')
    await finished
    assert.equal(await readFile(lock, 'utf8'), 'backend')
    await invoke('release', 'backend')
  } finally { child.kill(); await finished.catch(() => {}) }
}))

test('timeout preserves the held lock and reports a bounded wait', async () => fixture(async ({ lock, invoke }) => {
  await writeFile(lock, 'backup')
  await assert.rejects(invoke('acquire', 'backend'), error => /Timed out waiting.*150ms/.test(error.stderr))
  assert.equal(await readFile(lock, 'utf8'), 'backup')
}))

test('two concurrent acquisitions cannot both succeed', async () => fixture(async ({ lock, invoke }) => {
  const results = await Promise.allSettled([invoke('acquire', 'one'), invoke('acquire', 'two')])
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1)
  const owner = await readFile(lock, 'utf8')
  assert.ok(['one', 'two'].includes(owner))
  await assert.rejects(invoke('release', owner === 'one' ? 'two' : 'one'))
  assert.equal(await readFile(lock, 'utf8'), owner)
  await invoke('release', owner)
}))

test('non-contention filesystem errors and invalid configuration fail immediately', async () => fixture(async ({ lock, invoke }) => {
  await writeFile(lock, 'file-not-directory')
  await assert.rejects(invoke('acquire', 'backend', { DEPLOYMENT_LOCK: join(lock, 'child') }), error => !/Timed out waiting/.test(error.stderr))
  await assert.rejects(invoke('acquire', 'backend', { DEPLOYMENT_LOCK_TIMEOUT_MS: '-1' }), error => /timeout must be/.test(error.stderr))
  assert.equal(await readFile(lock, 'utf8'), 'file-not-directory')
}))
