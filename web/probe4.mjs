import { chromium } from 'playwright'
import fs from 'node:fs'
const tmp = '/home/vargj/.claude/jobs/19d3dad2/tmp'
fs.writeFileSync(`${tmp}/Interview.m4a`, Buffer.from('fake-audio'))
const browser = await chromium.launch({ executablePath: '/home/vargj/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome' })
const page = await browser.newPage()
page.on('pageerror', e => console.log('[PAGEERROR]', e.message))
await page.goto('http://localhost:5180/'); await page.waitForTimeout(600)
await page.getByRole('button', { name: 'Use browser storage' }).click(); await page.waitForTimeout(500)

// 1. Does clicking "Add audio" actually open a file chooser?
let chooserOpened = false
page.on('filechooser', async (fc) => { chooserOpened = true; await fc.setFiles(`${tmp}/Interview.m4a`) })
await page.getByRole('button', { name: 'Add audio' }).click()
await page.waitForTimeout(1500)
console.log('FILE CHOOSER OPENED:', chooserOpened)
console.log('LIBRARY:', (await page.textContent('body')).replace(/\s+/g,' ').slice(0,200))

// 2. What does Transcribe do with nothing selected?
const t = page.getByRole('button', { name: /^Transcribe/ })
console.log('TRANSCRIBE disabled (nothing selected):', await t.isDisabled())
await t.click({ force: true }).catch(e => console.log('click err', e.message.slice(0,60)))
await page.waitForTimeout(800)
console.log('AFTER CLICKING TRANSCRIBE:', (await page.textContent('body')).replace(/\s+/g,' ').slice(0,240))

// 3. Now select the recording and try again (no vault, no key)
await page.locator('input[type=checkbox]').first().check()
await page.waitForTimeout(300)
console.log('TRANSCRIBE disabled (selected):', await t.isDisabled())
await t.click(); await page.waitForTimeout(1200)
console.log('AFTER TRANSCRIBE WITH SELECTION:', (await page.textContent('body')).replace(/\s+/g,' ').slice(0,300))
await browser.close()
