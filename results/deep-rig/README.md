# Deep test — deep/rig @ ae0d1ed

Run: https://github.com/anjystar10/atlas/actions/runs/45688693330

Parts: crawl-api34, crawl-api35, desktop, lint, monkey-api34, monkey-api35


---

<!-- crawl-api34/findings.md -->
# Deep test — crawl

abi: x86_64,arm64-v8a
sdk: 34
release: 14
tz: Africa/Lagos
translation: libndk_translation.so
Physical size: 320x640
Physical density: 160
    versionCode=25 minSdk=33 targetSdk=35
    versionName=0.11.4

Atlas processes seen: ['com.anjy.atlas', 'com.anjy.atlas:hotword']

## Crashes (0)

## ANRs (0, traces 0)

## Native crashes (0; known emulator SIGILL in the wake word engine: 2)

## Monkey

## Crawler events (322)
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: StrictMode policy violation; ~duration=2746 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat libcore.io.BlockGuardOs.access(BlockGuardOs.java:74)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat libcore.io.ForwardingOs.access(ForwardingOs.java:128)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.app.ActivityThread$AndroidOs.access(ActivityThread.java:8054)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat java.io.UnixFileSystem.checkAccess(UnixFileSystem.java:313)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat java.io.File.exists(File.java:813)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat anyp.a(:com.google.android.gms@231818047@23.18.18 (190800-535401451):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat anzs.e(:com.google.android.gms@231818047@23.18.18 (190800-535401451):19)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat anzs.b(:com.google.android.gms@231818047@23.18.18 (190800-535401451):6)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat anzv.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):11)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat zde.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProvider.query(ContentProvider.java:1596)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat hfr.superQuery(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat com.google.android.chimera.ContentProvider.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat zde.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):9)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProvider.query(ContentProvider.java:1692)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat hfr.superQuery(:com.google.android.gms@231818047@23.18.18 (190800-535401451):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat com.google.android.chimera.ContentProvider.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat hfr.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProvider$Transport.query(ContentProvider.java:292)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProviderNative.onTransact(ContentProviderNative.java:107)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Binder.execTransactInternal(Binder.java:1339)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Binder.execTransact(Binder.java:1275)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: # via Binder call with stack:", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.StrictMode.readAndHandleBinderCallViolations(StrictMode.java:2538)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Parcel.readExceptionCode(Parcel.java:3002)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:139)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProviderProxy.query(ContentProviderNative.java:495)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProviderClient.query(ContentProviderClient.java:196)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.content.ContentProviderClient.query(ContentProviderClient.java:178)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat bbo.a(PG:273)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat bkb.a(PG:14)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.380  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: StrictMode policy violation; ~duration=2746 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat libcore.io.BlockGuardOs.statvfs(BlockGuardOs.java:426)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat libcore.io.ForwardingOs.statvfs(ForwardingOs.java:852)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.system.Os.statvfs(Os.java:909)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.StatFs.doStat(StatFs.java:51)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.StatFs.<init>(StatFs.java:43)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat anyp.l(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat anzs.e(:com.google.android.gms@231818047@23.18.18 (190800-535401451):21)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat anzs.b(:com.google.android.gms@231818047@23.18.18 (190800-535401451):6)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat anzv.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):11)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat zde.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProvider.query(ContentProvider.java:1596)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat hfr.superQuery(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat com.google.android.chimera.ContentProvider.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat zde.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):9)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProvider.query(ContentProvider.java:1692)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat hfr.superQuery(:com.google.android.gms@231818047@23.18.18 (190800-535401451):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat com.google.android.chimera.ContentProvider.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat hfr.query(:com.google.android.gms@231818047@23.18.18 (190800-535401451):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProvider$Transport.query(ContentProvider.java:292)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProviderNative.onTransact(ContentProviderNative.java:107)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Binder.execTransactInternal(Binder.java:1339)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Binder.execTransact(Binder.java:1275)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: # via Binder call with stack:", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.StrictMode.readAndHandleBinderCallViolations(StrictMode.java:2538)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Parcel.readExceptionCode(Parcel.java:3002)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:139)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProviderProxy.query(ContentProviderNative.java:495)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProviderClient.query(ContentProviderClient.java:196)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.content.ContentProviderClient.query(ContentProviderClient.java:178)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat bbo.a(PG:273)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat bkb.a(PG:14)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.381  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: StrictMode policy violation; ~duration=41 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileDispatcherImpl.size(FileDispatcherImpl.java:106)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileChannelImpl.size(FileChannelImpl.java:359)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.graphics.fonts.Font$Builder.<init>(Font.java:167)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.graphics.fonts.Font$Builder.<init>(Font.java:153)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bag.a(PG:25)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bac.a(PG:3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat azw.b(PG:2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bkb.a(PG:166)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: StrictMode policy violation; ~duration=41 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileDispatcherImpl.size(FileDispatcherImpl.java:106)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileChannelImpl.map(FileChannelImpl.java:945)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.graphics.fonts.Font$Builder.<init>(Font.java:168)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.graphics.fonts.Font$Builder.<init>(Font.java:153)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bag.a(PG:25)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bac.a(PG:3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat azw.b(PG:2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bkb.a(PG:166)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: StrictMode policy violation; ~duration=41 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileChannelImpl.map(FileChannelImpl.java:982)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.graphics.fonts.Font$Builder.<init>(Font.java:168)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.graphics.fonts.Font$Builder.<init>(Font.java:153)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bag.a(PG:25)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bac.a(PG:3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat azw.b(PG:2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bkb.a(PG:166)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: StrictMode policy violation; ~duration=28 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileDispatcherImpl.size(FileDispatcherImpl.java:106)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileChannelImpl.size(FileChannelImpl.java:359)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat ayy.c(PG:28)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bkb.a(PG:174)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: StrictMode policy violation; ~duration=28 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileDispatcherImpl.size(FileDispatcherImpl.java:106)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat sun.nio.ch.FileChannelImpl.map(FileChannelImpl.java:945)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat ayy.c(PG:36)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bkb.a(PG:174)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.383  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: StrictMode policy violation; ~duration=28 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1666)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat sun.nio.ch.FileChannelImpl.map(FileChannelImpl.java:982)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat ayy.c(PG:36)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat bkb.a(PG:174)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat bho.run(PG:235)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat android.os.Handler.handleCallback(Handler.java:958)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:99)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:205)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat android.os.Looper.loop(Looper.java:294)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat qgi.run(PG:13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat qgg.run(PG:604)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:00:16.384  1796  2079 D StrictMode: \tat qig.run(PG:64)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: StrictMode policy violation: android.os.strictmode.SqliteObjectLeakedViolation: Finalizing a Cursor that has not been deactivated or closed. database = /data/user/0/com.android.providers.contacts/databases/contacts2.db, table = raw_contacts, query = SELECT contacts_view._id AS _id, contacts_view.display_name AS display_name, contacts_view.photo_thumb_ur", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat android.os.StrictMode.onSqliteObjectLeaked(StrictMode.java:2222)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat android.database.sqlite.SQLiteCursor.finalize(SQLiteCursor.java:282)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat java.lang.Daemons$FinalizerDaemon.doFinalize(Daemons.java:339)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat java.lang.Daemons$FinalizerDaemon.processReference(Daemons.java:324)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat java.lang.Daemons$FinalizerDaemon.runInternal(Daemons.java:300)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat java.lang.Daemons$Daemon.run(Daemons.java:145)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: StrictMode policy violation: android.os.strictmode.LeakedClosableViolation: A resource was acquired at attached stack trace but never released. See java.io.Closeable for information on avoiding resource leaks.", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat android.os.StrictMode$AndroidCloseGuardReporter.report(StrictMode.java:1994)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat dalvik.system.CloseGuard.warnIfOpen(CloseGuard.java:336)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat android.database.AbstractCursor.finalize(AbstractCursor.java:531)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}
- strictmode: {"line": "11-25 21:10:51.554  1927  1938 D StrictMode: \tat android.database.sqlite.SQLiteCursor.finalize(SQLiteCursor.java:291)", "after": {"scenario": "ask", "label": "deep test back online with a key that is not real"}, "t": 672.3}

… (152 more lines in crawl-api34/findings.md)

---

<!-- crawl-api35/findings.md -->
# Deep test — crawl

abi: x86_64,arm64-v8a
sdk: 35
release: 15
tz: America/New_York
translation: libndk_translation.so
Physical size: 320x640
Physical density: 160
    versionCode=25 minSdk=33 targetSdk=35
    versionName=0.11.4

Atlas processes seen: ['com.anjy.atlas', 'com.anjy.atlas:hotword']

## Crashes (0)

## ANRs (0, traces 0)

## Native crashes (0; known emulator SIGILL in the wake word engine: 0)

## Monkey

## Crawler events (682)
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: StrictMode policy violation: android.os.strictmode.CustomViolation: gcore.dynamite", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onCustomSlowCall(StrictMode.java:1650)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.os.StrictMode.noteSlowCall(StrictMode.java:2785)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat anlp.a(:com.google.android.gms@242335041@24.23.35 (190800-646585959):3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.chimera.container.GmsModuleChimeraProvider.query(:com.google.android.gms@242335041@24.23.35 (190800-646585959):135)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat ajjb.query(:com.google.android.gms@242335041@24.23.35 (190800-646585959):3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentProvider.query(ContentProvider.java:1620)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat pgr.superQuery(:com.google.android.gms@242335041@24.23.35 (190800-646585959):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.chimera.ContentProvider.query(:com.google.android.gms@242335041@24.23.35 (190800-646585959):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat ajjb.query(:com.google.android.gms@242335041@24.23.35 (190800-646585959):13)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentProvider.query(ContentProvider.java:1716)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat pgr.superQuery(:com.google.android.gms@242335041@24.23.35 (190800-646585959):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.chimera.ContentProvider.query(:com.google.android.gms@242335041@24.23.35 (190800-646585959):1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat pgr.query(:com.google.android.gms@242335041@24.23.35 (190800-646585959):2)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentProvider$Transport.query(ContentProvider.java:295)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentProviderNative.onTransact(ContentProviderNative.java:107)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.os.Binder.execTransactInternal(Binder.java:1500)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.os.Binder.execTransact(Binder.java:1444)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: # via Binder call with stack:", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.os.StrictMode.readAndHandleBinderCallViolations(StrictMode.java:2588)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.os.Parcel.readExceptionCode(Parcel.java:3187)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:152)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentProviderProxy.query(ContentProviderNative.java:495)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentResolver.query(ContentResolver.java:1229)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentResolver.query(ContentResolver.java:1161)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat android.content.ContentResolver.query(ContentResolver.java:1117)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.dynamite.DynamiteModule.e(PG:69)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.dynamite.DynamiteModule.b(PG:87)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.dynamite.e.b(PG:1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.dynamite.h.a(PG:23)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.dynamite.DynamiteModule.d(PG:58)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.common.q.b(PG:24)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.common.aa.g(PG:28)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.common.aa.a(PG:22)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.gms.common.aa.d(PG:1)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.frameworks.client.data.android.b.c.a(PG:3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat io.grpc.b.a.k.a(PG:62)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat io.grpc.es.a(PG:5)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat io.grpc.f.le.a(PG:30)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat io.grpc.f.bc.run(PG:7)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat io.grpc.f.kq.run(PG:17)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.libraries.k.x.run(PG:3)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.common.n.c.c.run(PG:50)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.libraries.k.e.run(PG:8)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat java.lang.Thread.run(Thread.java:1012)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:53.518  1539  1951 D StrictMode: \tat com.google.android.libraries.k.d.b.run(PG:74)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: StrictMode policy violation; ~duration=62 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1683)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat libcore.io.BlockGuardOs.access(BlockGuardOs.java:74)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat libcore.io.ForwardingOs.access(ForwardingOs.java:128)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread$AndroidOs.access(ActivityThread.java:8582)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat java.io.UnixFileSystem.checkAccess(UnixFileSystem.java:332)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat java.io.File.exists(File.java:829)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ContextImpl.ensurePrivateDirExists(ContextImpl.java:819)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ContextImpl.ensurePrivateDirExists(ContextImpl.java:810)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ContextImpl.getPreferencesDir(ContextImpl.java:766)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ContextImpl.getSharedPreferencesPath(ContextImpl.java:991)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ContextImpl.getSharedPreferences(ContextImpl.java:606)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.content.ContextWrapper.getSharedPreferences(ContextWrapper.java:223)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.content.ContextWrapper.getSharedPreferences(ContextWrapper.java:223)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat com.android.providers.contacts.ContactsUpgradeReceiver.onReceive(ContactsUpgradeReceiver.java:55)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread.handleReceiver(ActivityThread.java:4772)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread.-$$Nest$mhandleReceiver(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread$H.handleMessage(ActivityThread.java:2436)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:107)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:232)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.Looper.loop(Looper.java:317)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread.main(ActivityThread.java:8705)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat java.lang.reflect.Method.invoke(Native Method)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: StrictMode policy violation; ~duration=62 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1683)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.awaitLoadedLocked(SharedPreferencesImpl.java:283)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.getInt(SharedPreferencesImpl.java:328)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat com.android.providers.contacts.ContactsUpgradeReceiver.onReceive(ContactsUpgradeReceiver.java:56)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread.handleReceiver(ActivityThread.java:4772)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread.-$$Nest$mhandleReceiver(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread$H.handleMessage(ActivityThread.java:2436)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:107)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:232)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.os.Looper.loop(Looper.java:317)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat android.app.ActivityThread.main(ActivityThread.java:8705)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat java.lang.reflect.Method.invoke(Native Method)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.945  1664  1664 D StrictMode: \tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: StrictMode policy violation; ~duration=60 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1683)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat libcore.io.BlockGuardOs.access(BlockGuardOs.java:74)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat libcore.io.ForwardingOs.access(ForwardingOs.java:128)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread$AndroidOs.access(ActivityThread.java:8582)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat java.io.UnixFileSystem.checkAccess(UnixFileSystem.java:332)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat java.io.File.exists(File.java:829)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.writeToFile(SharedPreferencesImpl.java:746)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$mwriteToFile(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$1.run(SharedPreferencesImpl.java:680)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.enqueueDiskWrite(SharedPreferencesImpl.java:699)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$menqueueDiskWrite(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$EditorImpl.commit(SharedPreferencesImpl.java:612)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat com.android.providers.contacts.ContactsUpgradeReceiver.onReceive(ContactsUpgradeReceiver.java:76)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread.handleReceiver(ActivityThread.java:4772)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread.-$$Nest$mhandleReceiver(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread$H.handleMessage(ActivityThread.java:2436)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:107)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:232)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.Looper.loop(Looper.java:317)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread.main(ActivityThread.java:8705)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat java.lang.reflect.Method.invoke(Native Method)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: StrictMode policy violation; ~duration=60 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.1}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1683)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat libcore.io.BlockGuardOs.open(BlockGuardOs.java:269)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat libcore.io.ForwardingOs.open(ForwardingOs.java:563)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread$AndroidOs.open(ActivityThread.java:8591)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat libcore.io.IoBridge.open(IoBridge.java:560)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat java.io.FileOutputStream.<init>(FileOutputStream.java:259)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat java.io.FileOutputStream.<init>(FileOutputStream.java:208)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.createFileOutputStream(SharedPreferencesImpl.java:710)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.writeToFile(SharedPreferencesImpl.java:801)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$mwriteToFile(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$1.run(SharedPreferencesImpl.java:680)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.enqueueDiskWrite(SharedPreferencesImpl.java:699)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$menqueueDiskWrite(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$EditorImpl.commit(SharedPreferencesImpl.java:612)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat com.android.providers.contacts.ContactsUpgradeReceiver.onReceive(ContactsUpgradeReceiver.java:76)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread.handleReceiver(ActivityThread.java:4772)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread.-$$Nest$mhandleReceiver(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread$H.handleMessage(ActivityThread.java:2436)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:107)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:232)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.os.Looper.loop(Looper.java:317)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat android.app.ActivityThread.main(ActivityThread.java:8705)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat java.lang.reflect.Method.invoke(Native Method)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.947  1664  1664 D StrictMode: \tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: StrictMode policy violation; ~duration=60 ms: android.os.strictmode.DiskWriteViolation", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onWriteToDisk(StrictMode.java:1639)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat libcore.io.BlockGuardOs.open(BlockGuardOs.java:272)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat libcore.io.ForwardingOs.open(ForwardingOs.java:563)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread$AndroidOs.open(ActivityThread.java:8591)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat libcore.io.IoBridge.open(IoBridge.java:560)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat java.io.FileOutputStream.<init>(FileOutputStream.java:259)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat java.io.FileOutputStream.<init>(FileOutputStream.java:208)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.createFileOutputStream(SharedPreferencesImpl.java:710)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.writeToFile(SharedPreferencesImpl.java:801)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$mwriteToFile(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$1.run(SharedPreferencesImpl.java:680)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.enqueueDiskWrite(SharedPreferencesImpl.java:699)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$menqueueDiskWrite(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$EditorImpl.commit(SharedPreferencesImpl.java:612)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat com.android.providers.contacts.ContactsUpgradeReceiver.onReceive(ContactsUpgradeReceiver.java:76)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread.handleReceiver(ActivityThread.java:4772)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread.-$$Nest$mhandleReceiver(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread$H.handleMessage(ActivityThread.java:2436)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:107)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:232)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.Looper.loop(Looper.java:317)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread.main(ActivityThread.java:8705)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat java.lang.reflect.Method.invoke(Native Method)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: StrictMode policy violation; ~duration=60 ms: android.os.strictmode.DiskReadViolation", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1683)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat libcore.io.BlockGuardOs.fstat(BlockGuardOs.java:194)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat libcore.io.ForwardingOs.fstat(ForwardingOs.java:261)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat libcore.io.IoBridge.open(IoBridge.java:563)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat java.io.FileOutputStream.<init>(FileOutputStream.java:259)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat java.io.FileOutputStream.<init>(FileOutputStream.java:208)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.createFileOutputStream(SharedPreferencesImpl.java:710)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.writeToFile(SharedPreferencesImpl.java:801)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$mwriteToFile(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$1.run(SharedPreferencesImpl.java:680)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.enqueueDiskWrite(SharedPreferencesImpl.java:699)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl.-$$Nest$menqueueDiskWrite(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.SharedPreferencesImpl$EditorImpl.commit(SharedPreferencesImpl.java:612)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat com.android.providers.contacts.ContactsUpgradeReceiver.onReceive(ContactsUpgradeReceiver.java:76)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread.handleReceiver(ActivityThread.java:4772)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread.-$$Nest$mhandleReceiver(Unknown Source:0)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread$H.handleMessage(ActivityThread.java:2436)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.Handler.dispatchMessage(Handler.java:107)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.Looper.loopOnce(Looper.java:232)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.os.Looper.loop(Looper.java:317)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat android.app.ActivityThread.main(ActivityThread.java:8705)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat java.lang.reflect.Method.invoke(Native Method)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.949  1664  1664 D StrictMode: \tat com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: StrictMode policy violation; ~duration=60 ms: android.os.strictmode.DiskWriteViolation", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat android.os.StrictMode$AndroidBlockGuardPolicy.onWriteToDisk(StrictMode.java:1639)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat libcore.io.BlockGuardOs.write(BlockGuardOs.java:447)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat libcore.io.ForwardingOs.write(ForwardingOs.java:943)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat libcore.io.IoBridge.write(IoBridge.java:646)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat java.io.FileOutputStream.write(FileOutputStream.java:432)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat com.android.internal.util.FastXmlSerializer.flushBytes(FastXmlSerializer.java:253)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat com.android.internal.util.FastXmlSerializer.flush(FastXmlSerializer.java:274)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat com.android.internal.util.FastXmlSerializer.endDocument(FastXmlSerializer.java:219)", "after": {"scenario": "start"}, "t": 0.2}
- strictmode: {"line": "11-25 14:59:59.952  1664  1664 D StrictMode: \tat com.android.internal.util.XmlSerializerWrapper.endDocument(XmlSerializerWrapper.java:69)", "after": {"scenario": "start"}, "t": 0.2}

… (511 more lines in crawl-api35/findings.md)

---

<!-- desktop/desktop-findings.md -->
# Deep test — desktop

Sessions: nokey, gemini, openai, claude, twokeys; 164 screenshots; 11 min

Coverage: {"nokey":{"clicked":56,"typed":27,"screens":68,"overlays":15},"gemini":{"clicked":55,"typed":23,"screens":67,"overlays":16},"openai":{"clicked":0,"typed":0,"screens":12,"overlays":0},"claude":{"clicked":0,"typed":0,"screens":10,"overlays":0},"twokeys":{"clicked":0,"typed":0,"screens":7,"overlays":0}}

## main-stderr (51)
- ×10 [nokey] [2583:1125/194808.679093:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0702000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: start
- ×9 [gemini] [2822:1125/195055.133387:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: destructive "Remove" on me (nokey)
- ×6 [nokey] [2583:1125/194808.731018:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: start
- ×3 [gemini] [2822:1125/195055.178807:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: destructive "Remove" on me (nokey)
- ×3 [openai] [3100:1125/195521.247626:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A070200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: sheet primary "Save" (gemini)
- ×3 [openai,claude,twokeys] [3100:1125/195654.602313:WARNING:connection.cc(58)] X error received.  Request: Shm::PutImageRequest, Error: DrawableError{.sequence = 5828, .bad_value = 2097155, .minor_opcode = 3, .major_opcode = 130}
    after: ask, then Esc while waiting (openai)
- ×3 [claude] [3252:1125/195655.798505:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0702000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (openai)
- ×3 [twokeys] [3405:1125/195816.009351:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (claude)
- ×2 [nokey] [2583:1125/194902.569079:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0B08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: click "Speak" [div#go.round.go] on atlas (nokey)
- ×2 [nokey] [2583:1125/194903.239043:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: click "Talk to Atlas" [div.orb] on atlas (nokey)
- ×1 [nokey] [2583:1125/194907.130270:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0C07C01EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: click "" [div.orb] on atlas (nokey)
- ×1 [openai] [3100:1125/195521.289917:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A0200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: sheet primary "Save" (gemini)
- ×1 [openai] [3100:1125/195524.265210:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0F0870064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: sheet primary "Save" (gemini)
- ×1 [claude] [3252:1125/195655.843836:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (openai)
- ×1 [claude] [3252:1125/195700.683323:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (openai)
- ×1 [twokeys] [3405:1125/195816.055514:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (claude)
- ×1 [twokeys] [3405:1125/195819.021050:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0C086000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (claude)

## console-warning (51)
- ×10 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0702000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: start
- ×9 [gemini] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: destructive "Remove" on me (nokey)
- ×6 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: start
- ×3 [gemini] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: destructive "Remove" on me (nokey)
- ×3 [gemini] The ScriptProcessorNode is deprecated. Use AudioWorkletNode instead. (https://bit.ly/audio-worklet) (voice.js:397)
    after: HUD click "Talk to Atlas (Space)" (gemini)
- ×3 [openai] [GroupMarkerNotSet(crbug.com/242999)!:A070200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: sheet primary "Save" (gemini)
- ×3 [claude] [GroupMarkerNotSet(crbug.com/242999)!:A0702000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (openai)
- ×3 [twokeys] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (claude)
- ×2 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0B08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: click "Speak" [div#go.round.go] on atlas (nokey)
- ×2 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: click "Talk to Atlas" [div.orb] on atlas (nokey)
- ×1 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0C07C01EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: click "" [div.orb] on atlas (nokey)
- ×1 [openai] [GroupMarkerNotSet(crbug.com/242999)!:A0A0200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: sheet primary "Save" (gemini)
- ×1 [openai] [GroupMarkerNotSet(crbug.com/242999)!:A0F0870064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: sheet primary "Save" (gemini)
- ×1 [claude] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (openai)
- ×1 [claude] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (openai)
- ×1 [twokeys] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (claude)
- ×1 [twokeys] [GroupMarkerNotSet(crbug.com/242999)!:A0C086000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (claude)

## Replies for each stand-in answer

- **gemini:ok** → "Stub reply: all good. Привет 👋\n\nGemini\nCopy\nRemember\nAsk Again" (0085-gemini-reply-ok.png)
- **gemini:md** → "Bold, italic, code and a link.\n\nDay\tPlan\nMon\tGym 🏋️\nTue\tRead 📚\nÜnïcødé: Ἀθηνᾶ, 漢字, العربية (right to left), עברית, हिन्दी, 👍🏽👨‍👩‍👧‍👦\nA long unbroken word: SupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilis" (0086-gemini-reply-md.png)
- **gemini:long** → "This is a very long answer to check scrolling, wrapping and memory. This is a very long answer to check scrolling, wrapping and memory. This is a very long answer to check scrolling, wrapping and memory. This is a very long answer to check scrolling, wrapping and memory. This is a very long answer t" (0087-gemini-reply-long.png)
- **gemini:429** → "Gemini asked me to wait 37 s (1 of 3)\nGemini" (0088-gemini-reply-429.png)
- **gemini:500** → "Gemini is busy, trying again (3 of 3)\nGemini" (0089-gemini-reply-500.png)
- **gemini:503** → "Gemini is busy, trying again (3 of 3)\nGemini" (0090-gemini-reply-503.png)
- **gemini:400key** → "That Gemini key isn't valid. (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0091-gemini-reply-400key.png)
- **gemini:403** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0092-gemini-reply-403.png)
- **gemini:html502** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0093-gemini-reply-html502.png)
- **gemini:malformed** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0094-gemini-reply-malformed.png)
- **gemini:empty** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0095-gemini-reply-empty.png)
- **gemini:maxtokens** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0096-gemini-reply-maxtokens.png)
- **gemini:slow** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0097-gemini-reply-slow.png)
- **gemini:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0098-gemini-esc-stop.png)
- **openai:ok** → "Stub reply: all good. Привет 👋\n\nOpenAI\nCopy\nRemember\nAsk Again" (0137-openai-reply-ok.png)
- **openai:md** → "Bold, italic, code and a link.\n\nDay\tPlan\nMon\tGym 🏋️\nTue\tRead 📚\nÜnïcødé: Ἀθηνᾶ, 漢字, العربية (right to left), עברית, हिन्दी, 👍🏽👨‍👩‍👧‍👦\nA long unbroken word: SupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilis" (0138-openai-reply-md.png)
- **openai:429** → "Waiting for OpenAI's limit, 15 s (2 of 3)\nOpenAI" (0139-openai-reply-429.png)
- **openai:500** → "OpenAI is busy, trying again (3 of 3)\nOpenAI" (0140-openai-reply-500.png)
- **openai:400key** → "OpenAI turned the key down: Incorrect API key provided: sk-deep****. You can find your API key at https://platform.openai.com/account/api-keys. (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0141-openai-reply-400key.png)
- **openai:html502** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0142-openai-reply-html502.png)
- **openai:malformed** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0143-openai-reply-malformed.png)
- **openai:empty** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0144-openai-reply-empty.png)
- **openai:maxtokens** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0145-openai-reply-maxtokens.png)
- **openai:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0146-openai-esc-stop.png)
- **claude:ok** → "Stub reply: all good. Привет 👋\n\nClaude\nCopy\nRemember\nAsk Again" (0149-claude-reply-ok.png)
- **claude:429** → "Waiting for Claude's limit, 15 s (2 of 3)\nClaude" (0150-claude-reply-429.png)
- **claude:503** → "Claude is having trouble right now (529).\n\nCopy\nRemember\nAsk Again" (0151-claude-reply-503.png)
- **claude:400key** → "Claude turned the key down: invalid x-api-key (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0152-claude-reply-400key.png)
- **claude:malformed** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0153-claude-reply-malformed.png)
- **claude:empty** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0154-claude-reply-empty.png)
- **claude:maxtokens** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0155-claude-reply-maxtokens.png)
- **claude:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0156-claude-esc-stop.png)
- **twokeys:429** → "Gemini asked me to wait 37 s (1 of 3)\nGemini" (0159-twokeys-reply-429.png)
- **twokeys:ok** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0162-twokeys-reply-ok.png)
- **twokeys:400key** → "That Gemini key isn't valid. (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0161-twokeys-reply-400key.png)
- **twokeys:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0163-twokeys-esc-stop.png)

## Destructive buttons

- nokey bots: "Remove From List" — not found again ()
- nokey me: "9999••••9999 In use Try Again Remove" — nothing visible happened (0066-nokey-danger-9999-9999-In-use-Try-Again-Remove.png)
- nokey me: "Remove" — asked first (0067-nokey-danger-Remove.png)
- gemini atlas: "Remove" — not found again ()
- gemini bots: "Remove From List" — not found again ()
- gemini me: "AIza••••0000 In use Try Again Remove" — not found again ()

---

<!-- desktop/eslint-findings.md -->
# ESLint (eslint:recommended + bug checks) over desktop/

Totals: no-unmodified-loop-condition error ×10, no-control-regex error ×9, no-irregular-whitespace error ×3, no-useless-escape error ×1, no-use-before-define warning ×69, no-promise-executor-return warning ×18, no-unused-vars warning ×14

## error · no-control-regex (9)
- desktop/botbrowser.js:230 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `const t = String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 50).trim();`
- desktop/mail.js:210 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `const name = String(a.filename || `attachment-${index}`).replace(/[\\/:*?"<>|\x00-\x1f]+/g, '_').replace(/^\.+/, '').slice(0, 120) || 'attachment';`
- desktop/projectrules.js:138 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `if (!s || s.length > 400 || escapes(s) || /[\x00-\x1f<>:"|?*]/.test(s)) return null;`
- desktop/projectrules.js:147 — Unexpected control character(s) in regular expression: \x00, \x1f.
    `const t = String(s || '').replace(/[<>:"/\\|?*\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().replace(/^[.\s]+|[.\s]+$/g, '').slice(0, 60).trim();`
- desktop/projects.js:122 — Unexpected control character(s) in regular expression: \x1b.
    `const stripAnsi = (s) => String(s).replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '').replace(/\r(?!\n)/g, '\n');`
- desktop/renderer/md.js:23 — Unexpected control character(s) in regular expression: \x01, \x01.
    `t = t.replace(/\u0001(\d+)\u0001/g, (_m, i) => links[+i]);`
- desktop/renderer/md.js:24 — Unexpected control character(s) in regular expression: \x00, \x00.
    `return t.replace(/\u0000(\d+)\u0000/g, (_m, i) => `<code>${codes[+i]}</code>`).replace(/\u0002(\d+)\u0002/g, (_m, i) => lits[+i]);`
- desktop/renderer/md.js:24 — Unexpected control character(s) in regular expression: \x02, \x02.
    `return t.replace(/\u0000(\d+)\u0000/g, (_m, i) => `<code>${codes[+i]}</code>`).replace(/\u0002(\d+)\u0002/g, (_m, i) => lits[+i]);`
- desktop/sheets.js:67 — Unexpected control character(s) in regular expression: \x00, \x08, \x0b, \x0c, \x0e, \x1f.
    `const x = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])).replace(/[\u0000-\u0008\u000B\u000C\u000E-\`

## error · no-irregular-whitespace (3)
- desktop/mailtext.js:74 — Irregular whitespace not allowed.
    `s = decodeEntities(s).replace(/ /g, ' ').replace(/\r/g, '');`
- desktop/renderer/brain.js:395 — Irregular whitespace not allowed.
    `function strip(s) { return String(s).replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(`
- desktop/sheets.js:8 — Irregular whitespace not allowed.
    `const s = String(text || '').replace(/^﻿/, '');`

## error · no-unmodified-loop-condition (10)
- desktop/projects.js:140 — 'active' is not modified in this loop.
    `while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));`
- desktop/projects.js:140 — 'MAX_ACTIVE' is not modified in this loop.
    `while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));`
- desktop/remote.js:96 — 'ready' is not modified in this loop.
    `function flush() { while (ready && proc && queue.length) proc.stdin.write(queue.shift() + '\n'); }`
- desktop/remote.js:96 — 'proc' is not modified in this loop.
    `function flush() { while (ready && proc && queue.length) proc.stdin.write(queue.shift() + '\n'); }`
- desktop/renderer/linkcore.js:121 — 'running' is not modified in this loop.
    `while (running) {`
- desktop/renderer/linkwire.js:255 — 'link' is not modified in this loop.
    `while (!s.stopped && link) {`
- desktop/renderer/linkwire.js:318 — 'ended' is not modified in this loop.
    `while (!ended && link) {`
- desktop/renderer/linkwire.js:318 — 'link' is not modified in this loop.
    `while (!ended && link) {`
- desktop/renderer/sync.js:324 — 'inflight' is not modified in this loop.
    `while (inflight) { try { await inflight; } catch {} }`
- desktop/renderer/sync.js:423 — 'blocked' is not modified in this loop.
    `while (!blocked) {`

## error · no-useless-escape (1)
- desktop/pc.js:65 — Unnecessary escape character: \[.
    `function sendKeysText(t) { return String(t).replace(/[+^%~(){}\[\]]/g, (c) => '{' + c + '}').replace(/\r?\n/g, '{ENTER}'); }`

## warning · no-promise-executor-return (18)
- desktop/atlasvoice.js:77 — Return values from promise executor functions cannot be read.
    `const run = (cmd, args, ms = 120_000) => new Promise((res) => execFile(cmd, args, { timeout: ms, windowsHide: true }, (err, out, errOut) => res({ ok: !err, out:`
- desktop/attach.js:40 — Return values from promise executor functions cannot be read.
    `if (total + RESERVE > free) return reject(new Error(`Not enough room on this PC for “${name}” (${(total / MB).toFixed(1)} MB).`));`
- desktop/attach.js:82 — Return values from promise executor functions cannot be read.
    `if (!e.csize) return resolve(Buffer.alloc(0));`
- desktop/attach.js:235 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, 3000));`
- desktop/botbrowser.js:14 — Return values from promise executor functions cannot be read.
    `const sleep = (ms) => new Promise((r) => setTimeout(r, ms));`
- desktop/botfiles.js:135 — Return values from promise executor functions cannot be read.
    `await Promise.race([win.webContents.executeJavaScript('document.fonts ? document.fonts.ready.then(() => 1) : 1', true).catch(() => {}), new Promise((r) => setTi`
- desktop/main.js:46 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, Number(process.env.ATLAS_WAIT || 2500)));`
- desktop/pc.js:17 — Return values from promise executor functions cannot be read.
    `if (!WIN) return resolve({ code: -1, out: '', err: 'This only works on Windows.' });`
- desktop/projects.js:140 — Return values from promise executor functions cannot be read.
    `while (running.has(dir) || active >= MAX_ACTIVE) await new Promise((r) => setTimeout(r, 300));`
- desktop/projects.js:312 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, 4000));`
- desktop/remote.js:99 — Return values from promise executor functions cannot be read.
    `if (!WIN) return resolve('This only works on Windows.');`
- desktop/renderer/app.js:507 — Return values from promise executor functions cannot be read.
    `if (!cancelled && !followUp) { o.querySelector('.heard').textContent = "I didn't catch that."; await new Promise((r) => setTimeout(r, 1200)); }`
- desktop/renderer/bots.js:811 — Return values from promise executor functions cannot be read.
    `idle: async () => { while (workers.size) await new Promise((r) => setTimeout(r, 5)); await saving; },`
- desktop/renderer/brain.js:639 — Return values from promise executor functions cannot be read.
    `const sleep = (ms) => new Promise((r) => setTimeout(r, ms));`
- desktop/renderer/linkcore.js:61 — Return values from promise executor functions cannot be read.
    `const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));`
- desktop/renderer/linkwire.js:261 — Return values from promise executor functions cannot be read.
    `try { await link.putFrame(++s.seq, { sid, w: f.w, h: f.h, realW: f.realW, realH: f.realH, jpg: f.jpg }); } catch { await new Promise((r) => setTimeout(r, 800));`
- desktop/renderer/linkwire.js:264 — Return values from promise executor functions cannot be read.
    `await new Promise((r) => setTimeout(r, Math.max(120, 350 - (Date.now() - t0))));   // at most about 3 frames a second`
- desktop/renderer/linkwire.js:323 — Return values from promise executor functions cannot be read.
    `} catch { await new Promise((res) => setTimeout(res, 700)); }`

## warning · no-unused-vars (14)
- desktop/remote.js:9 — 'path' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const path = require('path');`
- desktop/renderer/app.js:400 — 'videoSeconds' is defined but never used. Allowed unused vars must match /^_/u.
    `function videoSeconds(a) {`
- desktop/renderer/bots.js:10 — 'planProgress' is defined but never used. Allowed unused vars must match /^_/u.
    `import { parsePlanTree, planText, planProgress, missionDefaults, allows as crewAllows, capabilityOf, narrowAccess, budgetCheck, usageToday, dayOf, agentSlice,`
- desktop/renderer/botwire.js:7 — 'MAX_TOOL_CALLS' is defined but never used. Allowed unused vars must match /^_/u.
    `import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';`
- desktop/renderer/botwire.js:7 — 'callsOf' is defined but never used. Allowed unused vars must match /^_/u.
    `import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';`
- desktop/renderer/botwire.js:7 — 'msOf' is defined but never used. Allowed unused vars must match /^_/u.
    `import { createBots, MAX_TOOL_CALLS, MAX_WORK_MS, MAX_DEPTH, callsOf, msOf } from './bots.js';`
- desktop/renderer/connui.js:10 — 'toast' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { h, esc, sheet, toast, render, settingRow } = ui;`
- desktop/renderer/linkwire.js:309 — 'frame' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `let seq = -1, ended = false, frame = null;`
- desktop/renderer/selfwire.js:141 — 'now' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const st = state(); const now = Date.now();`
- desktop/renderer/store.js:5 — 'MIN' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const MIN = 60000;`
- desktop/renderer/trade.js:136 — 'h' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { h, esc, sheet, toast, render } = ui;`
- desktop/renderer/trade.js:207 — 'h' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { h, esc, sheet, toast, render } = ui;`
- desktop/renderer/when.js:91 — 'used' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `while ((x = re.exec(rest))) { const v = +x[1], u = x[2][0]; total += u === 'h' ? v * 60 : u === 'd' ? v * 1440 : v; used += x[0]; }`
- desktop/trading.js:6 — 'BrowserWindow' is assigned a value but never used. Allowed unused vars must match /^_/u.
    `const { app, safeStorage, dialog, Notification, BrowserWindow } = require('electron');`

## warning · no-use-before-define (69)
- desktop/attach.js:132 — 'size' was used before it was defined.
    `return { count: all.length, lines: all.slice(0, 300).map((e) => `  ${e.name}  (${size(e.size)})`).join('\n') + (all.length > 300 ? `\n  …and ${all.length - 300}`
- desktop/pc.js:23 — 't' was used before it was defined.
    `const finish = (r) => { if (done) return; done = true; clearTimeout(t); clearTimeout(late); resolve(r); };`
- desktop/pc.js:23 — 'late' was used before it was defined.
    `const finish = (r) => { if (done) return; done = true; clearTimeout(t); clearTimeout(late); resolve(r); };`
- desktop/remote.js:88 — 'waiting' was used before it was defined.
    `const w = waiting.shift(); if (w) w(line.startsWith('err') ? line.slice(4) : null);`
- desktop/remote.js:91 — 'waiting' was used before it was defined.
    `proc.on('exit', () => { proc = null; ready = false; for (const w of waiting.splice(0)) w('input stopped'); });`
- desktop/renderer/app.js:377 — 'MAX_ITEMS' was used before it was defined.
    `if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);`
- desktop/renderer/app.js:377 — 'MAX_ITEMS' was used before it was defined.
    `if (attachments.length >= MAX_ITEMS) return toast(`Up to ${MAX_ITEMS} per message.`);`
- desktop/renderer/app.js:1012 — 'iv' was used before it was defined.
    `const x = pref(F); if (!x) { clearInterval(iv); return; }`
- desktop/renderer/app.js:1014 — 'resume' was used before it was defined.
    `if (x.pausedAt && x.breakUntil && x.breakUntil <= now) { resume(); chime(); return; }`
- desktop/renderer/app.js:1033 — 'iv' was used before it was defined.
    `clearInterval(iv); o.remove(); host.fullscreen(false); toast(mins >= 1 ? `Session done: ${mins} min. Nice work.` : 'Session ended.'); if (tab === 'log') render(`
- desktop/renderer/autonomyui.js:38 — 'timer' was used before it was defined.
    `const done = (v) => { clearInterval(tick); clearTimeout(timer); el.remove(); resolve(v); };`
- desktop/renderer/bots.js:219 — 'job' was used before it was defined.
    `const rootOf = (j) => { let r = j, n = 0; while (r.parent && job(r.parent) && n++ < 50) r = job(r.parent); return r; };`
- desktop/renderer/bots.js:219 — 'job' was used before it was defined.
    `const rootOf = (j) => { let r = j, n = 0; while (r.parent && job(r.parent) && n++ < 50) r = job(r.parent); return r; };`
- desktop/renderer/bots.js:223 — 'job' was used before it was defined.
    `const treeSize = (j) => { const r = rootOf(j); const seen = new Set(); const walk = (x) => { for (const id of x.children || []) { const k = job(id); if (k && !s`
- desktop/renderer/botwire.js:78 — 'extras' was used before it was defined.
    `const b = extras.data.specialists.find((s) => s.id === id);`
- desktop/renderer/botwire.js:79 — 'usage' was used before it was defined.
    `const c = budgetCheck(b && b.budget, usageToday(usage.get(id), dayOf(Date.now())));`
- desktop/renderer/botwire.js:86 — 'extras' was used before it was defined.
    `const sk = skillsIndex(extras.data.skills); if (sk) out.push(sk);`
- desktop/renderer/botwire.js:87 — 'extras' was used before it was defined.
    `if ((j.depth || 0) < MAX_DEPTH) { const t = teamIndex(extras.data.specialists.filter((s) => s.id !== j.specialist)); if (t) out.push(t + '\nHand a part to one w`
- desktop/renderer/botwire.js:88 — 'extras' was used before it was defined.
    `const r = rulesPrompt(extras.data.policy); if (r) out.push(r);`
- desktop/renderer/botwire.js:89 — 'extras' was used before it was defined.
    `if (j.routine) { const rt = extras.data.routines.find((x) => x.id === j.routine); if (rt) out.push(`This job is the routine "${rt.name}" (${describeSchedule(rt.`
- desktop/renderer/botwire.js:90 — 'extras' was used before it was defined.
    `const sp = j.specialist ? extras.data.specialists.find((s) => s.id === j.specialist) : null;`
- desktop/renderer/botwire.js:109 — 'bots' was used before it was defined.
    `fire: (r) => bots.deploy(r.goal, `This is the routine "${r.name}", which runs ${describeSchedule(r.schedule).toLowerCase()}.`, { specialist: r.specialist || nul`
- desktop/renderer/botwire.js:239 — 'trigRun' was used before it was defined.
    `nextTriggerAt: (botId, trigId) => trigRun.nextAt(botId, trigId),`
- desktop/renderer/botwire.js:240 — 'trigRun' was used before it was defined.
    `triggerProblem: (botId, trigId) => trigRun.problem(botId, trigId),`
- desktop/renderer/brain.js:23 — 'modelOf' was used before it was defined.
    `const id = modelOf(p), base = baseOf(p);`
- desktop/renderer/brain.js:23 — 'baseOf' was used before it was defined.
    `const id = modelOf(p), base = baseOf(p);`
- desktop/renderer/brain.js:32 — 'dontSwitch' was used before it was defined.
    `const other = !dontSwitch() && PROVIDERS.find((x) => !x.local && readyKey(x.id));`
- desktop/renderer/brain.js:32 — 'PROVIDERS' was used before it was defined.
    `const other = !dontSwitch() && PROVIDERS.find((x) => !x.local && readyKey(x.id));`
- desktop/renderer/brain.js:33 — 'convo' was used before it was defined.
    `if (other) { convo.status = `${modelOf(p)} is too big for this PC right now: using ${other.name}`; changed('chat'); return other; }`
- desktop/renderer/brain.js:33 — 'modelOf' was used before it was defined.
    `if (other) { convo.status = `${modelOf(p)} is too big for this PC right now: using ${other.name}`; changed('chat'); return other; }`
- desktop/renderer/brain.js:34 — 'dontSwitch' was used before it was defined.
    `throw new Failure(v.reason + (dontSwitch() ? ' (Don\'t Switch Services is on, so I stayed with it.)' : ''));`
- desktop/renderer/brain.js:69 — 'byId' was used before it was defined.
    `let id = 'c_' + Date.now().toString(36); while (byId(id)) id += Math.floor(Math.random() * 36).toString(36);`
- desktop/renderer/brain.js:71 — 'byId' was used before it was defined.
    `saveCustoms([...mineOnly(), p]); return byId(p.id);`
- desktop/renderer/brain.js:141 — 'dontSwitch' was used before it was defined.
    `if (chosen && available(chosen)) return readyKey(chosen.id) || dontSwitch() ? chosen : fallbacks(chosen.id)[0] || chosen;`
- desktop/renderer/brain.js:180 — 'TOOLS' was used before it was defined.
    `export function session(p, key, history, user, media, tools = TOOLS) {`
- desktop/renderer/crewui.js:287 — 'PRESETS' was used before it was defined.
    `${b.builtIn ? '' : `<div class="chips" id="bud" style="margin-top:12px">${PRESETS.map(([n, p]) => `<button class="chip sm${b.budget.callsPerDay === p.callsPerDa`
- desktop/renderer/crewui.js:291 — 'PRESETS' was used before it was defined.
    `today.querySelectorAll('#bud [data-n]').forEach((x) => (x.onclick = () => { crew.updateBot(b.id, { budget: { ...b.budget, ...PRESETS.find(([n]) => n === x.datas`
- desktop/renderer/crewui.js:318 — 'HOME_LABELS' was used before it was defined.
    `const where = k.h(`<div class="card" style="margin-top:12px"><div class="small muted" style="font-weight:600;margin-bottom:8px">WHERE IT WORKS</div><div class="`
- desktop/renderer/crewui.js:412 — 'wireSrc' was used before it was defined.
    `o.querySelector('#tf').innerHTML = fields[type]; wireSrc(o);`
- desktop/renderer/crewui.js:414 — 'wireSrc' was used before it was defined.
    `wireSrc(o);`
- desktop/renderer/crewui.js:545 — 'flatten' was used before it was defined.
    `const total = (t ? flatten(t) : [j]).reduce((s, m) => ({ calls: s.calls + ((m.cost || {}).calls || 0), tin: s.tin + ((m.cost || {}).tokensIn || 0), tout: s.tout`
- desktop/renderer/crewui.js:546 — 'fmtTok' was used before it was defined.
    `col.appendChild(k.h(`<div class="small muted" style="margin-top:12px">Cost: ${total.calls} AI calls · ${fmtTok(total.tin)} in / ${fmtTok(total.tout)} out${t && `
- desktop/renderer/crewui.js:546 — 'fmtTok' was used before it was defined.
    `col.appendChild(k.h(`<div class="small muted" style="margin-top:12px">Cost: ${total.calls} AI calls · ${fmtTok(total.tin)} in / ${fmtTok(total.tout)} out${t && `
- desktop/renderer/hud.js:20 — 'root' was used before it was defined.
    `const $ = (sel) => root && root.querySelector(sel);`
- desktop/renderer/hud.js:20 — 'root' was used before it was defined.
    `const $ = (sel) => root && root.querySelector(sel);`
- desktop/renderer/hud.js:105 — 'offChange' was used before it was defined.
    `offChange = onChange((what) => {`
- desktop/renderer/hud.js:112 — 'ticker' was used before it was defined.

… (46 more lines in desktop/eslint-findings.md)

---

<!-- lint/lint-findings.md -->
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

… (60 more lines in lint/lint-findings.md)

---

<!-- monkey-api34/findings.md -->
# Deep test — monkey

abi: x86_64,arm64-v8a
sdk: 34
release: 14
tz: Africa/Lagos
translation: libndk_translation.so
Physical size: 320x640
Physical density: 160
    versionCode=25 minSdk=33 targetSdk=35
    versionName=0.11.4

Atlas processes seen: ['com.anjy.atlas', 'com.anjy.atlas:hotword']

## Crashes (15)

11-25 21:00:56.176  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 4743
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=219.771, y[0]=56.5459, toolType[0]=UNKNOWN, id[1]=1, x[1]=90.1592, y[1]=403.153, toolType[1]=UNKNOWN, pointerCount=2, eventTime=79353000000, downTime=79351000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-238039370}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2667)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
	at android.view.View.dispatchTouchEvent(View.java:15655)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
	at android.view.View.dispatchPointerEvent(View.java:15919)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
```

11-25 21:01:33.635  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 5290
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=297.873, toolType[0]=UNKNOWN, id[1]=1, x[1]=209.82, y[1]=267.634, toolType[1]=UNKNOWN, pointerCount=2, eventTime=116818000000, downTime=116810000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-292729973}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2667)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
	at android.view.View.dispatchTouchEvent(View.java:15655)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
	at android.view.View.dispatchPointerEvent(View.java:15919)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
```

11-25 21:02:09.790  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 6194
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=236.509, y[0]=416.296, toolType[0]=UNKNOWN, id[1]=1, x[1]=211.256, y[1]=517.384, toolType[1]=UNKNOWN, pointerCount=2, eventTime=152972000000, downTime=152967000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-160819299}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2667)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
	at android.view.View.dispatchTouchEvent(View.java:15655)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
	at android.view.View.dispatchPointerEvent(View.java:15919)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
```

11-25 21:02:15.080  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 6423
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=200, y[0]=140.451, toolType[0]=UNKNOWN, id[1]=1, x[1]=290.913, y[1]=552, toolType[1]=UNKNOWN, pointerCount=2, eventTime=158251000000, downTime=158248000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-393442786}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2667)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
	at android.view.View.dispatchTouchEvent(View.java:15655)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
	at android.view.View.dispatchPointerEvent(View.java:15919)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
```

11-25 21:03:17.235  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 6504
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=2.65332, y[0]=165.092, toolType[0]=UNKNOWN, id[1]=1, x[1]=0, y[1]=31.3105, toolType[1]=UNKNOWN, pointerCount=2, eventTime=220420000000, downTime=220418000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-184074314}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2667)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
	at android.view.View.dispatchTouchEvent(View.java:15655)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
	at android.view.View.dispatchPointerEvent(View.java:15919)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
```

11-25 21:03:29.094  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 6937
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=206.592, y[0]=339.038, toolType[0]=UNKNOWN, id[1]=1, x[1]=49.3643, y[1]=232.77, toolType[1]=UNKNOWN, pointerCount=2, eventTime=232277000000, downTime=232274000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-27531789}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2667)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
	at android.view.View.dispatchTouchEvent(View.java:15655)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
	at android.view.View.dispatchPointerEvent(View.java:15919)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
```


… (791 more lines in monkey-api34/findings.md)

---

<!-- monkey-api35/findings.md -->
# Deep test — monkey

abi: x86_64,arm64-v8a
sdk: 35
release: 15
tz: Europe/London
translation: libndk_translation.so
Physical size: 320x640
Physical density: 160
    versionCode=25 minSdk=33 targetSdk=35
    versionName=0.11.4

Atlas processes seen: ['com.anjy.atlas', 'com.anjy.atlas:hotword']

## Crashes (7)

11-25 20:01:25.475  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 3777
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=9.28125, y[0]=282.388, toolType[0]=UNKNOWN, id[1]=1, x[1]=228.949, y[1]=260.329, toolType[1]=UNKNOWN, pointerCount=2, eventTime=125172000000, downTime=125165000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf1926e68}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2795)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
	at android.view.View.performOnTouchCallback(View.java:16447)
	at android.view.View.dispatchTouchEvent(View.java:16404)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
	at android.view.View.dispatchPointerEvent(View.java:16729)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
```

11-25 20:01:39.439  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 4477
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=361.873, toolType[0]=UNKNOWN, id[1]=1, x[1]=209.82, y[1]=331.634, toolType[1]=UNKNOWN, pointerCount=2, eventTime=139137000000, downTime=139124000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf7af499d}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2795)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
	at android.view.View.performOnTouchCallback(View.java:16447)
	at android.view.View.dispatchTouchEvent(View.java:16404)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
	at android.view.View.dispatchPointerEvent(View.java:16729)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
```

11-25 20:03:40.596  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 4590
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=0, y[0]=333.673, toolType[0]=UNKNOWN, id[1]=1, x[1]=85.9473, y[1]=503.173, toolType[1]=UNKNOWN, pointerCount=2, eventTime=260293000000, downTime=260289000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xe47ec9d4}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2795)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
	at android.view.View.performOnTouchCallback(View.java:16447)
	at android.view.View.dispatchTouchEvent(View.java:16404)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
	at android.view.View.dispatchPointerEvent(View.java:16729)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
```

11-25 20:06:00.039  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 5798
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=32.3125, y[0]=-4.98926, toolType[0]=UNKNOWN, id[1]=1, x[1]=81.7422, y[1]=237.963, toolType[1]=UNKNOWN, pointerCount=2, eventTime=399738000000, downTime=399733000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xd782f774}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2795)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
	at android.view.View.performOnTouchCallback(View.java:16447)
	at android.view.View.dispatchTouchEvent(View.java:16404)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
	at android.view.View.dispatchPointerEvent(View.java:16729)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
```

11-25 20:07:01.272  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 5909
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=161.03, y[0]=427.115, toolType[0]=UNKNOWN, id[1]=1, x[1]=151.76, y[1]=351.517, toolType[1]=UNKNOWN, pointerCount=2, eventTime=460968000000, downTime=460964000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xe487dc7f}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2795)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
	at android.view.View.performOnTouchCallback(View.java:16447)
	at android.view.View.dispatchTouchEvent(View.java:16404)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
	at android.view.View.dispatchPointerEvent(View.java:16729)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
```

11-25 20:07:49.065  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 6238
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=507, toolType[0]=UNKNOWN, id[1]=1, x[1]=22.7715, y[1]=-133, toolType[1]=UNKNOWN, pointerCount=2, eventTime=508758000000, downTime=508742000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf5e46458}
	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
	at android.view.MotionEvent.getY(MotionEvent.java:2795)
	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
	at android.view.View.performOnTouchCallback(View.java:16447)
	at android.view.View.dispatchTouchEvent(View.java:16404)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
	at android.view.View.dispatchPointerEvent(View.java:16729)
	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
```


… (316 more lines in monkey-api35/findings.md)
