import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { COMING_SOON_SCREENS, ComingSoon, GITHUB_URL } from '../src/ui/ComingSoon'
import { detectCapabilities } from '../src/platform/capabilities'

/**
 * Web-specific. The brief was explicit: the native-only features get a "Coming
 * soon" wall pointing at the Android app, not a broken imitation. These check
 * the wall is actually there, says why, and links somewhere useful.
 */
describe('Coming Soon screens', () => {
  it.each(Object.entries(COMING_SOON_SCREENS))('renders the %s wall', (_key, screenProps) => {
    render(<ComingSoon {...screenProps} />)

    expect(screen.getByRole('heading', { name: screenProps.title })).toBeInTheDocument()
    expect(screen.getByText('Coming soon')).toBeInTheDocument()
    expect(
      screen.getByText('Download the full app on Android to experience full features.'),
    ).toBeInTheDocument()

    // The reason must be stated, not just the absence.
    expect(screen.getByText(screenProps.reason)).toBeInTheDocument()

    const link = screen.getByRole('link', { name: /Android on GitHub/i })
    expect(link).toHaveAttribute('href', GITHUB_URL)
  })

  it('lists what the Android app actually does, so the user knows the trade', () => {
    render(<ComingSoon {...COMING_SOON_SCREENS.recorder!} />)

    const list = screen.getByRole('list')
    expect(within(list).getAllByRole('listitem').length).toBeGreaterThan(3)
    expect(within(list).getByText(/foreground service/i)).toBeInTheDocument()
  })

  it('covers every native-only area, including the recorder', () => {
    expect(Object.keys(COMING_SOON_SCREENS)).toEqual(
      expect.arrayContaining(['recorder', 'microphone', 'enhance', 'organize', 'usage']),
    )
  })
})

describe('capability detection', () => {
  const fakeScope = (overrides: Record<string, unknown>) =>
    ({
      navigator: { userAgent: 'Mozilla/5.0', maxTouchPoints: 0, storage: {} },
      crypto: { subtle: { importKey: () => undefined } },
      ...overrides,
    }) as unknown as typeof globalThis

  it('reports no folder linking when showDirectoryPicker is absent, as on Safari', () => {
    expect(detectCapabilities(fakeScope({})).fileSystemAccess).toBe(false)
  })

  it('reports folder linking on Chromium desktop', () => {
    expect(detectCapabilities(fakeScope({ showDirectoryPicker: () => undefined })).fileSystemAccess).toBe(
      true,
    )
  })

  it('recognises iPhone so the resumable provider is preferred there', () => {
    const iphone = fakeScope({
      navigator: { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)', maxTouchPoints: 5, storage: {} },
    })
    expect(detectCapabilities(iphone).suspendsBackgroundTabs).toBe(true)
  })

  it('recognises iPadOS even though it reports itself as a Mac', () => {
    const ipad = fakeScope({
      navigator: { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', maxTouchPoints: 5, storage: {} },
    })
    expect(detectCapabilities(ipad).suspendsBackgroundTabs).toBe(true)
  })

  it('does not mistake a real Mac for an iPad', () => {
    const mac = fakeScope({
      navigator: { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', maxTouchPoints: 0, storage: {} },
    })
    expect(detectCapabilities(mac).suspendsBackgroundTabs).toBe(false)
  })

  it('detects OPFS, which is what makes the app work on iOS at all', () => {
    const withOpfs = fakeScope({
      navigator: { userAgent: 'Mozilla/5.0', maxTouchPoints: 0, storage: { getDirectory: () => undefined } },
    })
    expect(detectCapabilities(withOpfs).opfs).toBe(true)
    expect(detectCapabilities(fakeScope({})).opfs).toBe(false)
  })
})
