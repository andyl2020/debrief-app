import { useState } from 'react'
import { useApp } from '../state/useApp'
import { useCloud } from '../state/useCloud'
import { COMING_SOON_SCREENS, ComingSoon } from './ComingSoon'
import { Library } from './Library'
import { Review } from './Review'
import { SettingsScreen } from './Settings'
import { StorageSetup } from './StorageSetup'
import { Messages } from './Messages'

type Tab = 'library' | 'record' | 'settings'

export function App() {
  const app = useApp()
  const cloud = useCloud(app.repository, app.actions.notify)
  const [tab, setTab] = useState<Tab>('library')
  const [reviewing, setReviewing] = useState<string | null>(null)

  if (!app.state.ready) {
    return (
      <main className="shell shell--centered">
        <p className="muted">Opening your local library…</p>
      </main>
    )
  }

  const showSetup = !app.state.storage || app.state.needsRelink || app.state.needsChoice

  return (
    <div className="shell">
      <header className="topbar">
        <div className="topbar__brand">
          <span className="topbar__mark" aria-hidden="true" />
          <div>
            <h1>Debrief</h1>
            <p className="topbar__subtitle">
              {app.state.storage ? app.state.storage.label : 'No storage linked yet'}
            </p>
          </div>
        </div>
        <nav className="tabs" aria-label="Sections">
          <TabButton current={tab} value="library" onSelect={setTab} label="Library" />
          <TabButton current={tab} value="record" onSelect={setTab} label="Record" />
          <TabButton current={tab} value="settings" onSelect={setTab} label="Settings" />
        </nav>
      </header>

      <Messages messages={app.state.messages} onDismiss={app.actions.dismissMessage} />

      <main className="content">
        {showSetup ? (
          <StorageSetup app={app} />
        ) : reviewing ? (
          <Review app={app} cloud={cloud} recordingId={reviewing} onClose={() => setReviewing(null)} />
        ) : tab === 'library' ? (
          <Library app={app} cloud={cloud} onOpen={setReviewing} />
        ) : tab === 'record' ? (
          <ComingSoon {...COMING_SOON_SCREENS.recorder!} />
        ) : (
          <SettingsScreen app={app} cloud={cloud} />
        )}
      </main>
    </div>
  )
}

function TabButton({
  current,
  value,
  onSelect,
  label,
}: {
  current: Tab
  value: Tab
  onSelect: (tab: Tab) => void
  label: string
}) {
  return (
    <button
      type="button"
      className={`tab ${current === value ? 'tab--active' : ''}`}
      aria-current={current === value ? 'page' : undefined}
      onClick={() => onSelect(value)}
    >
      {label}
    </button>
  )
}
