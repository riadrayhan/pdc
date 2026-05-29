import { QrCode, Nfc, Wrench, ArrowRight, AlertTriangle, CheckCircle } from 'lucide-react'
import { Link } from 'react-router-dom'

/**
 * Service Center Re-Provisioning hub (Path C).
 *
 * When a customer / thief factory-resets a device from recovery, the phone
 * lands on the FRP "verify Google account" screen (Path B) — useless to them.
 * They bring it to the service center, where the technician uses one of the
 * methods on this page to re-flash EMI Locker in seconds.
 */
export default function ReProvision() {
  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
          <Wrench className="w-7 h-7 text-orange-600" />
          Service Center Re-Provisioning
        </h1>
        <p className="text-sm text-gray-500 mt-1">
          Use these methods to re-install EMI Locker on a wiped device in &lt; 60 seconds.
        </p>
      </div>

      {/* Decision tree */}
      <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-900">
        <div className="flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 flex-shrink-0 mt-0.5 text-amber-600" />
          <div>
            <p className="font-semibold">Decision tree</p>
            <ol className="list-decimal pl-5 mt-1 space-y-1 text-amber-800">
              <li>
                <strong>FRP locked?</strong> Unlock with the company FRP Google
                account (configured in <code>BuildConfig.DEFAULT_FRP_ACCOUNT</code>), then proceed below.
              </li>
              <li>
                <strong>FRP unlocked, on Welcome screen?</strong> Pick a method below.
              </li>
            </ol>
          </div>
        </div>
      </div>

      {/* Method 1 — NFC bump */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-5">
        <h3 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
          <Nfc className="w-5 h-5 text-blue-600" />
          Method 1 — NFC Bump (fastest, ~30 sec)
        </h3>
        <ol className="mt-3 space-y-2 text-sm text-gray-700 list-decimal pl-5">
          <li>On the technician phone (Android 13 or earlier, NFC enabled), open the EMI Locker app and launch <em>"EMI Re-Provisioning Source"</em>.</li>
          <li>Optional: enter office WiFi SSID and password.</li>
          <li>On the wiped phone, ensure it's on the very first <strong>Welcome</strong> screen and NFC is enabled.</li>
          <li>Tap the two phones back-to-back (NFC antennas).</li>
          <li>The wiped phone shows "Set up your work device" and downloads the APK automatically.</li>
        </ol>
        <div className="mt-3 inline-flex items-center gap-2 text-xs px-2 py-1 bg-blue-100 text-blue-800 rounded">
          <CheckCircle className="w-3 h-3" /> Re-provisioning is automatic; no user input required.
        </div>
      </div>

      {/* Method 2 — QR */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-5">
        <h3 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
          <QrCode className="w-5 h-5 text-purple-600" />
          Method 2 — QR Code (works on every Android version)
        </h3>
        <ol className="mt-3 space-y-2 text-sm text-gray-700 list-decimal pl-5">
          <li>On the wiped phone, tap the <strong>Welcome screen 6 times</strong> rapidly — the QR scanner opens.</li>
          <li>Connect to WiFi when prompted.</li>
          <li>Open the dashboard <Link to="/device-setup" className="text-purple-700 underline">Device Setup</Link> page on any other screen and scan the QR.</li>
          <li>EMI Locker downloads, installs and becomes Device Owner automatically.</li>
        </ol>
        <Link
          to="/device-setup"
          className="mt-3 inline-flex items-center gap-2 px-3 py-2 bg-purple-600 text-white rounded-lg text-sm hover:bg-purple-700"
        >
          Open QR Scanner Page <ArrowRight className="w-4 h-4" />
        </Link>
      </div>

      {/* FRP recovery hint */}
      <div className="bg-gray-50 border border-gray-200 rounded-xl p-4 text-sm text-gray-700">
        <p className="font-semibold mb-1">⚠️ Stuck on FRP "Verify your account" screen?</p>
        <p>
          Sign in with the company-controlled FRP Google account
          (set in <code>build.gradle</code> as <code>DEFAULT_FRP_ACCOUNT</code>). Keep these
          credentials in a sealed envelope — losing them bricks all unsold devices.
        </p>
      </div>
    </div>
  )
}
