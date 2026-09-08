export const GITHUB_URL = 'https://github.com/andyl2020/debrief-app'

/**
 * The honest wall.
 *
 * Some of Debrief's best features are not "not built yet" - they are things a
 * browser fundamentally cannot do. A foreground microphone service that
 * survives the screen turning off, wake locks, part rollover at 8 MiB
 * boundaries, external-microphone routing: none of these have a web API.
 *
 * Rather than shipping a degraded imitation, each one gets a screen that says
 * what it does, why it is Android-only, and where to get the real app.
 */

export interface ComingSoonProps {
  title: string
  /** Plain-language reason this cannot exist in a browser. */
  reason: string
  /** What the Android app actually does, so the user knows what they're missing. */
  androidBehaviour: string[]
}

export function ComingSoon({ title, reason, androidBehaviour }: ComingSoonProps) {
  return (
    <section className="coming-soon" aria-labelledby="coming-soon-title">
      <p className="coming-soon__eyebrow">Coming soon</p>
      <h2 id="coming-soon-title">{title}</h2>
      <p className="coming-soon__lede">
        Download the full app on Android to experience full features.
      </p>

      <div className="card">
        <h3>Why this isn’t in the web app</h3>
        <p>{reason}</p>
      </div>

      <div className="card">
        <h3>What the Android app does</h3>
        <ul>
          {androidBehaviour.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      </div>

      <a className="button button--primary" href={GITHUB_URL} target="_blank" rel="noreferrer">
        Get Debrief for Android on GitHub
      </a>
    </section>
  )
}

/** The five native-only areas, described once so every entry point agrees. */
export const COMING_SOON_SCREENS: Record<string, ComingSoonProps> = {
  recorder: {
    title: 'Recorder',
    reason:
      'Recording for hours needs a foreground service, a wake lock, and the ability to keep capturing with the screen off. A browser tab gets suspended instead, so a web recorder would quietly lose your audio — which is worse than not offering one.',
    androidBehaviour: [
      'Offline capture at 48 kHz mono AAC into a linked phone folder',
      'Live level meter, timer, pause and resume, hold-to-discard',
      'Keeps recording with the screen off, via a microphone foreground service',
      'Automatic pause and resume around phone calls',
      'Rolls into protected ~8 MiB parts and rejoins them losslessly',
      'Storage safeguards, and recovery of interrupted sessions on next launch',
    ],
  },
  microphone: {
    title: 'External microphone routing',
    reason:
      'The web has no API to choose and monitor a specific hardware input mid-capture the way Android’s audio device routing does.',
    androidBehaviour: [
      'Detects USB, wired, Bluetooth, line, dock and bus inputs',
      'Switches to an external microphone live, without restarting the recorder',
      'Falls back to the built-in microphone on disconnect',
    ],
  },
  enhance: {
    title: 'AI Enhance',
    reason:
      'Enhance re-listens to short extracted clips of your audio. Clip extraction needs native media tooling, and the repair-run pipeline is tied to it.',
    androidBehaviour: [
      'Detects low-confidence rough spots in the transcript',
      'Conservative Gemini text repair with a reviewable diff',
      'Optional short-clip audio re-listen — never the whole recording',
      'Versioned repair runs, a Cleaned view, and accept or revert',
    ],
  },
  organize: {
    title: 'Organize Recording',
    reason:
      'The AI pass renames the physical file on disk and offers an undo. That needs the file-level rename support the linked-folder APIs provide on Android.',
    androidBehaviour: [
      'Summaries and speaker-name suggestions from the transcript',
      'Intelligent physical file rename, with undo',
      'Gemini, OpenAI-compatible, or Claude as the provider',
      'Per-recording privacy skip so a recording is never sent',
    ],
  },
  usage: {
    title: 'Provider usage and spend',
    reason:
      'Reading your provider balance needs API scopes that would have to be exercised from a server. This app has no server — your key never leaves your browser except to transcribe.',
    androidBehaviour: [
      'Per-key local usage tracking',
      'Deepgram provider usage, spend and balance when the key has read scopes',
    ],
  },
}
