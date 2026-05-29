import { useState } from 'react'
import toast from 'react-hot-toast'
import {
  ShieldCheck,
  Copy,
  Download,
  Wifi,
  AlertTriangle,
  CheckCircle2,
  Terminal,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'

const DEVICE_ADMIN = 'com.riad.rrlkr/.admin.EMIDeviceAdminReceiver'
const ADB_COMMAND = `adb shell dpm set-device-owner ${DEVICE_ADMIN}`
const WIFI_BAT = '/setup_owner_wifi.bat'

function Copyable({ text, label = 'Copy' }) {
  return (
    <button
      type="button"
      onClick={() => {
        navigator.clipboard.writeText(text)
        toast.success('Copied')
      }}
      className="px-3 py-1.5 text-xs border border-gray-300 rounded-md hover:bg-gray-50 inline-flex items-center gap-1.5"
    >
      <Copy className="w-3.5 h-3.5" />
      {label}
    </button>
  )
}

function Step({ n, children }) {
  return (
    <li className="flex items-start gap-2.5">
      <span className="w-6 h-6 rounded-full bg-primary-100 text-primary-700 text-xs font-bold flex items-center justify-center flex-shrink-0 mt-0.5">
        {n}
      </span>
      <div className="flex-1 text-sm text-gray-800">{children}</div>
    </li>
  )
}

export default function DeviceOwnerSetup() {
  const [showWhy, setShowWhy] = useState(false)
  const [showCompare, setShowCompare] = useState(false)
  const [showAlts, setShowAlts] = useState(false)

  return (
    <div className="max-w-3xl mx-auto space-y-5">
      <div>
        <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
          <ShieldCheck className="w-7 h-7 text-primary-600" />
          Activate Device Owner
        </h1>
        <p className="text-sm text-gray-500 mt-1">
          Wireless setup over Wi-Fi — no USB cable required.
        </p>
      </div>

      <div className="bg-white rounded-xl border-2 border-primary-300 ring-1 ring-primary-200 p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <Wifi className="w-5 h-5 text-primary-600" />
          <h2 className="font-semibold text-gray-900">Wireless Setup (Recommended)</h2>
          <span className="ml-auto bg-primary-600 text-white text-[10px] font-bold uppercase px-2 py-0.5 rounded">
            No USB
          </span>
        </div>

        <div className="mb-5">
          <h3 className="text-xs font-bold uppercase tracking-wide text-gray-500 mb-2">
            On the phone (one-time)
          </h3>
          <ol className="space-y-2">
            <Step n={1}>
              Settings → About phone → tap <strong>Build number</strong> 7 times to unlock Developer options.
            </Step>
            <Step n={2}>
              Settings → Developer options → turn on <strong>Wireless debugging</strong>.
            </Step>
            <Step n={3}>
              Inside Wireless debugging, tap <strong>“Pair device with pairing code”</strong>.
              The phone shows an <em>IP : PORT</em> and a 6-digit code — keep it open.
            </Step>
            <Step n={4}>
              Settings → Accounts → <strong>remove all Google / Samsung accounts</strong>
              {' '}(data syncs back after re-adding).
            </Step>
          </ol>
        </div>

        <div>
          <h3 className="text-xs font-bold uppercase tracking-wide text-gray-500 mb-2">
            On the PC (one click)
          </h3>
          <ol className="space-y-2">
            <Step n={1}>
              Download and run the launcher (auto-fetches the Python helpers):
              <div className="mt-2">
                <a
                  href={WIFI_BAT}
                  className="px-3 py-1.5 text-xs bg-primary-600 text-white rounded-md hover:bg-primary-700 inline-flex items-center gap-1.5"
                >
                  <Download className="w-3.5 h-3.5" />
                  setup_owner_wifi.bat
                </a>
              </div>
            </Step>
            <Step n={2}>
              When prompted, enter the <strong>IP</strong>, <strong>port</strong>, and
              {' '}<strong>6-digit code</strong> shown on the phone.
            </Step>
            <Step n={3}>
              The script pairs over Wi-Fi → installs the APK → runs{' '}
              <code className="bg-gray-100 px-1 rounded text-[11px]">dpm set-device-owner</code> →
              verifies via <code className="bg-gray-100 px-1 rounded text-[11px]">dumpsys</code> →
              launches the enrollment screen on the phone.
            </Step>
          </ol>
        </div>

        <div className="mt-4 pt-4 border-t border-gray-100 text-xs text-gray-600">
          <p className="mb-2"><strong>Manual fallback</strong> — same command, run it yourself once paired:</p>
          <div className="bg-gray-900 text-green-300 text-xs font-mono p-2 rounded overflow-x-auto">
            {ADB_COMMAND}
          </div>
          <div className="mt-2"><Copyable text={ADB_COMMAND} label="Copy command" /></div>
        </div>
      </div>

      <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
        <div className="flex gap-3">
          <AlertTriangle className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" />
          <div className="text-sm text-amber-900 space-y-2">
            <p>
              <strong>Why a PC is required:</strong> Android refuses to let any app set itself
              as Device Owner once Setup Wizard has finished AND a Google / Samsung account
              is signed in — the OS throws{' '}
              <code className="bg-amber-100 px-1 rounded text-[11px]">IllegalStateException</code>.
              The wireless ADB flow above runs the command from <em>outside</em> the app,
              which the OS allows. There is no in-app bypass on a non-rooted phone — every MDM
              vendor uses the same trick.
            </p>
            <p>
              <strong>If the customer refuses:</strong> the in-app <em>Device Admin</em>
              activation (one tap, no PC) still covers Lock, Unlock, Wipe, Password reset and
              Camera disable. Device Owner only adds factory-reset block, kiosk, silent install,
              and hide-from-launcher.
            </p>
          </div>
        </div>
      </div>

      <details
        className="bg-white border border-gray-200 rounded-xl"
        open={showAlts}
        onToggle={(e) => setShowAlts(e.target.open)}
      >
        <summary className="cursor-pointer px-5 py-3 text-sm font-medium text-gray-900 flex items-center gap-2">
          {showAlts ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          Other paths (factory-reset phone, bulk distribution)
        </summary>
        <div className="px-5 pb-5 space-y-4 text-sm text-gray-700">
          <div>
            <h4 className="font-semibold text-gray-900 mb-1">Factory-reset / brand-new phone</h4>
            <p className="text-gray-600 text-xs mb-2">
              No account is signed in yet, so the wireless flow is not needed.
            </p>
            <ol className="space-y-1 list-decimal list-inside text-sm">
              <li>Power on. On the <em>Welcome / Hi there</em> screen, tap the screen 6 times quickly.</li>
              <li>QR scanner opens — scan the provisioning QR from the dashboard.</li>
              <li>The phone auto-downloads the app, installs as Device Owner, and finishes setup.</li>
            </ol>
          </div>
          <div>
            <h4 className="font-semibold text-gray-900 mb-1">Bulk (50+ phones)</h4>
            <p className="text-gray-600 text-xs mb-2">Zero-Touch (Google) or Knox Mobile Enrollment (Samsung).</p>
            <p>
              Register IMEIs at{' '}
              <a className="text-primary-600 hover:underline" target="_blank" rel="noreferrer"
                 href="https://partner.android.com/zerotouch">partner.android.com/zerotouch</a>{' '}
              or{' '}
              <a className="text-primary-600 hover:underline" target="_blank" rel="noreferrer"
                 href="https://samsungknox.com/kme">samsungknox.com/kme</a>{' '}
              and point them to the AMAPI endpoint already wired in the backend. Phones
              provision themselves on first boot.
            </p>
          </div>
        </div>
      </details>

      <details
        className="bg-white border border-gray-200 rounded-xl"
        open={showCompare}
        onToggle={(e) => setShowCompare(e.target.open)}
      >
        <summary className="cursor-pointer px-5 py-3 text-sm font-medium text-gray-900 flex items-center gap-2">
          {showCompare ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          What changes after Device Owner is set?
        </summary>
        <div className="px-5 pb-5">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-gray-500 uppercase border-b">
                  <th className="py-2 pr-4">Capability</th>
                  <th className="py-2 px-4 text-center">Device Admin</th>
                  <th className="py-2 px-4 text-center">Device Owner</th>
                </tr>
              </thead>
              <tbody className="text-gray-700">
                {[
                  ['Lock screen on EMI default', true, true],
                  ['Force password / PIN', true, true],
                  ['Remote wipe on default', true, true],
                  ['Disable camera', true, true],
                  ['Full-screen lock overlay', true, true],
                  ['Receive FCM commands', true, true],
                  ['Silent app updates', false, true],
                  ['Prevent uninstall', 'partial', true],
                  ['Block factory reset (FRP)', false, true],
                  ['Kiosk / lock-task mode', false, true],
                  ['Hide app from launcher', false, true],
                  ['Block USB / file transfer', false, true],
                ].map(([cap, da, doo], i) => (
                  <tr key={i} className="border-b last:border-0">
                    <td className="py-2 pr-4">{cap}</td>
                    <td className="py-2 px-4 text-center">
                      {da === true ? '✅' : da === 'partial' ? '⚠️' : '—'}
                    </td>
                    <td className="py-2 px-4 text-center">{doo ? '✅' : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </details>

      <details
        className="bg-white border border-gray-200 rounded-xl"
        open={showWhy}
        onToggle={(e) => setShowWhy(e.target.open)}
      >
        <summary className="cursor-pointer px-5 py-3 text-sm font-medium text-gray-900 flex items-center gap-2">
          {showWhy ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
          Why is there no &quot;auto&quot; option inside the app?
        </summary>
        <div className="px-5 pb-5 text-sm text-gray-600 space-y-2">
          <p>
            Inside Android&apos;s{' '}
            <code className="bg-gray-100 px-1 rounded text-[11px]">DevicePolicyManagerService.setDeviceOwner()</code>:
          </p>
          <pre className="bg-gray-900 text-gray-100 text-xs p-3 rounded overflow-x-auto">
{`if (hasUserSetupCompleted() && accounts.length > 0) {
    throw new IllegalStateException(
        "Not allowed to set the device owner because " +
        "there are already some accounts on the device");
}`}
          </pre>
          <p className="flex items-start gap-1.5 text-green-700">
            <CheckCircle2 className="w-4 h-4 flex-shrink-0 mt-0.5" />
            <span>The wireless ADB flow above runs <code>dpm</code> from outside the app, which the OS allows.</span>
          </p>
          <p className="flex items-start gap-1.5 text-amber-700">
            <Terminal className="w-4 h-4 flex-shrink-0 mt-0.5" />
            <span>Anything that claims &quot;one tap, no PC, no reset&quot; on a stock phone with accounts is either lying or running on a rooted ROM.</span>
          </p>
        </div>
      </details>
    </div>
  )
}
