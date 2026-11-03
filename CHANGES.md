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

## 8. Current validation

**Build:** `e8b6edb22d376eaaca2e67c612c25351db223196`, GitHub Actions run `41587855589`, job
`125757432852`, conclusion `success`, finished `2026-11-03T06:10:15Z`.

**Artifact:** one `CloudStream-debug-apk` for this run, ID `13208388375`, unexpired,
81,333,857 bytes, created `2026-11-03T06:09:50Z`. Earlier validated artifacts from runs
`41523213497`, `41528025087` and `41532042162` may also remain until GitHub expires them; the
current APK is the phase 1 build that includes sections 1–6.1.

**Scope note:** a successful compile does not mean the Stremio bridge has runtime parity. This
build predates section 9. The dedicated stream-list page is now implemented, but runtime login,
stream loading and the remaining parity items in section 6 still need confirmation before calling
the Stremio integration complete.

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
