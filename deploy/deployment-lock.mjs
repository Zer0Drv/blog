import { open, readFile, unlink } from 'node:fs/promises'

const [mode, owner] = process.argv.slice(2)
const path = process.env.DEPLOYMENT_LOCK || 'E:/dev/zer0drv/.deployment.lock'
if (!owner || !/^[\w/-]+$/.test(owner)) throw new Error('A deployment owner token is required')
if (mode === 'acquire') {
  const file = await open(path, 'wx')
  try { await file.writeFile(owner) } finally { await file.close() }
} else if (mode === 'release') {
  if (await readFile(path, 'utf8') !== owner) throw new Error('Deployment lock belongs to another operation')
  await unlink(path)
} else throw new Error('Use acquire|release with an owner token')
