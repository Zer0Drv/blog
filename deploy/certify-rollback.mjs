import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { readFile, appendFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

const execute = promisify(execFile)

export function selectRelease(matrix, tag, frontendSha, database) {
  if (!/^sha-[0-9a-f]{8}$/.test(tag)) throw new Error('Invalid rollback tag')
  const entry = matrix.releases.find(release => release.tag === tag)
  if (matrix.schemaVersion !== 1 || !entry || !/^[0-9a-f]{40}$/.test(entry.sha) ||
      tag !== `sha-${entry.sha.slice(0, 8)}` || !/^sha256:[0-9a-f]{64}$/.test(entry.digest)) {
    throw new Error('Rollback target has no reviewed immutable certification')
  }
  if (!entry.frontendShas.includes(frontendSha) || database.version !== entry.schema ||
      database.checksum !== entry.schemaChecksum || database.failedCount !== 0) {
    throw new Error('Frontend/schema combination is not certified; never downgrade the database')
  }
  return entry
}

export function verifyRun(entry, run, jobs, log) {
  if (run.head_sha !== entry.sha || run.head_branch !== 'main' || run.conclusion !== 'success' ||
      run.path !== '.github/workflows/cd.yml' || !['push', 'workflow_dispatch'].includes(run.event)) {
    throw new Error('Historical main CD does not certify this commit')
  }
  for (const name of ['ci / build', 'ci / flyway-migration', 'ci / real-dependencies', 'ci / browser-e2e', 'image']) {
    if (!jobs.some(job => job.name === name && job.conclusion === 'success')) {
      throw new Error(`Historical required job missing or failed: ${name}`)
    }
  }
  if (!log.includes(`${entry.tag}: digest: ${entry.digest} `)) {
    throw new Error('Immutable image digest is not present in the original successful push log')
  }
}

export async function certify(matrixPath, tag, frontendTools, gateway, { run = execute } = {}) {
  const { collectDeploymentEvidence } = await import(pathToFileURL(resolve(frontendTools, 'deployment-evidence.mjs')))
  const releaseResponse = await fetch(new URL('/release.json', gateway), { signal: AbortSignal.timeout(10000), cache: 'no-store' })
  if (!releaseResponse.ok) throw new Error('Cannot read current frontend identity')
  const frontend = await releaseResponse.json()
  const observed = await collectDeploymentEvidence(gateway, { expectedSha: frontend.sha })
  const entry = selectRelease(JSON.parse(process.env.RELEASE_COMPATIBILITY_JSON || await readFile(matrixPath, 'utf8')),
    tag, frontend.sha, observed.database)
  if (!/^\d+$/.test(entry.runId)) throw new Error('Invalid historical run')
  async function github(path) {
    const result = await run('gh', ['api', `repos/Zer0Drv/blog/${path}`], { timeout: 30000, maxBuffer: 2 * 1024 * 1024 })
    return JSON.parse(result.stdout)
  }
  const original = await github(`actions/runs/${entry.runId}`)
  const page = await github(`actions/runs/${entry.runId}/jobs?per_page=100`)
  if (page.total_count !== page.jobs.length) throw new Error('Incomplete historical job inventory')
  const log = await run('gh', ['run', 'view', entry.runId, '--repo', 'Zer0Drv/blog', '--log'],
    { timeout: 120000, maxBuffer: 32 * 1024 * 1024 })
  verifyRun(entry, original, page.jobs, log.stdout)
  return { ...entry, pinnedTag: `${tag}@${entry.digest}`, previousBackend: observed.backend, frontendSha: frontend.sha }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const result = await certify(...process.argv.slice(2))
    if (process.env.GITHUB_OUTPUT) {
      await appendFile(process.env.GITHUB_OUTPUT, `tag=${result.pinnedTag}\nsha=${result.sha}\n`)
    }
    console.log(JSON.stringify(result, null, 2))
  } catch (error) {
    console.error(error.cmd ? 'Unable to read historical CI certification' : error.message)
    process.exitCode = 1
  }
}
