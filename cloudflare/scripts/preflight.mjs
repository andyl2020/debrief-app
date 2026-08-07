#!/usr/bin/env node
/**
 * Checks the deployment config before `wrangler deploy` touches the API.
 *
 * The failure this exists to prevent: `wrangler.jsonc` ships with the upstream
 * author's D1 database id and workers.dev hostname. Deploying with those gives
 * a raw Cloudflare API error about a database you have never heard of, several
 * steps into the process, which is a miserable way to learn you skipped a setup
 * step. Better to say so up front, in words that name the fix.
 */

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const configPath = resolve(root, 'wrangler.jsonc')

/**
 * Values that must not be deployed as-is: the placeholders this file ships with,
 * plus the upstream author's real ids, which earlier revisions carried.
 */
const PLACEHOLDER_DATABASE_IDS = new Set([
  'PUT-YOUR-D1-DATABASE-ID-HERE',
  '24068807-199a-4d36-bdac-2a2748728004',
])
const PLACEHOLDER_BASE_URLS = new Set([
  'https://debrief-share.YOUR-SUBDOMAIN.workers.dev',
  'https://debrief-share.debrief-share-service.workers.dev',
])

const problems = []
const warnings = []

const config = parseJsonc(readFileSync(configPath, 'utf8'))

const database = config.d1_databases?.[0]
if (!database?.database_id || PLACEHOLDER_DATABASE_IDS.has(database.database_id)) {
  problems.push(
    `d1_databases[0].database_id is still a placeholder.\n` +
      `    Run:  npx wrangler d1 create debrief-share\n` +
      `    then put the id it prints into wrangler.jsonc.`,
  )
}

if (!config.vars?.PUBLIC_BASE_URL || PLACEHOLDER_BASE_URLS.has(config.vars.PUBLIC_BASE_URL)) {
  problems.push(
    `vars.PUBLIC_BASE_URL is not your deployment.\n` +
      `    Share links would be generated with a placeholder or somebody else's hostname.\n` +
      `    Set it to your own Worker or custom-domain origin.`,
  )
}

const origins = String(config.vars?.ALLOWED_ORIGINS ?? '')
  .split(',')
  .map((entry) => entry.trim())
  .filter(Boolean)

if (origins.length === 0) {
  problems.push(
    `vars.ALLOWED_ORIGINS is empty, so the web app cannot call the owner API at all.\n` +
      `    Set it to the origin the web app is served from.`,
  )
} else if (origins.includes('*')) {
  warnings.push(
    `ALLOWED_ORIGINS contains "*", so any website can call your owner API with a stolen token.\n` +
      `    List your real origins instead.`,
  )
} else if (origins.every((origin) => origin.startsWith('http://localhost'))) {
  warnings.push(
    `ALLOWED_ORIGINS only lists localhost. Add your deployed web-app origin before using this from a phone.`,
  )
}

report()

function report() {
  if (warnings.length > 0) {
    console.warn('\nDeploy warnings:\n')
    for (const warning of warnings) console.warn(`  ! ${warning}\n`)
  }
  if (problems.length > 0) {
    console.error('\nThis deployment is not configured yet:\n')
    for (const problem of problems) console.error(`  x ${problem}\n`)
    console.error('See web/CLOUD-SETUP.md for the full walkthrough.\n')
    console.error('Also make sure R2 is enabled on your account — it is a one-time')
    console.error('click in the Cloudflare dashboard and cannot be done from the CLI.\n')
    process.exit(1)
  }
  console.log('Deploy preflight passed.')
}

/** Minimal JSONC reader: wrangler.jsonc carries // comments and trailing commas. */
function parseJsonc(text) {
  const withoutComments = text
    .replace(/("(?:\\.|[^"\\])*")|\/\*[\s\S]*?\*\/|\/\/[^\n\r]*/g, (match, stringLiteral) =>
      stringLiteral ? stringLiteral : '',
    )
    .replace(/,(\s*[}\]])/g, '$1')
  return JSON.parse(withoutComments)
}
