import { test, expect } from '@playwright/test'

/**
 * 通话录音混音的浏览器级取证（v2.87 · C-165，收口手册 6.6 的 C-82 边界）。
 *
 * C-82 说的是"双轨直接喂 MediaRecorder，浏览器编进去几条轨不由我们决定"——此前既不能证实也不能证伪，
 * 因为本仓没有任何浏览器级媒体取证手段。这条用例把"证实"变成可重跑的守卫：
 * 用两路**不同频率**的正弦音源当作"本方麦克风"与"远端 AI"，过 `buildRecordingStream` 混音后真录一段
 * webm，再解码回 PCM 按频点做 DFT——两段声音都在文件里才算绿。
 *
 * 判据的鉴别力由同批的第二段给出：只送一路音时，另一个频点的幅度必须塌下去。
 * 不连后端、不登录，只需要 vite dev 在跑（跑法见 docs/DEVELOPMENT.md §9）。
 */

const LOW_HZ = 440
const HIGH_HZ = 2400
const DISTORT_HZ = 9000
const RECORD_MS = 1200

type Probe = {
  mixed: boolean
  reason: string
  durationSec: number
  rms: number
  low: number
  high: number
  far: number
}

type Result = { mixed: Probe; single: Probe; trackCount: number }

test.describe('录音混音取证（media 项目，不依赖后端）', () => {
  test('双频音源经混音录制后，两段声音确实都在回放文件里', async ({ page }) => {
    test.setTimeout(60_000)
    await page.goto('/')

    const result = await page.evaluate(async (cfg) => {
      const { buildRecordingStream } = await import('/src/utils/recordingMix.ts')
      const ctx = new AudioContext()
      if (ctx.state !== 'running') await ctx.resume()

      // 每路音源单独经一个 MediaStreamDestination 变成真音轨，模拟"麦克风轨 + 远端轨"
      const toneTrack = (freq: number) => {
        const dest = ctx.createMediaStreamDestination()
        const osc = ctx.createOscillator()
        const gain = ctx.createGain()
        osc.frequency.value = freq
        gain.gain.value = 0.4
        osc.connect(gain)
        gain.connect(dest)
        osc.start()
        return dest.stream.getAudioTracks()[0]
      }

      const record = (stream: MediaStream) => new Promise<Blob>((resolve) => {
        const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
          ? 'audio/webm;codecs=opus'
          : 'audio/webm'
        const rec = new MediaRecorder(stream, { mimeType: mime })
        const chunks: Blob[] = []
        rec.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data) }
        rec.onstop = () => resolve(new Blob(chunks, { type: rec.mimeType }))
        rec.start()
        setTimeout(() => rec.stop(), cfg.recordMs)
      })

      // 按 bin 对齐的频点取幅度：窗口矩形 + 整周期 ⇒ 泄漏极小，判据才不会"时绿时不绿"
      const magAt = (data: Float32Array, bin: number) => {
        const w = (2 * Math.PI * bin) / data.length
        let re = 0
        let im = 0
        for (let i = 0; i < data.length; i++) {
          re += data[i] * Math.cos(w * i)
          im -= data[i] * Math.sin(w * i)
        }
        return (2 * Math.hypot(re, im)) / data.length
      }

      const probe = async (blob: Blob, mixed: boolean, reason: string): Promise<Probe> => {
        const buf = await ctx.decodeAudioData(await blob.arrayBuffer())
        const ch = buf.getChannelData(0)
        const binHz = buf.sampleRate / ch.length
        const bin = (hz: number) => Math.max(1, Math.round(hz / binHz))
        let sum = 0
        for (let i = 0; i < ch.length; i++) sum += ch[i] * ch[i]
        return {
          mixed,
          reason,
          durationSec: buf.duration,
          rms: Math.sqrt(sum / ch.length),
          low: magAt(ch, bin(cfg.lowHz)),
          high: magAt(ch, bin(cfg.highHz)),
          far: magAt(ch, bin(cfg.farHz)),
        }
      }

      const factory = {
        pack: (tracks: MediaStreamTrack[]) => new MediaStream(tracks),
        wrap: (track: MediaStreamTrack) => new MediaStream([track]),
      }
      const low = toneTrack(cfg.lowHz)
      const high = toneTrack(cfg.highHz)

      const both = buildRecordingStream([low, high], ctx, factory)
      const mixedProbe = await probe(await record(both.stream), both.mixed, both.reason)

      // 反向锚点：只有一路音时，另一个频点必须没有能量——否则上面的"两路都在"是恒真的
      const one = buildRecordingStream([low], ctx, factory)
      const singleProbe = await probe(await record(one.stream), one.mixed, one.reason)

      await ctx.close()
      return { mixed: mixedProbe, single: singleProbe, trackCount: both.stream.getAudioTracks().length }
    }, { lowHz: LOW_HZ, highHz: HIGH_HZ, farHz: DISTORT_HZ, recordMs: RECORD_MS })

    // 读数随每一次运行带出：手册登记的取证数值要能被同一命令复现，而不是抄一段快照
    console.log(
      `混音取证 rms=${result.mixed.rms.toFixed(4)} `
      + `low=${result.mixed.low.toFixed(4)} high=${result.mixed.high.toFixed(4)} far=${result.mixed.far.toFixed(4)} `
      + `| 单轨对照 low=${result.single.low.toFixed(4)} high=${result.single.high.toFixed(4)} `
      + `比值=${(result.single.high / result.single.low).toFixed(4)}`,
    )

    expect(result.trackCount, '混音后应只剩一条合成轨（双轨直送浏览器的不确定性由此消除）').toBe(1)
    expect(result.mixed.mixed, `混音分流应走 mixed 分支，实际 ${result.mixed.reason}`).toBe(true)
    expect(result.mixed.rms, '回放文件不是静音').toBeGreaterThan(0.01)
    expect(result.mixed.low, '低频音（本方麦克风）在文件里').toBeGreaterThan(0.05)
    expect(result.mixed.high, '高频音（远端 AI）也在文件里').toBeGreaterThan(0.05)
    expect(result.mixed.low, '低频峰要显著高于远端频点，否则是在读噪声底').toBeGreaterThan(result.mixed.far * 5)
    expect(result.mixed.high, '高频峰同理').toBeGreaterThan(result.mixed.far * 5)

    expect(result.single.mixed, '单轨不混音（分流按原因回退）').toBe(false)
    expect(result.single.reason).toBe('single-track')
    expect(result.single.high, '反向锚点：只送低频时高频幅度必须塌下去').toBeLessThan(result.single.low * 0.2)
  })
})
