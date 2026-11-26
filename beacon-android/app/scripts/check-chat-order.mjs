#!/usr/bin/env node
// Verifies the chat-order lane: a retained reply carries its turn's timestamp and keeps its own place, and a legacy row with no time is no longer
// sorted below every newer message. Reads the actual sources, test results and artifacts; prints one success-only marker.
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const app = path.join(root, 'beacon-android/app')
const result = (name, ok, detail) => {
  if (!ok) { console.error('FAIL ' + name + ': ' + detail); process.exit(1) }
  console.log('ok ' + name)
}
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
/** Evidence a PowerShell pipeline wrote is UTF-16; read it as the text it is either way. */
const readText = (p) => {
  const bytes = fs.readFileSync(p)
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return bytes.subarray(2).toString('utf16le')
  if (bytes[0] === 0xfe && bytes[1] === 0xff) {
    const swapped = Buffer.from(bytes.subarray(2))
    swapped.swap16()
    return swapped.toString('utf16le')
  }
  return bytes.toString('utf8')
}
const mode = process.argv[2] || ''

const vm = 'beacon-android/app/src/main/java/dev/hermes/android/BeaconViewModel.kt'
const chat = 'beacon-android/app/src/main/java/dev/hermes/android/ui/ChatScreen.kt'
const test = 'beacon-android/app/src/test/java/dev/hermes/android/KeptReplyTurnTest.kt'

if (!mode || mode === '--source') {
  const vmText = read(vm)
  const chatText = read(chat)
  const testText = read(test)

  // 1. The retained row is written with a timestamp, and the same id keeps the one it had.
  const retained = vmText.slice(vmText.indexOf('internal fun retainUnconfirmedReply'))
  const write = retained.slice(0, retained.indexOf('return chat.copy'))
  result('retained row writes a timestamp', /"timestamp" to \(recordedMessageTime\(kept\[/.test(write), 'retainUnconfirmedReply writes no timestamp')
  result('re-keeping keeps the recorded time', /recordedMessageTime\(kept\[[^\]]+\]?\?\.opt\("timestamp"\)\)\.takeIf \{ it > 0 \}/.test(write), 'no stable reuse of the old time')
  result('a turn boundary is asked before now', /keptTurnTime\(chat, prompt, System\.currentTimeMillis\(\)\)/.test(write), 'retention does not prefer the turn boundary')

  // 2. The turn time is the question row's own record, then the phone's send boundary, then now.
  const turnTime = vmText.slice(vmText.indexOf('internal fun keptTurnTime'))
  const fn = turnTime.slice(0, turnTime.indexOf('\n}'))
  for (const [name, re] of [
    ['turn time reads the question row', /recordedMessageTime\(question\?\.opt\("timestamp"\)\)/],
    ['turn time falls back to the send boundary', /queued\.firstOrNull \{ submission/],
    ['turn time returns now last', /return now/],
  ]) result(name, re.test(fn), 'keptTurnTime is missing ' + name)

  // 3. The retained history row no longer copies a missing timestamp.
  result('history row never writes a missing time',
    /recordedMessageTime\(last\.opt\("timestamp"\)\)\.takeIf \{ it > 0 \} \?:/.test(vmText) && !/"timestamp" to last\.opt\("timestamp"\)/.test(vmText),
    'mergeChatProjection still copies last.opt("timestamp")')

  // 4. Legacy no-time rows no longer sort to Long.MAX_VALUE.
  result('no sort to Long.MAX_VALUE', !/recordedAt\.takeIf \{ at -> at > 0 \} \?: Long\.MAX_VALUE/.test(chatText), 'placeKeptReplies still sorts missing times last')
  const place = chatText.slice(chatText.indexOf('private fun withUntimedKept'))
  result('untimed replies are placed, not sorted away', /Array\(shown\.size \+ 1\)/.test(place) && /keptReplyTime\(shown, at\)/.test(place), 'untimed kept replies have no place of their own')
  result('a timed reply keeps its existing sort', /timed\.sortedBy \{ it\.recordedAt \}/.test(chatText), 'timed replies changed order')

  // 5. The retained bubble draws the time.
  const bubble = chatText.slice(chatText.indexOf('message.displayKind == "unconfirmed_reply"'))
  result('retained bubble draws BubbleMeta', /BubbleMeta\(message\.recordedAt, message\.id, null\)/.test(bubble.slice(0, bubble.indexOf('} else if'))), 'the retained bubble has no time')

  // 6. The tests that decide the red/green pair are the ones demanded.
  for (const name of [
    'aKeptReplyWithNoTimeStaysAboveANewerMessageAndShowsThatTurnsTime',
    'aRetainedReplyIsWrittenWithItsTurnsTime',
    'aRetainedReplyKeepsItsOwnTimeWhenKeptAgain',
    'aSavedReplyRowWithNoTimeKeepsTheTurnBoundaryInsteadOfNone',
  ]) result('test ' + name, testText.includes('fun ' + name + '('), 'missing required test')
  console.log('chat-order source verification passed')
}

if (mode === '--unit') {
  // The focused JUnit run must be green on this source, from Gradle's own XML.
  const dir = path.join(app, 'build/test-results/testDebugUnitTest')
  const xml = fs.readdirSync(dir).filter((f) => f.startsWith('TEST-dev.hermes.android.KeptReplyTurnTest'))
  result('focused test report exists', xml.length === 1, 'expected one KeptReplyTurnTest report, found ' + xml.length)
  const text = fs.readFileSync(path.join(dir, xml[0]), 'utf8')
  const cases = [...text.matchAll(/<testcase name="([^"]+)"/g)].map((m) => m[1])
  const failures = (text.match(/<failure /g) || []).length
  result('focused tests ran', cases.length >= 19, 'only ' + cases.length + ' cases ran')
  result('focused tests pass', failures === 0, failures + ' failing case(s)')
  for (const name of ['aKeptReplyWithNoTimeStaysAboveANewerMessageAndShowsThatTurnsTime', 'aRetainedReplyIsWrittenWithItsTurnsTime'])
    result('ran ' + name, cases.includes(name), 'case absent from the report')
  console.log('chat-order unit verification passed')
}

if (mode === '--suite') {
  const dir = path.join(app, 'build/test-results/testDebugUnitTest')
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.xml'))
  result('suite reports exist', files.length > 300, 'only ' + files.length + ' suite reports')
  let tests = 0, failures = 0, errors = 0
  for (const f of files) {
    const head = fs.readFileSync(path.join(dir, f), 'utf8').match(/<testsuite [^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"/)
    if (!head) continue
    tests += +head[1]; failures += +head[2]; errors += +head[3]
  }
  console.log('suite: ' + tests + ' tests, ' + failures + ' failures, ' + errors + ' errors, ' + files.length + ' reports')
  result('whole suite ran', tests > 4000, 'only ' + tests + ' tests')
  result('whole suite passes', failures === 0 && errors === 0, failures + ' failures, ' + errors + ' errors')
  console.log('chat-order unit suite passed')
}

if (mode === '--device') {
  // The shared runner writes its instrumentation log under the main root's tooling scope; a copy is kept beside this lane's evidence.
  const log = [path.join(root, '.unlazy/uiux-20261031/evidence/device-chat-order.log'),
    path.join(root, '.unlazy/release-124/chat-order/device-chat-order.log')].find((p) => fs.existsSync(p)) || ''
  result('device log exists', fs.existsSync(log), 'missing ' + log)
  const text = readText(log)
  const run = readText(path.join(root, '.unlazy/release-124/chat-order/device-run.txt'))
  result('device run passed', /^DEVICE_TEST_PASS chat-order 1\r?$/m.test(run), 'device run did not pass')
  result('device run used emulator-5578', /EMULATOR_LEASE_ACQUIRED emulator-5578/.test(run), 'the run used another device')
  result('instrumentation finished', /INSTRUMENTATION_CODE: -1/.test(text), 'instrumentation did not finish')
  result('the order test ran', /dev\.hermes\.android\.KeptReplyOrderUiTest/.test(text), 'the order test is absent')
  for (const name of ['keptReplyShowsItsTimeAndStaysAboveTheNewerTurn'])
    result('ran ' + name, new RegExp(name).test(text), 'missing on-device case')
  // The capture is the visual proof: real PNG bytes, and the test recorded what it saw on screen.
  const shots = path.join(root, '.unlazy/release-124/chat-order')
  const pngs = fs.readdirSync(shots).filter((f) => f.startsWith('device-chat-order') && f.endsWith('.png'))
  result('device capture exists', pngs.length > 0, 'no device-chat-order*.png under ' + shots)
  const png = fs.readFileSync(path.join(shots, pngs[0]))
  result('capture is a real PNG', png.length > 20_000 && png.slice(1, 4).toString() === 'PNG', 'capture is not a PNG (' + png.length + ' bytes)')
  const facts = JSON.parse(fs.readFileSync(path.join(shots, 'device-order.json'), 'utf8'))
  result('bubble time is non-blank', typeof facts.time === 'string' && facts.time.trim().length > 0, 'blank time: ' + JSON.stringify(facts.time))
  result('bubble time looks like a clock', /^\d{1,2}:\d{2}(:\d{2})?\s?(AM|PM)?$/i.test(facts.time.trim()) || /^\d{1,2}:\d{2}$/.test(facts.time.trim()), 'time is not a clock: ' + facts.time)
  result('retained reply sits above the newer turn', facts.replyTop < facts.newerTop, 'reply at ' + facts.replyTop + ', newer turn at ' + facts.newerTop)
  console.log('chat-order device verification passed')
}

if (mode === '--red') {
  // The failing half of the pair: the same tests, run against the untouched base. Both the unit report and the device measurements must show the defect.
  const red = JSON.parse(fs.readFileSync(path.join(root, '.unlazy/release-124/chat-order/red.json'), 'utf8'))
  result('base unit run failed', red.unitFailures >= 6, 'only ' + red.unitFailures + ' unit cases failed on the base')
  for (const name of ['aKeptReplyWithNoTimeStaysAboveANewerMessageAndShowsThatTurnsTime', 'aRetainedReplyIsWrittenWithItsTurnsTime',
    'aKeptReplyRebuiltFromASavedRowWithNoTimeStaysWithThatRowNotAtTheBottom', 'aSavedReplyRowWithNoTimeKeepsTheTurnBoundaryInsteadOfNone'])
    result('base fails ' + name, red.unitCases.includes(name), 'case did not fail on the base')
  result('base kept reply timed 00:00 or blank', typeof red.device.time === 'string' && red.device.time.trim().length === 0, 'base drew a time: ' + JSON.stringify(red.device.time))
  result('base kept reply sat below the newer turn', red.device.replyTop > red.device.newerTop,
    'base reply ' + red.device.replyTop + ' vs newer ' + red.device.newerTop)
  const png = fs.readFileSync(path.join(root, '.unlazy/release-124/chat-order', red.device.shot))
  result('base capture exists', png.slice(1, 4).toString() === 'PNG' && png.length > 20_000, 'base capture is not a PNG')
  console.log('chat-order red verification passed')
}

if (mode === '--lint') {
  const xmls = ['debug', 'stable', 'release'].map((variant) => [variant, path.join(app, 'build/reports/lint-results-' + variant + '.xml')])
  for (const [variant, file] of xmls) {
    result('lint ' + variant + ' report exists', fs.existsSync(file), 'missing ' + file)
    const xml = fs.readFileSync(file, 'utf8')
    const errors = (xml.match(/severity="Error"/g) || []).length
    const warnings = (xml.match(/severity="Warning"/g) || []).length
    console.log('lint ' + variant + ': ' + errors + ' errors, ' + warnings + ' warnings')
    result('lint ' + variant + ' has no errors', errors === 0, errors + ' errors in ' + variant)
  }
  console.log('chat-order lint verification passed')
}

if (mode === '--patch') {
  const dir = path.join(root, '.unlazy/release-124')
  const patchFile = path.join(dir, 'chat-order.patch')
  const sumFile = path.join(dir, 'chat-order.patch.sha256')
  result('patch exists', fs.existsSync(patchFile), 'missing chat-order.patch')
  result('sha256 sidecar exists', fs.existsSync(sumFile), 'missing chat-order.patch.sha256')
  const actual = crypto.createHash('sha256').update(fs.readFileSync(patchFile)).digest('hex').toUpperCase()
  const recorded = fs.readFileSync(sumFile, 'utf8').trim().split(/\s+/)[0].toUpperCase()
  result('sha256 matches the sidecar', actual === recorded, 'file ' + actual + ' vs recorded ' + recorded)
  result('patch is not empty', fs.statSync(patchFile).size > 2_000, 'patch is suspiciously small')
  // It must apply to the base the brief names, checked in a scratch worktree so the lane tree is untouched.
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' })
  const tree = git('rev-parse', '4d3e5ed0^{tree}').trim()
  result('base tree is the joined 1.2.4 tree', tree === 'ffaa3d7840800a4e38be8df04bdbf308fa667d2b', 'base tree is ' + tree)
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'chat-order-patch-'))
  try {
    const wt = path.join(scratch, 'tree')
    execFileSync('git', ['worktree', 'add', '--detach', wt, '4d3e5ed0'], { cwd: root, encoding: 'utf8' })
    execFileSync('git', ['apply', '--check', patchFile], { cwd: wt, encoding: 'utf8' })
    execFileSync('git', ['apply', patchFile], { cwd: wt, encoding: 'utf8' })
    execFileSync('git', ['add', '-A'], { cwd: wt, encoding: 'utf8' })
    // The patch's result must equal this lane's own tree, file for file. The scratch tree is compared as staged content; it is never committed, so
    // the repository's own pre-commit policy does not apply to this verification.
    // The files the patch itself carries, read from its own headers rather than from a diff of the staging area.
    const patchFiles = [...fs.readFileSync(patchFile, 'utf8').matchAll(/^\+\+\+ b\/(.+)$/gm)].map((m) => m[1].trim())
    // Every file this lane has touched, tracked or new: git diff alone never names an untracked file.
    const laneFiles = execFileSync('git', ['status', '--porcelain', '--untracked-files=all', '--', 'beacon-android/app/src', 'beacon-android/app/scripts'],
      { cwd: root, encoding: 'utf8' }).split(/\r?\n/).filter((line) => line.length > 3)
      .map((line) => line.slice(3).trim().replace(/^"|"$/g, '')).sort()
    result('patch touches exactly the source this lane changed', patchFiles.slice().sort().join(',') === laneFiles.join(','),
      'patch ' + patchFiles.sort().join(',') + ' vs lane ' + laneFiles.join(','))
    for (const required of ['beacon-android/app/scripts/check-chat-order.mjs',
      'beacon-android/app/src/androidTest/java/dev/hermes/android/KeptReplyOrderUiTest.kt',
      'beacon-android/app/src/main/java/dev/hermes/android/BeaconViewModel.kt',
      'beacon-android/app/src/main/java/dev/hermes/android/ui/ChatScreen.kt',
      'beacon-android/app/src/test/java/dev/hermes/android/KeptReplyTurnTest.kt'])
      result('patch carries ' + required.split('/').pop(), patchFiles.includes(required), 'the patch omits ' + required)
    result('patch leaves build output, logs and evidence out', patchFiles.every((p) => /^beacon-android\/app\/(src|scripts)\//.test(p)), 'patch names ' + patchFiles.join(','))
    // Applied or not, every file the patch names must hold exactly the bytes this lane has.
    for (const file of patchFiles) {
      const applied = fs.readFileSync(path.join(wt, file))
      const lane = fs.readFileSync(path.join(root, file))
      result('applied ' + file, applied.equals(lane), 'the applied file differs from this lane\'s')
    }
  } finally {
    // Windows refuses the long build paths under a temp worktree; dropping the registration and pruning is enough, and it leaves no stale
    // worktree behind for the next run to trip over.
    try { execFileSync('git', ['worktree', 'remove', '--force', '--force', path.join(scratch, 'tree')], { cwd: root, encoding: 'utf8' }) } catch {
      try { execFileSync('git', ['worktree', 'remove', '--force', path.join(scratch, 'tree')], { cwd: root, encoding: 'utf8' }) } catch {}
    }
    try { execFileSync('git', ['worktree', 'prune'], { cwd: root, encoding: 'utf8' }) } catch {}
    try { fs.rmSync(scratch, { recursive: true, force: true }) } catch {}
  }
  console.log('chat-order patch verification passed')
}
