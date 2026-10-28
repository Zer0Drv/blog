import { open, readFile, unlink } from 'node:fs/promises'
import { setTimeout as sleep } from 'node:timers/promises'
import { performance } from 'node:perf_hooks'

const [mode, owner] = process.argv.slice(2)
const path = process.env.DEPLOYMENT_LOCK || 'E:/dev/zer0drv/.deployment.lock'
if (!owner || !/^[\w/-]+$/.test(owner)) throw new Error('A deployment owner token is required')
if (mode === 'acquire') {
  const timeoutMs = Number(process.env.DEPLOYMENT_LOCK_TIMEOUT_MS ?? 600_000)
  const pollMs = Number(process.env.DEPLOYMENT_LOCK_POLL_MS ?? 2_000)
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 0 || !Number.isSafeInteger(pollMs) || pollMs < 1)
    throw new Error('Lock timeout must be a nonnegative integer and poll interval a positive integer (milliseconds)')
  const deadline = performance.now() + timeoutMs
  let file
  let waiting = false
  while (!file) {
    try { file = await open(path, 'wx') } catch (error) {
      if (error.code !== 'EEXIST') throw error
      const remaining = deadline - performance.now()
      if (remaining <= 0) throw new Error(`Timed out waiting for deployment lock after ${timeoutMs}ms: ${path}; existing lock preserved`)
      if (!waiting) { console.log('Another deployment or backup holds the host lock; waiting for release'); waiting = true }
      await sleep(Math.min(pollMs, remaining))
    }
  }
  try { await file.writeFile(owner) } finally { await file.close() }
} else if (mode === 'release') {
  if (await readFile(path, 'utf8') !== owner) throw new Error('Deployment lock belongs to another operation')
  await unlink(path)
} else throw new Error('Use acquire|release with an owner token')
