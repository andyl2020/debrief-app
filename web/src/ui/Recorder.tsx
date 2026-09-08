import { useEffect, useRef, useState } from 'react'
import { formatTimestamp } from '../core/format'
import type { AppApi } from '../state/useApp'

type RecorderState = 'idle' | 'recording' | 'paused' | 'saving'

/** Best-effort browser recorder with frequent checkpoints and explicit platform limits. */
export function Recorder({ app, onSaved }: { app: AppApi; onSaved: (id: string) => void }) {
  const [state, setState] = useState<RecorderState>('idle')
  const [elapsedMs, setElapsedMs] = useState(0)
  const [name, setName] = useState(defaultName)
  const [inputName, setInputName] = useState('Default microphone')
  const recorder = useRef<MediaRecorder | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const chunks = useRef<Blob[]>([])
  const startedAt = useRef(0)
  const pausedAt = useRef(0)
  const pausedTotal = useRef(0)
  const wakeLock = useRef<{ release: () => Promise<void> } | null>(null)
  const audioContext = useRef<AudioContext | null>(null)
  const inputNode = useRef<MediaStreamAudioSourceNode | null>(null)
  const destination = useRef<MediaStreamAudioDestinationNode | null>(null)
  const deviceListener = useRef<(() => void) | null>(null)

  useEffect(() => {
    if (state !== 'recording') return
    const timer = window.setInterval(() => {
      setElapsedMs(Date.now() - startedAt.current - pausedTotal.current)
    }, 250)
    return () => window.clearInterval(timer)
  }, [state])

  useEffect(() => () => {
    stream.current?.getTracks().forEach((track) => track.stop())
    void audioContext.current?.close()
    if (deviceListener.current) navigator.mediaDevices?.removeEventListener('devicechange', deviceListener.current)
    void wakeLock.current?.release()
  }, [])

  const start = async () => {
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      app.actions.notify('This browser does not support microphone recording.')
      return
    }
    try {
      const media = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
      })
      const context = new AudioContext()
      const output = context.createMediaStreamDestination()
      const source = context.createMediaStreamSource(media)
      source.connect(output)
      const mimeType = preferredMimeType()
      const next = new MediaRecorder(output.stream, mimeType ? { mimeType } : undefined)
      chunks.current = []
      next.ondataavailable = (event) => { if (event.data.size > 0) chunks.current.push(event.data) }
      next.onerror = () => app.actions.notify('The browser reported a recording error. Stop and save what was captured.')
      const track = media.getAudioTracks()[0]
      setInputName(track?.label || 'Default microphone')
      track?.addEventListener('mute', () => {
        if (next.state === 'recording') { next.pause(); pausedAt.current = Date.now(); setState('paused') }
        app.actions.notify('Microphone interrupted. Recording is paused and will resume when the input returns.')
      })
      track?.addEventListener('unmute', () => {
        if (next.state === 'paused') { pausedTotal.current += Date.now() - pausedAt.current; next.resume(); setState('recording') }
      })
      recorder.current = next
      stream.current = media
      audioContext.current = context
      inputNode.current = source
      destination.current = output
      const switchToPreferredInput = async () => {
        if (next.state === 'inactive') return
        const devices = (await navigator.mediaDevices.enumerateDevices()).filter((device) => device.kind === 'audioinput')
        const external = devices.find((device) => /usb|headset|headphone|bluetooth|airpod|external|line.?in/i.test(device.label) && !/built.?in|internal/i.test(device.label))
        const wanted = external ?? devices.find((device) => device.deviceId === 'default') ?? devices[0]
        const currentId = stream.current?.getAudioTracks()[0]?.getSettings().deviceId
        if (!wanted || wanted.deviceId === currentId || wanted.deviceId === 'default' && !external) return
        try {
          const replacement = await navigator.mediaDevices.getUserMedia({ audio: { deviceId: { exact: wanted.deviceId }, echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
          const replacementNode = context.createMediaStreamSource(replacement)
          replacementNode.connect(output)
          inputNode.current?.disconnect()
          stream.current?.getTracks().forEach((item) => item.stop())
          stream.current = replacement; inputNode.current = replacementNode
          setInputName(replacement.getAudioTracks()[0]?.label || wanted.label || 'Default microphone')
          app.actions.notify(`Microphone switched to ${wanted.label || 'the available input'}.`)
        } catch { app.actions.notify('A microphone changed, but this browser kept the current input.') }
      }
      deviceListener.current = () => { void switchToPreferredInput() }
      navigator.mediaDevices.addEventListener('devicechange', deviceListener.current)
      startedAt.current = Date.now()
      pausedTotal.current = 0
      setElapsedMs(0)
      next.start(5_000) // frequent chunks preserve everything emitted before an interruption
      setState('recording')
      void switchToPreferredInput()
      const locks = navigator as Navigator & { wakeLock?: { request: (type: 'screen') => Promise<{ release: () => Promise<void> }> } }
      wakeLock.current = await locks.wakeLock?.request('screen').catch(() => null) ?? null
    } catch (error) {
      app.actions.notify(error instanceof Error ? error.message : 'Microphone permission was denied.')
    }
  }

  const togglePause = () => {
    const current = recorder.current
    if (!current) return
    if (current.state === 'recording') {
      current.requestData(); current.pause(); pausedAt.current = Date.now(); setState('paused')
    } else if (current.state === 'paused') {
      pausedTotal.current += Date.now() - pausedAt.current; current.resume(); setState('recording')
    }
  }

  const finish = async () => {
    const current = recorder.current
    if (!current || !app.repository) return
    setState('saving')
    const stopped = new Promise<void>((resolve) => current.addEventListener('stop', () => resolve(), { once: true }))
    current.stop(); await stopped
    stream.current?.getTracks().forEach((track) => track.stop())
    if (deviceListener.current) navigator.mediaDevices.removeEventListener('devicechange', deviceListener.current)
    deviceListener.current = null
    await audioContext.current?.close(); audioContext.current = null
    void wakeLock.current?.release(); wakeLock.current = null
    const type = current.mimeType || 'audio/webm'
    const safeName = withExtension(name.trim() || defaultName(), type)
    try {
      const blob = new Blob(chunks.current, { type })
      const file = new File([blob], safeName, { type, lastModified: Date.now() })
      const [source] = await app.repository.adapter.add([file])
      if (!source) throw new Error('The recording could not be saved.')
      const saved = await app.repository.importSource(source)
      await app.actions.refreshRecordings()
      app.actions.notify('Recording saved on this device.')
      setName(defaultName())
      setState('idle'); chunks.current = []
      onSaved(saved.id)
    } catch (error) {
      setState('idle')
      app.actions.notify(error instanceof Error ? error.message : 'Could not save the recording.')
    }
  }

  const discard = () => {
    if (!confirm('Delete this unsaved recording?')) return
    recorder.current?.stop()
    stream.current?.getTracks().forEach((track) => track.stop())
    if (deviceListener.current) navigator.mediaDevices.removeEventListener('devicechange', deviceListener.current)
    void audioContext.current?.close(); audioContext.current = null
    chunks.current = []; setState('idle'); setElapsedMs(0)
  }

  return (
    <section className="recorder" aria-labelledby="recorder-title">
      <div className="card recorder__card">
        <p className="recorder__eyebrow">{state === 'recording' ? 'Recording' : state === 'paused' ? 'Paused' : 'Offline recorder'}</p>
        <h2 id="recorder-title">{formatTimestamp(elapsedMs)}</h2>
        <label>Recording name<input value={name} onChange={(event) => setName(event.target.value)} disabled={state === 'saving'} /></label>
        <p className="muted">Microphone: {inputName}</p>
        <div className="recorder__controls">
          {state === 'idle' ? (
            <button className="record-button" type="button" onClick={() => void start()} aria-label="Start recording" />
          ) : state === 'saving' ? <span>Saving safely…</span> : (
            <>
              <button className="button" type="button" onClick={togglePause}>{state === 'paused' ? 'Resume' : 'Pause'}</button>
              <button className="button button--primary" type="button" onClick={() => void finish()}>Stop & save</button>
              <button className="button button--quiet" type="button" onClick={discard}>Discard</button>
            </>
          )}
        </div>
      </div>
      <p className="notice notice--warn">Keep this app visible while recording on iPhone. iOS can suspend any web app after the screen locks or another app takes the microphone; no website can override that OS rule. Five-second capture chunks reduce loss if the browser interrupts the session.</p>
    </section>
  )
}

function preferredMimeType(): string | undefined {
  return ['audio/mp4;codecs=mp4a.40.2', 'audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus'].find((type) => MediaRecorder.isTypeSupported(type))
}

function defaultName(): string {
  return `Debrief ${new Date().toISOString().replace(/[:T]/g, '-').slice(0, 16)}`
}

function withExtension(name: string, mime: string): string {
  if (/\.[a-z0-9]{2,5}$/i.test(name)) return name
  return `${name}.${mime.includes('mp4') ? 'm4a' : mime.includes('ogg') ? 'ogg' : 'webm'}`
}
