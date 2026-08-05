import { chromium } from 'playwright'
import fs from 'node:fs'
const tmp = '/home/vargj/.claude/jobs/19d3dad2/tmp'
fs.writeFileSync(`${tmp}/Interview.m4a`, Buffer.from('fake-audio'))
const browser = await chromium.launch({ executablePath: '/home/vargj/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome' })
const page = await browser.newPage()
page.on('pageerror', e => console.log('[PAGEERROR]', e.message))
await page.goto('http://localhost:5181/'); await page.waitForTimeout(600)
await page.getByRole('button', { name: 'Use browser storage' }).click(); await page.waitForTimeout(500)
page.on('filechooser', async (fc) => await fc.setFiles(`${tmp}/Interview.m4a`))
await page.getByRole('button', { name: 'Add audio' }).click(); await page.waitForTimeout(1200)
console.log('--- LIBRARY ---')
console.log((await page.textContent('body')).replace(/\s+/g,' ').slice(0,220))
await page.getByRole('button', { name: 'Transcribe', exact: true }).click(); await page.waitForTimeout(900)
console.log('--- AFTER CLICKING PER-CARD TRANSCRIBE ---')
console.log((await page.textContent('body')).replace(/\s+/g,' ').slice(0,280))
await browser.close()
