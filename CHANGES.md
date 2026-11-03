# CHANGES.md — reproducible patch journal

Purpose: if this repository is deleted, clone the upstream CloudStream `main` branch and hand this
file to an agent at the start. Reapply the sections below in order, then keep appending future
changes here. The upstream base used by this workspace was `2b6607133585056dc66f6761ad3fa567aba2217e`.

Working branch: `arena/f17c62e8-cs-test-1`. Do not merge these cleanup only changes into a
production release; the committed `debug.keystore` is a public, non production debug key.

---

## 1. Repository repair and APK build workflow

**Files:** `.github/workflows/build_apk.yml`, `gradlew`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties`, `app/src/main/java`, `library/src/commonMain`,
`shared/src/commonMain`, plus the restored module Gradle files.

The original clone was missing `gradlew`, the wrapper JAR and most module sources, so GitHub Actions
could not build. Restored the wrapper and module sources from upstream `recloudstream/cloudstream`
at `5024ace2`, then added a hardened `build_apk.yml` that uses JDK 21, Android SDK 36, the committed
debug key and uploads one `CloudStream-debug-apk`.

Successful validation: run `41523213497`, commit `6ee2a5eb`. Do **not** reintroduce
`android-actions/setup-android@v3` or `sdkmanager tools`; that obsolete package broke the first
workflow run.

---

## 2. App updater cleanup, while preserving extensions

**Files deleted or refactored:** `app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt`,
`ApkInstaller.kt`, `ApkUpdater.kt`, `PackageInstaller.kt`, `GitInfo.kt`,
`SettingsUpdatesScreen.kt`, `SettingsUpdates.kt`, update only navigation entries, update only
resources, and update only manifest entries. New shared helpers were added:
`DigestPair.kt`, `PluginFileTransfer.kt`, `SettingsBackup2.kt`, `SettingsBackupScreen.kt`.
Refactored: `AndroidRepositoryManager.kt`, `DevicePreferenceStore.kt`, `MainActivity.kt`,
`SettingsFragment.kt`, `SettingsFragmentScreen.kt`, `mobile_navigation.xml`, `main_settings.xml`,
`AndroidManifest.xml`, `README.md`.

Removed app update checks, GitHub release fetching, APK self update, update notifications, update
preferences and other non extension remote app code delivery paths. The user selected
`remove_updaters_keep_app` and `keep_for_extensions`, so extension repositories, manual plugin
install, automatic plugin updates, background repository refresh, player, downloads, accounts and
normal settings were kept. `DigestPair` / `ApkUpdater.transfer` / `isFileDigest` capability was
extracted before deleting updater code so plugin installation still works.

Successful validation: run `41528025087`, commit `fef2cba33cfbbe75a719dc14f9c79134b52d8a96`.

---

## 3. Fixed debug signing and second UI cleanup

**Files:** `.github/workflows/build_apk.yml`, `debug.keystore`, `.gitignore`,
`app/build.gradle.kts`, `SettingsGeneral.kt`, `SettingsGeneralScreen.kt`, `Globals.kt`,
`DevicePreferenceStore.kt`, `settings_general.xml`, `MainActivity.kt`, `SettingsFragment.kt`,
`SettingsFragmentScreen.kt`, `main_settings.xml`, `app/src/main/res/values*`, four deleted
drawables, and deleted `GitInfo.kt`.

Replaced per runner generated signing keys with one committed public React Native style JKS
(`debug.keystore`, alias `androiddebugkey`, password `android`). Removed `GenerateGitHashTask`,
`generateGitHash`, `BUILD_DATE`, git hash assets, `BuildConfig.BUILD_DATE`, `GitInfo.kt` and the
workflow key generation step; CI now verifies the key with `keytool`.

Cleared field/page names and symbols: `pref_category_links`, `legal_notice_text`, `beneneCount`,
`GeneralPreferences.bananas`, `BuildStamp()`, `app_version_info`, version/hash/date blocks,
`checkGithubConnectivity`, startup GitHub connectivity snackbar, `pref_category_links` items,
`benene` easter egg, legal text and the app version / hash / build date clutter below Extensions.
This followed `github_fetch_scope=ui_only`: provider keys, MALSync mapping, jsdelivr proxy and
extension repository network calls remain. Removed 389 unused string definitions for 11 keys and
four unreferenced drawables; referenced `github`, `discord`, `benene`, `revert`,
`ic_github_logo` and `quick_novel_icon` were kept.

Successful validation: run `41532042162`, commit `5098e741634acc289855b0d35785cb8efe821d16`,
artifact `CloudStream-debug-apk` ID `13181079751`.

---

## 4. Network security configuration for local/testing traffic

**Files:** `app/src/main/res/xml/network_security_config.xml`, `app/src/main/AndroidManifest.xml`.

Added the exact requested config with `cleartextTrafficPermitted="true"`, `system` and `user`
trust anchors with `overridePins="true"` in both `base-config` and `debug-overrides`, plus the
AXMLPrinter2 decompiled config comment. Wired it with
`android:networkSecurityConfig="@xml/network_security_config"` on `<application>`. The manifest
already had `android:usesCleartextTraffic="true"`. This is for local/testing traffic and user
installed CAs only; no unrelated production behavior was changed.

---

## 5. Promotional popup / link / button cleanup

**Files:** `app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt`,
`app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsFragment.kt`,
`app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/PluginDetailsFragment.kt`,
`app/src/main/res/layout/fragment_plugin_details.xml`,
`app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/PluginAdapter.kt`,
deleted `app/src/main/java/com/lagradost/cloudstream3/plugins/VotingApi.kt`, deleted
`app/src/main/res/drawable/ic_baseline_thumb_up_24.xml`, and `app/src/main/res/values*/strings.xml`.

Removed `Activity.addRepositoryDialog()` and both call sites. That was the provider/repository
"support" warning popup seen after a repository/provider test and on deep link/app open; its
cleared fields were `download_all_plugins_from_repo`, `open_downloaded_repo` and the "Open
Repository" / "Dismiss" buttons. The repository add URL dialog itself remains because it is
functional. Also removed the plugin details `githubBtn` repository button, the `upvote` /
`plugin_votes` promotional voting UI, the `VotingApi` countify backend and dead vote code.
Functional dialogs (settings, errors, logs, battery, biometric, safe mode, player controls and
test logs) were kept after auditing the dialog call sites.

---

## 6. Standalone Stremio/Streamio addon bridge — phase 1

**New package:** `app/src/main/java/com/lagradost/cloudstream3/stremio/`
(`StremioModels.kt`, `StremioApi.kt`, `StremioRepository.kt`, `StremioProvider.kt`,
`StremioBridge.kt`).
**New settings UI:** `app/src/main/java/com/lagradost/cloudstream3/ui/settings/StremioAddonsFragment.kt`,
`StremioAddonsScreen.kt`.
**Wiring:** `CloudStreamApp.kt`, `SettingsFragment.kt`, `SettingsFragmentScreen.kt`,
`MainActivity.kt`, `app/src/main/res/layout/main_settings.xml`,
`app/src/main/res/navigation/mobile_navigation.xml`,
`app/src/main/res/values/strings.xml`.

Added a standalone architecture that speaks Stremio's official login/register/getUser and
addonCollectionGet/addonCollectionSet API plus the addon `manifest.json`, `catalog`, `meta` and
`stream` protocol. It intentionally does **not** copy stremio-native's forced update dialog,
telemetry, APK installer or Rust/JNI native server.

Login behavior: ordinary app launch is unchanged; the new **Settings → Stremio Addons** option
shows a login/signup/auth key page only when no Stremio account is selected. After login the
account is shown at the top, `account_selected` is set, the server addon collection is pulled,
official default addons are installed on a fresh account, and the addon management page opens.
Addons can be installed by manifest URL, uninstalled, synced and pushed back to the Stremio
account.

Home/search/meta/stream integration is provided through an internal `MainAPI` provider registered
with `APIHolder.allProviders`. `StremioProvider` maps addon catalogs into CloudStream home rows,
declared `search` catalogs into CloudStream search, addon `meta` into CloudStream
movie/series detail pages with episodes, and addon `stream` responses into CloudStream
`ExtractorLink`s (direct URL, YouTube, magnet, HLS/DASH hints and subtitles). CloudStream's own
UI/UX and player are preserved. CloudStream extension repositories and their normal behavior are
unchanged.

**Still pending for full parity:** library/continue watching sync, configurable addon pages,
native torrent/stream server support, and deeper account settings parity. The dedicated stream
list is implemented in section 9; do not claim the remaining items are finished; append the next
phase here when implemented.

### 6.1 Phase 1 build fixes

**Files:** `app/src/main/res/values/strings.xml`,
`app/src/main/java/com/lagradost/cloudstream3/stremio/StremioBridge.kt`.

The first phase 1 build failed at `:app:mergeDebugResources` because `stremio_intro` contained an
unescaped apostrophe in `CloudStream's`. Replaced it with `CloudStream screens` so AAPT can flatten
the string. Keep future string additions free of unescaped apostrophes or use `\'`.

The next build failed at `:app:compileDebugKotlin` because `AtomicMutableList.withLock` has no
lambda receiver. `StremioBridge.registerProvider()` now references `allProviders` explicitly for
`none` and `add` instead of using an implicit `it` receiver.

---

## 7. How to reapply on a fresh clone

1. Clone upstream `recloudstream/cloudstream` from the same base commit, then restore the missing
   wrapper/module sources as in section 1.
2. Apply sections 2–6 in order. Keep `debug.keystore` unignored and do not regenerate it.
3. Keep extension network/install/update code intact; remove only app updaters, promotional
   surfaces and the named General/Extensions clutter fields.
4. Validate with the `Build APK` workflow on the `arena/**` branch and confirm one unexpired
   `CloudStream-debug-apk` artifact.
5. Append every later change to this file with the cleared field names, a 2–3 line explanation and
   the affected file paths.

---

## 8. Phase 1 validation

**Build:** `e8b6edb22d376eaaca2e67c612c25351db223196`, GitHub Actions run `41587855589`, job
`125757432852`, conclusion `success`, finished `2026-11-03T06:10:15Z`.

**Artifact:** one `CloudStream-debug-apk` for this run, ID `13208388375`, unexpired,
81,333,857 bytes, created `2026-11-03T06:09:50Z`. Earlier validated artifacts from runs
`41523213497`, `41528025087` and `41532042162` may also remain until GitHub expires them; the
current APK is the phase 1 build that includes sections 1–6.1.

**Scope note:** a successful compile does not mean the Stremio bridge has runtime parity. This
build predates section 9; the current build is recorded in section 9.1. The dedicated stream-list
page is now implemented, but runtime login, stream loading and the remaining parity items in
section 6 still need confirmation before calling the Stremio integration complete.

---

## 9. Login fix and dedicated all-streams page

**Files:** `app/src/main/java/com/lagradost/cloudstream3/stremio/StremioApi.kt`,
`StremioModels.kt`, `StremioRepository.kt`,
`app/src/main/java/com/lagradost/cloudstream3/ui/settings/StremioAddonsFragment.kt`,
`app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt`,
`StremioStreamsFragment.kt`,
`app/src/main/java/com/lagradost/cloudstream3/ui/player/StremioStreamGenerator.kt`,
`app/src/main/res/navigation/mobile_navigation.xml`,
`app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt`,
`app/src/main/res/values/strings.xml`.

Login, sign-up and auth-key calls now use minimal official request bodies, tolerate Stremio's
zero/false/empty `error` side-channel, accept `authKey`/`token`/`key`/nested `auth` responses, and
surface the real API or transport message instead of a generic failure. No test account
credentials are stored in the repository.

Stremio result clicks now navigate to `StremioStreamsFragment` before playback. The page calls
`StremioProvider.loadLinks`, shows every distinct stream, and hands only the chosen link and
subtitles to CloudStream's existing player through `StremioStreamGenerator`. Ordinary CloudStream
extension results are unchanged.

### 9.1 Phase 2 build validation

**File:** `app/src/main/java/com/lagradost/cloudstream3/ui/result/StremioStreamsFragment.kt`.

The first phase 2 build (`b6fb83c`, run `41591628868`, job `125769452447`) failed because
`StremioStreamsContent` was top-level and had no Fragment `arguments`/`activity` receiver. It is
now a member of `StremioStreamsFragment`; run `41591916088`, job `125770347095`, succeeded at
`7282e95`.

**Artifact:** one unexpired `CloudStream-debug-apk` for this run, ID `13210163744`,
81,350,775 bytes, created `2026-11-03T06:47:27Z`. Runtime login, stream loading and player
handoff still need user testing. A direct sandbox TLS connection to `api.strem.io` failed, but a
GitHub Actions runner reached the API successfully; see section 9.2.

### 9.2 Live Stremio API probe and login false-failure fix

**Files:** `app/src/main/java/com/lagradost/cloudstream3/stremio/StremioModels.kt`,
`StremioApi.kt`, `StremioRepository.kt`,
`app/src/main/java/com/lagradost/cloudstream3/ui/settings/StremioAddonsFragment.kt`.

A temporary GitHub Actions probe called the official `https://api.strem.io/api/login`
endpoint with the supplied existing account. Credentials were passed only through a
temporary masked CI payload and are not stored in repository files or this journal.
All five tested request shapes returned HTTP 200 with the untagged success envelope
`{ "result": { "authKey": "...", "user": { ... } } }`; there was no `error` field.
The same runner also got HTTP 200 from `getUser` (minimal and `type`-tagged bodies) and
`addonCollectionGet` (minimal and `type`-tagged bodies), returning eight addons. A
temporary JVM unit test then exercised the app's own `StremioApi.login`,
`loginWithAuthKey`, `register`, post-registration `login`, and `addonCollectionGet`
against the live API: all succeeded, with only redacted key lengths/counts logged.
Sign-up used the supplied new-account email and a generated, masked password.

This proved that the generic "Stremio login failed" message did not come from Stremio or
from response parsing. It came from the settings/repository error path: both layers
caught `Throwable`, including `CancellationException`, and replaced any null-message
exception with a hardcoded failure string. The composition-scoped coroutine could also be
cancelled while a login was in flight. The fix now uses an app-level `SupervisorJob`
scope, rethrows cancellation, shows the real redacted exception class/message instead of
a generic failure, resolves account storage through either `CloudStreamApp.context` or
`getContext()`, and writes the auth key synchronously with `commit()` so `isLoggedIn()`
sees it immediately. `register` now includes `gdpr_consent.from`, matching the official
web client. API error text is redacted before it can be shown in the UI.

**Build:** `2d99584` failed at `:app:compileDebugKotlin` because `stremioScope` was
imported as a top-level symbol instead of an `StremioRepository` member. `b295128`
fixed the reference; run `41597139672`, job `125787030436`, succeeded in 3m13s.

**Artifact:** one unexpired `CloudStream-debug-apk`, ID `13212483803`, 81,353,609 bytes,
created `2026-11-03T07:36:39Z`. Install this exact artifact for the login retest. The
temporary probe workflow, unit test and payload commits were removed by a force-push;
no credentials or auth keys are stored in repository files or this journal. On-device
UI acceptance for all three login modes, the dedicated all-streams page, stream loading
and CloudStream player handoff is still pending.

### 9.3 Stremio addon transport, search and stream protocol parity

**Files:** `app/src/main/java/com/lagradost/cloudstream3/stremio/StremioApi.kt`,
`StremioModels.kt`, `StremioProvider.kt`, `StremioRepository.kt`,
`app/src/test/java/com/lagradost/cloudstream3/StremioTransportTest.kt`.

**Reference:** `https://github.com/stremio-native/stremio-android` (with its
`streamio-core-kotlin` / `stream-server` submodules) and
`Stremio/stremio-core@90c38f181d290fc705049e4c8bd30df00f6f3e66`. The native app delegates
search to `CatalogsWithExtra.Selected(extra=[search])`, meta/streams to `ResourcePath`, and
core builds addon URLs by replacing `/manifest.json` in the descriptor transport URL with
`/{resource}/{type}/{id}[/{extra}].json`.

**Root cause confirmed:** the official `addonCollectionGet` descriptors store
`transportUrl` ending in `/manifest.json`, while the old bridge kept that full string as the
resource base. A live check returned `Cannot GET
/manifest.json/catalog/movie/top/search=deadpool.json` for
`https://v3-cinemeta.strem.io/manifest.json/catalog/movie/top/search=deadpool.json`, but
`https://v3-cinemeta.strem.io/catalog/movie/top/search=deadpool.json` returned the expected
metas. The same broken prefix affected catalog, meta and stream calls, which explains both
the addon search errors and the provider API errors on the series/episode stream-load page.

**Fix:** `StremioApi` now parses the transport URL into a base plus an optional config query,
strips the `/manifest.json` suffix, preserves the query after the resource path, and uses
stremio-core's exact `URI_COMPONENT_ENCODE_SET` percent encoding. It also implements the
legacy `/stremio/v1` `q.json?b=<base64 JSON-RPC>` transport, maps legacy manifests into the
standard manifest shape, and logs redacted per-addon errors instead of silently dropping them.
`StremioModels` now mirrors core's short/full `ManifestResource` filtering, required/supported
catalog extras and `default_required_extra`, reads `behaviorHints.proxyHeaders.request`, and
keeps `externalUrl`/`androidTvUrl`/`playerFrameUrl` stream sources. `StremioProvider` searches
all installed search-capable catalogs (no arbitrary `.take(8)`/`.take(2)`), uses the episode
`videoId` for stream requests, and resolves external stream URLs. `StremioRepository` dedupes
and installs/uninstalls addons by canonical resource base so synced and manually installed
descriptors no longer produce duplicate, broken addon entries.

A JVM unit test asserts that a `/manifest.json` descriptor produces
`https://v3-cinemeta.strem.io/catalog/movie/top/search=dead%20pool.json`, that config queries
are appended after the resource path, that percent encoding matches core, and that the legacy
`/stremio/v1` transport is detected. It uses only the existing JUnit/JSON test dependencies.

**Build validation:** `5e737df` failed at `:app:compileDebugKotlin` because the new
`proxyHeaders.request` parsing called the key-based `stringMap` helper without a key.
`7bbe6a3` added a no-argument map helper; run `41602465500`, job `125804225564`, succeeded
in 2m59s. The workflow now also runs `:app:testDebugUnitTest` before `assembleDebug`.

**Artifact:** one unexpired `CloudStream-debug-apk` for run `41602465500`, ID
`13215061835`, 81,367,293 bytes, created `2026-11-03T08:22:29Z`. Install this exact
artifact for the addon search and series/episode stream retest. On-device acceptance
for addon search, stream loading, the dedicated all-streams page and CloudStream player
handoff remains pending. No credentials or auth keys were added.
