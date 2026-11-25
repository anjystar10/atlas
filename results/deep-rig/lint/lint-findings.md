# Android lint (Correctness, Security, Performance: errors and warnings)

All categories: Accessibility/Warning 18, Correctness/Error 2, Correctness/Warning 28, Internationalization/Error 1, Internationalization/Warning 57, Performance/Warning 108, Productivity/Warning 5, Security/Warning 1, Usability/Warning 28

## Error · Correctness · WrongConstant (1): Incorrect constant
- app/src/main/java/com/anjy/atlas/DocPdf.kt:108 — Must be one of: LineBreaker.BREAK_STRATEGY_SIMPLE, LineBreaker.BREAK_STRATEGY_HIGH_QUALITY, LineBreaker.BREAK_STRATEGY_BALANCED
    `.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).build()`

## Error · Correctness · PermissionImpliesUnsupportedChromeOsHardware (1): Permission Implies Unsupported Chrome OS Hardware
- app/src/main/AndroidManifest.xml:31 — Permission exists without corresponding hardware `<uses-feature android:name="android.hardware.telephony" required="false">` tag
    `<uses-permission android:name="android.permission.SEND_SMS" />`

## Error · Internationalization · ByteOrderMark (1): Byte order mark inside files
- app/src/main/java/com/anjy/atlas/BotFiles.kt:132 — Found byte-order-mark in the middle of a file
    `val cf = File(dir, xf.name.removeSuffix(".xlsx") + ".csv"); cf.writeText("﻿" + BotFileRules.toCsv(book.first().second))`

## Warning · Correctness · GradleDependency (6): Obsolete Gradle Dependency
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:9 — A newer version of androidx.activity:activity than 1.12.2 is available: 1.13.0
    `activity = "1.12.2"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:10 — A newer version of androidx.appcompat:appcompat than 1.7.1 is available: 1.8.0
    `appcompat = "1.7.1"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:11 — A newer version of androidx.core:core-ktx than 1.17.0 is available: 1.19.1
    `core-ktx = "1.17.0"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:12 — A newer version of androidx.constraintlayout:constraintlayout than 2.2.1 is available: 2.2.2
    `constraint-layout = "2.2.1"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:13 — A newer version of androidx.datastore:datastore-preferences than 1.2.0 is available: 1.3.0
    `datastore-preferences = "1.2.0"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:16 — A newer version of com.google.android.material:material than 1.13.0 is available: 1.14.0
    `material = "1.13.0"`

## Warning · Correctness · NewerVersionAvailable (6): Newer Library Versions Available
- /home/runner/work/atlas/atlas/app/build.gradle.kts:70 — A newer version of net.java.dev.jna:jna than 5.18.1 is available: 5.19.1
    `implementation("net.java.dev.jna:jna:5.18.1@aar")`
- /home/runner/work/atlas/atlas/app/build.gradle.kts:72 — A newer version of com.sun.mail:android-mail than 1.6.7 is available: 1.6.8
    `implementation("com.sun.mail:android-mail:1.6.7")`
- /home/runner/work/atlas/atlas/app/build.gradle.kts:73 — A newer version of com.sun.mail:android-activation than 1.6.7 is available: 1.6.8
    `implementation("com.sun.mail:android-activation:1.6.7")`
- /home/runner/work/atlas/atlas/app/build.gradle.kts:76 — A newer version of org.json:json than 20240303 is available: 20260814
    `testImplementation("org.json:json:20240303")`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:2 — A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-android than 1.10.2 is available: 1.11.0
    `coroutines = "1.10.2"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:6 — A newer version of org.jetbrains.kotlin.android than 2.3.0 is available: 2.4.21
    `kotlin = "2.3.0"`

## Warning · Correctness · InlinedApi (5): Using inlined constants on older versions
- app/src/main/java/com/anjy/atlas/AvatarService.kt:64 — Field requires API level 34 (current min is 33): `android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
    `startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`
- app/src/main/java/com/anjy/atlas/AvatarService.kt:114 — Field requires API level 34 (current min is 33): `android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
    `startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)`
- app/src/main/java/com/anjy/atlas/AvatarService.kt:123 — Field requires API level 34 (current min is 33): `android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
    `try { startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } catch (_: Exception) {}`
- app/src/main/java/com/anjy/atlas/BotService.kt:75 — Field requires API level 34 (current min is 33): `android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
    `try { startForeground(NOTIF_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }`
- app/src/main/java/com/anjy/atlas/LinkService.kt:46 — Field requires API level 34 (current min is 33): `android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
    `try { startForeground(ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }`

## Warning · Correctness · AndroidGradlePluginVersion (3): Obsolete Android Gradle Plugin Version
- /home/runner/work/atlas/atlas/gradle/wrapper/gradle-wrapper.properties:4 — A newer version of Gradle than 8.14.3 is available: 8.14.8
    `distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:5 — A newer version of com.android.application than 8.13.2 is available: 9.5.3
    `agp = "8.13.2"`
- /home/runner/work/atlas/atlas/gradle/libs.versions.toml:5 — A newer version of com.android.library than 8.13.2 is available: 9.5.3
    `agp = "8.13.2"`

## Warning · Correctness · MissingPermission (2): Missing Permissions
- app/src/main/java/com/anjy/atlas/BotExtras.kt:510 — Setting Exact alarms with `setExactAndAllowWhileIdle` requires the `SCHEDULE_EXACT_ALARM` permission or power exemption from user; it is intended for applications where the user knowingly schedules actions to happen at a precise time such as alarms, clocks, calendars, etc. Check out the javadoc on t
    `if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)`
- app/src/main/java/com/anjy/atlas/Reminders.kt:107 — Setting Exact alarms with `setAlarmClock` requires the `SCHEDULE_EXACT_ALARM` permission or power exemption from user; it is intended for applications where the user knowingly schedules actions to happen at a precise time such as alarms, clocks, calendars, etc. Check out the javadoc on this permissi
    `am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)`

## Warning · Correctness · BatteryLife (2): Battery Life Issues
- app/src/main/java/com/anjy/atlas/MainActivity.kt:2889 — Use of `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` violates the Play Store Content Policy regarding acceptable use cases, as described in https://developer.android.com/training/monitoring-device-state/doze-standby.html
    `try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }`
- app/src/main/java/com/anjy/atlas/PermissionCenter.kt:70 — Use of `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` violates the Play Store Content Policy regarding acceptable use cases, as described in https://developer.android.com/training/monitoring-device-state/doze-standby.html
    `"battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)`

## Warning · Correctness · OldTargetApi (1): Target SDK attribute is not targeting latest version
- /home/runner/work/atlas/atlas/app/build.gradle.kts:13 — Not targeting the latest versions of Android; compatibility modes apply. Consider testing and updating this version. Consult the `android.os.Build.VERSION_CODES` javadoc for details.
    `targetSdk = 35`

## Warning · Correctness · SwitchIntDef (1): Missing @IntDef in Switch
- app/src/main/java/com/anjy/atlas/Orb.kt:232 — Don't use a constant here; expected one of: `SpeechRecognizer.ERROR_AUDIO`, `SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT`, `SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`, `SpeechRecognizer.ERROR_CLIENT`, `SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS`, `SpeechRecognizer.ERROR_LANGUAGE_N
    `SpeechRecognizer.ERROR_CLIENT, 11 /* server disconnected */ -> again("Speech recognition stopped. Try again.")`

## Warning · Correctness · DiscouragedApi (1): Using discouraged APIs
- app/src/main/AndroidManifest.xml:229 — Fixed screen orientations will be ignored in most cases, starting from Android 16. Android is moving toward a model where apps are expected to adapt to various orientations, display sizes, and aspect ratios.
    `android:screenOrientation="unspecified"`

## Warning · Correctness · ChromeOsAbiSupport (1): Missing ABI Support for ChromeOS
- /home/runner/work/atlas/atlas/app/build.gradle.kts:17 — Missing x86_64 ABI support for ChromeOS
    `abiFilters += listOf("arm64-v8a")`

## Warning · Performance · DrawAllocation (61): Memory allocations within drawing code
- app/src/main/java/com/anjy/atlas/Orb.kt:63 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `val pic = placeholder ?: android.graphics.BitmapFactory.decodeResource(resources, R.drawable.orb_still).also { placeholder = it }`
- app/src/main/java/com/anjy/atlas/Orb.kt:65 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `canvas.drawBitmap(pic, null, android.graphics.RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2), filter)`
- app/src/main/java/com/anjy/atlas/stoa_Depth.kt:401 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `embers.add(Ember(w * (0.08f + Random.nextFloat() * 0.38f), h * (0.75f + Random.nextFloat() * 0.25f),`
- app/src/main/java/com/anjy/atlas/stoa_FlameView.kt:669 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(cx, cy, hr, intArrayOf(`
- app/src/main/java/com/anjy/atlas/stoa_FlameView.kt:669 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(cx, cy, hr, intArrayOf(`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:81 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(0xFFD6D6DA.toInt(), 0xFF2A2A2E.toInt(), t), mix(0xFFA8A8AE.toInt(), 0xFF141416.toInt(), t), Shader.TileMo`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:81 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(0xFFD6D6DA.toInt(), 0xFF2A2A2E.toInt(), t), mix(0xFFA8A8AE.toInt(), 0xFF141416.toInt(), t), Shader.TileMo`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:83 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `s.strokeWidth = u; s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb((60 * (1 - t) + 14 * t).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), `
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:83 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `s.strokeWidth = u; s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb((60 * (1 - t) + 14 * t).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), `
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:97 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(kx, cy + 6 * u, kw * 0.9f, Color.argb((150 * (1 - t)).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:97 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(kx, cy + 6 * u, kw * 0.9f, Color.argb((150 * (1 - t)).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:107 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(kx - kw * 0.15f, cy - kh * 0.2f, kw * 0.8f, Color.argb(40, 255, 255, 255), Color.argb(6, 255, 255, 255), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:107 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(kx - kw * 0.15f, cy - kh * 0.2f, kw * 0.8f, Color.argb(40, 255, 255, 255), Color.argb(6, 255, 255, 255), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:110 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(110, 255, 255, 255)), fl`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:110 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `s.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(110, 255, 255, 255)), fl`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:454 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `path.arcTo(RectF(R - 2 * b, cy - b, R, cy + b), -90f, 180f)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:456 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `path.arcTo(RectF(L, cy - a, L + 2 * a, cy + a), 90f, 180f)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:465 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - h / 2, 0f, cy + h / 2, tint(color, Color.WHITE, 0.28f), tint(color, Color.BLACK, 0.06f), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:465 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - h / 2, 0f, cy + h / 2, tint(color, Color.WHITE, 0.28f), tint(color, Color.BLACK, 0.06f), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:467 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - h / 2, 0f, cy, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:467 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - h / 2, 0f, cy, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Mascot.kt:141 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - coinR + press, 0f, cy + coinR + press, mix(Color.WHITE, face, 0.22f), face, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Mascot.kt:141 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - coinR + press, 0f, cy + coinR + press, mix(Color.WHITE, face, 0.22f), face, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_SlideSwitch.kt:71 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(base, Color.WHITE, if (dark) 0.10f else 0.14f), base, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_SlideSwitch.kt:71 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(base, Color.WHITE, if (dark) 0.10f else 0.14f), base, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_SlideSwitch.kt:76 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(if (dark) 70 else 120, 255, 255, 255), Color.argb(if (dark) 10 else 30, 0, 0, 0), Shader.TileMode.`
- app/src/main/java/com/anjy/atlas/stoa_SlideSwitch.kt:76 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `s.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(if (dark) 70 else 120, 255, 255, 255), Color.argb(if (dark) 10 else 30, 0, 0, 0), Shader.TileMode.`
- app/src/main/java/com/anjy/atlas/stoa_SlideSwitch.kt:87 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - kr, 0f, cy + kr, mix(knob, Color.WHITE, 0.18f), knob, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_SlideSwitch.kt:87 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, cy - kr, 0f, cy + kr, mix(knob, Color.WHITE, 0.18f), knob, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:211 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `val marble = LinearGradient(0f, 0f, cw, 0f, intArrayOf(shadow, ink, shadow), floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.MIRROR)`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:236 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `line.pathEffect = if (d == Day.OPEN) DashPathEffect(floatArrayOf(cw * 0.16f, cw * 0.16f), 0f) else null`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:236 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `line.pathEffect = if (d == Day.OPEN) DashPathEffect(floatArrayOf(cw * 0.16f, cw * 0.16f), 0f) else null`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:248 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `val path = Path().apply {`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:352 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `val marble = LinearGradient(0f, 0f, cw, 0f, intArrayOf(shadow, ink, shadow), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.MIRROR)`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:354 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.argb(40, Color.red(ink), Color.green(ink), Color.blue(ink)), ink), floatArrayOf(0f, 0.5f), Shader.Tile`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:354 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.argb(40, Color.red(ink), Color.green(ink), Color.blue(ink)), ink), floatArrayOf(0f, 0.5f), Shader.Tile`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:366 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `val ember = LinearGradient(0f, 0f, cw, 0f, intArrayOf(emberA, emberB, emberC), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.MIRROR)`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:373 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(newX, 0f, newX + cw, 0f, intArrayOf(emberA, emberB, emberC), null, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:373 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = LinearGradient(newX, 0f, newX + cw, 0f, intArrayOf(emberA, emberB, emberC), null, Shader.TileMode.CLAMP)`
- app/src/main/java/com/anjy/atlas/stoa_Stoa.kt:378 — Avoid object allocations during draw/layout operations (preallocate and reuse instead)
    `p.shader = RadialGradient(newX + cw / 2, floor, cw * 2.2f, Color.argb((120 * k.coerceAtMost(1f)).toInt(), 255, 122, 46), Color.argb(0, 255, 122, 46), Shader.Til`
- … and 21 more

## Warning · Performance · StaticFieldLeak (21): Static Field Leaks
- app/src/main/java/com/anjy/atlas/AvatarService.kt:661 — Do not place Android context classes in static fields (static reference to `AvatarService` which has field `orbFrame` pointing to `FrameLayout`); this is a memory leak
    `@Volatile private var instance: AvatarService? = null`
- app/src/main/java/com/anjy/atlas/BotBrowser.kt:34 — Do not place Android context classes in static fields (static reference to `BotBrowser` which has field `web` pointing to `WebView`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/BotBrowser.kt:60 — Do not place Android context classes in static fields; this is a memory leak
    `private var web: WebView? = null`
- app/src/main/java/com/anjy/atlas/BotBrowser.kt:61 — Do not place Android context classes in static fields; this is a memory leak
    `private var ctxWrap: MutableContextWrapper? = null`
- app/src/main/java/com/anjy/atlas/BotBrowser.kt:62 — Do not place Android context classes in static fields; this is a memory leak
    `private lateinit var app: Context`
- app/src/main/java/com/anjy/atlas/BotExtras.kt:19 — Do not place Android context classes in static fields (static reference to `BotExtras` which has field `app` pointing to `Context`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/BotExtras.kt:296 — Do not place Android context classes in static fields; this is a memory leak
    `private lateinit var app: Context`
- app/src/main/java/com/anjy/atlas/Bots.kt:28 — Do not place Android context classes in static fields (static reference to `Bots` which has field `app` pointing to `Context`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/Bots.kt:52 — Do not place Android context classes in static fields; this is a memory leak
    `private lateinit var app: Context`
- app/src/main/java/com/anjy/atlas/Brain.kt:19 — Do not place Android context classes in static fields (static reference to `ModelManager` which has field `appCtx` pointing to `Context`); this is a memory leak
    `/** The local model slot. Any GGUF chat model runs here through llama.cpp, fully on the phone. */`
- app/src/main/java/com/anjy/atlas/Brain.kt:39 — Do not place Android context classes in static fields; this is a memory leak
    `@Volatile private var appCtx: Context? = null`
- app/src/main/java/com/anjy/atlas/Link.kt:17 — Do not place Android context classes in static fields (static reference to `Link` which has field `app` pointing to `Context`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/Link.kt:40 — Do not place Android context classes in static fields; this is a memory leak
    `lateinit var app: Context; private set`
- app/src/main/java/com/anjy/atlas/ScreenGate.kt:26 — Do not place Android context classes in static fields (static reference to `ScreenGate` which has field `banner` pointing to `View`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/ScreenGate.kt:54 — Do not place Android context classes in static fields; this is a memory leak
    `private var banner: View? = null`
- app/src/main/java/com/anjy/atlas/ScreenGate.kt:124 — Do not place Android context classes in static fields; this is a memory leak
    `@Volatile private var appRef: Context? = null`
- app/src/main/java/com/anjy/atlas/Threads.kt:12 — Do not place Android context classes in static fields (static reference to `Threads` which has field `app` pointing to `Context`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/Threads.kt:31 — Do not place Android context classes in static fields; this is a memory leak
    `private lateinit var app: Context`
- app/src/main/java/com/anjy/atlas/TrigRun.kt:14 — Do not place Android context classes in static fields (static reference to `TrigRun` which has field `app` pointing to `Context`); this is a memory leak
    `/**`
- app/src/main/java/com/anjy/atlas/TrigRun.kt:33 — Do not place Android context classes in static fields; this is a memory leak
    `private lateinit var app: Context`
- app/src/main/java/com/anjy/atlas/Voice.kt:306 — Do not place Android context classes in static fields; this is a memory leak
    `private var focusCtx: Context? = null`

## Warning · Performance · ObsoleteSdkInt (15): Obsolete SDK_INT Version Check
- app/src/main/java/com/anjy/atlas/DeviceProfile.kt:55 — Unnecessary; `SDK_INT` is always >= 33
    `val maker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""`
- app/src/main/java/com/anjy/atlas/DeviceProfile.kt:56 — Unnecessary; `SDK_INT` is always >= 33
    `val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""`
- app/src/main/java/com/anjy/atlas/MainActivity.kt:273 — Unnecessary; `SDK_INT` is always >= 33
    `if (!dark) { elevation = dp(5).toFloat(); outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; if (Build.VERSION.SDK_INT >= 28) { outlineAmbientShadow`
- app/src/main/java/com/anjy/atlas/MainActivity.kt:435 — Unnecessary; `SDK_INT` is always >= 33
    `if (Build.VERSION.SDK_INT >= 30) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)`
- app/src/main/java/com/anjy/atlas/MainActivity.kt:749 — Unnecessary; `SDK_INT` is always >= 33
    `if (Build.VERSION.SDK_INT >= 30) {`
- app/src/main/java/com/anjy/atlas/MainActivity.kt:845 — Unnecessary; `SDK_INT` is always >= 33
    `if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }`
- app/src/main/java/com/anjy/atlas/MainActivity.kt:913 — Unnecessary; `SDK_INT` is always >= 33
    `if (Build.VERSION.SDK_INT >= 29) { root.isForceDarkAllowed = false; window.decorView.isForceDarkAllowed = false }`
- app/src/main/java/com/anjy/atlas/Remote.kt:49 — Unnecessary; `SDK_INT` is always >= 33
    `fun canCapture() = Build.VERSION.SDK_INT >= 30 && AtlasAccess.instance != null`
- app/src/main/java/com/anjy/atlas/Remote.kt:126 — Unnecessary; `SDK_INT` is never < 33
    `if (Build.VERSION.SDK_INT < 30) return null`
- app/src/main/java/com/anjy/atlas/Remote.kt:184 — Unnecessary; `SDK_INT` is always >= 33
    `"enter" -> focused(svc)?.let { n -> if (Build.VERSION.SDK_INT >= 30 && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) null else`
- app/src/main/res/mipmap-anydpi-v26:? — This folder configuration (`v26`) is unnecessary; `minSdkVersion` is 33. Merge all the resources in this folder into `mipmap-anydpi`.
- app/src/main/java/com/anjy/atlas/stoa_Finish.kt:112 — Unnecessary; `SDK_INT` is always >= 33
    `if (android.os.Build.VERSION.SDK_INT >= 28) {`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:277 — Unnecessary; `SDK_INT` is always >= 33
    `if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Color.argb(255, 0, 0, 0); outlineAmbientShadowColor = Color.argb(255, 0, 0, 0) }`
- app/src/main/java/com/anjy/atlas/stoa_Glass.kt:461 — Unnecessary; `SDK_INT` is always >= 33
    `if (android.os.Build.VERSION.SDK_INT >= 28) g.setShadowLayer(9 * d, 0f, 0f, Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)))`
- app/src/main/java/com/anjy/atlas/stoa_StoaUi.kt:212 — Unnecessary; `SDK_INT` is always >= 33
    `performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL`

## Warning · Performance · UnusedResources (7): Unused resources
- app/src/main/res/drawable/ic_bell.xml:3 — The resource `R.drawable.ic_bell` appears to be unused
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`
- app/src/main/res/drawable/ic_chat.xml:3 — The resource `R.drawable.ic_chat` appears to be unused
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`
- app/src/main/res/values/ic_launcher.xml:3 — The resource `R.color.ic_launcher_bg` appears to be unused
    `<color name="ic_launcher_bg">#2A1E6E</color>`
- app/src/main/res/drawable/ic_moon.xml:3 — The resource `R.drawable.ic_moon` appears to be unused
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`
- app/src/main/res/drawable/ic_plus.xml:3 — The resource `R.drawable.ic_plus` appears to be unused
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`
- app/src/main/res/drawable/nav_home_off.xml:3 — The resource `R.drawable.nav_home_off` appears to be unused
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`
- app/src/main/res/drawable/nav_home_on.xml:3 — The resource `R.drawable.nav_home_on` appears to be unused
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`

## Warning · Performance · UsableSpace (4): Using getUsableSpace()
- app/src/main/java/com/anjy/atlas/Attach.kt:97 — Consider also using `StorageManager#getAllocatableBytes` and `allocateBytes` which will consider clearable cached data
    `if (total > 0 && total + RESERVE > up.usableSpace)`
- app/src/main/java/com/anjy/atlas/Attach.kt:98 — Consider also using `StorageManager#getAllocatableBytes` and `allocateBytes` which will consider clearable cached data
    `throw TooBig("Not enough room on the phone for “$name” (${AttachRules.size(total)}): ${AttachRules.size((up.usableSpace - RESERVE).coerceAtLeast(0))} is free to`
- app/src/main/java/com/anjy/atlas/Attach.kt:103 — Consider also using `StorageManager#getAllocatableBytes` and `allocateBytes` which will consider clearable cached data
    `ins.use { i -> out.outputStream().use { o -> copyStream(i, o, total, progress) { cx.ensureActive(); if (up.usableSpace < RESERVE) throw TooBig("The phone ran ou`
- app/src/main/java/com/anjy/atlas/Brain.kt:58 — Consider also using `StorageManager#getAllocatableBytes` and `allocateBytes` which will consider clearable cached data
    `if (size > 0 && dest.parentFile!!.usableSpace < size + 200_000_000L) {`

## Warning · Security · UnsafeProtectedBroadcastReceiver (1): Unsafe Protected `BroadcastReceiver`
- app/src/main/java/com/anjy/atlas/Reminders.kt:196 — This broadcast receiver declares an intent-filter for a protected broadcast action string, which can only be sent by the system, not third-party applications. However, the receiver's `onReceive` method does not appear to call `getAction` to ensure that the received Intent's action string matches the
    `override fun onReceive(context: Context, intent: Intent) {`

