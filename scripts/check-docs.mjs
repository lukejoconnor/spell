import { readFile, readdir } from 'node:fs/promises'
import { extname, join, relative } from 'node:path'

const roots = ['README.md', 'docs', 'examples']
const marker = /^(<<<<<<<|=======|>>>>>>>)(?: .*)?$/m
const failures = []

async function visit(path) {
  const entries = await readdir(path, { withFileTypes: true })
  for (const entry of entries) {
    const next = join(path, entry.name)
    if (entry.isDirectory()) await visit(next)
    else if (['.md', '.spl'].includes(extname(entry.name))) await check(next)
  }
}

async function check(path) {
  const text = await readFile(path, 'utf8')
  if (marker.test(text)) failures.push(`${relative('.', path)} contains a merge-conflict marker`)
}

for (const root of roots) {
  if (extname(root)) await check(root)
  else await visit(root)
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Documentation source checks passed.')
