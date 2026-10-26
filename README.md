# DownloadOS

**Download**OS — the MartinB OS APK installer for Android, plus a web page with every MartinB OS app for every device.

- **My apps**: lists every `fisbal` repo whose latest GitHub Release has an `.apk`, shows installed / update available, and installs or updates in one tap (Android 12+: updates of apps it installed happen silently). DownloadOS updates itself the same way.
- **Link**: paste or *Share → DownloadOS* any direct `.apk` link, a `github.com/owner/repo` page, or just `owner/repo`.
- **File**: install an `.apk` already on the phone, or *Open with → DownloadOS*.
- **✦ Ask Claude**: the built-in copilot helps with install and update problems. It uses your own Claude API key, saved in ⚙ Settings on the phone only.

## Downloads

- Android app: https://github.com/fisbal/DownloadOS/releases/latest
- Every app, for your phone, tablet, PC or Mac: https://fisbal.github.io/DownloadOS/

## Builds

Every pull request runs the screen tests (Android app at 5 sizes in English and French, web page at 6 sizes) and builds the APK.
Every push to `main` also publishes a signed APK as release `v1.0.N` and updates the web page.

The release signing key is never in this repo. The workflow reads it from three GitHub secrets:
`DOWNLOADOS_KEYSTORE_B64`, `DOWNLOADOS_KEYSTORE_PASSWORD`, `DOWNLOADOS_KEY_PASSWORD`.
