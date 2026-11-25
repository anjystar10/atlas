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

11-25 20:10:59.668  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 7937
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=240.418, y[0]=552.795, toolType[0]=UNKNOWN, id[1]=1, x[1]=178.19, y[1]=168.291, toolType[1]=UNKNOWN, pointerCount=2, eventTime=699367000000, downTime=699343000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf869cddd}
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

## ANRs (0, traces 0)

## Native crashes (0; known emulator SIGILL in the wake word engine: 0)

## Monkey

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=9.28125, y[0]=282.388, toolType[0]=UNKNOWN, id[1]=1, x[1]=228.949, y[1]=260.329, toolType[1]=UNKNOWN, pointerCount=2, eventTime=125172000000, downTime=125165000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf1926e68}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=9.28125, y[0]=282.388, toolType[0]=UNKNOWN, id[1]=1, x[1]=228.949, y[1]=260.329, toolType[1]=UNKNOWN, pointerCount=2, eventTime=125172000000, downTime=125165000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf1926e68}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=361.873, toolType[0]=UNKNOWN, id[1]=1, x[1]=209.82, y[1]=331.634, toolType[1]=UNKNOWN, pointerCount=2, eventTime=139137000000, downTime=139124000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf7af499d}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=361.873, toolType[0]=UNKNOWN, id[1]=1, x[1]=209.82, y[1]=331.634, toolType[1]=UNKNOWN, pointerCount=2, eventTime=139137000000, downTime=139124000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf7af499d}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=0, y[0]=333.673, toolType[0]=UNKNOWN, id[1]=1, x[1]=85.9473, y[1]=503.173, toolType[1]=UNKNOWN, pointerCount=2, eventTime=260293000000, downTime=260289000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xe47ec9d4}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=0, y[0]=333.673, toolType[0]=UNKNOWN, id[1]=1, x[1]=85.9473, y[1]=503.173, toolType[1]=UNKNOWN, pointerCount=2, eventTime=260293000000, downTime=260289000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xe47ec9d4}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```
- monkey-101.txt: 20000 events injected; ends: ## Network stats: elapsed time=308983ms (0ms mobile, 0ms wifi, 308983ms not connected) | // Monkey finished | exit 0

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=32.3125, y[0]=-4.98926, toolType[0]=UNKNOWN, id[1]=1, x[1]=81.7422, y[1]=237.963, toolType[1]=UNKNOWN, pointerCount=2, eventTime=399738000000, downTime=399733000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xd782f774}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=32.3125, y[0]=-4.98926, toolType[0]=UNKNOWN, id[1]=1, x[1]=81.7422, y[1]=237.963, toolType[1]=UNKNOWN, pointerCount=2, eventTime=399738000000, downTime=399733000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xd782f774}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=161.03, y[0]=427.115, toolType[0]=UNKNOWN, id[1]=1, x[1]=151.76, y[1]=351.517, toolType[1]=UNKNOWN, pointerCount=2, eventTime=460968000000, downTime=460964000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xe487dc7f}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=161.03, y[0]=427.115, toolType[0]=UNKNOWN, id[1]=1, x[1]=151.76, y[1]=351.517, toolType[1]=UNKNOWN, pointerCount=2, eventTime=460968000000, downTime=460964000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xe487dc7f}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=507, toolType[0]=UNKNOWN, id[1]=1, x[1]=22.7715, y[1]=-133, toolType[1]=UNKNOWN, pointerCount=2, eventTime=508758000000, downTime=508742000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf5e46458}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=507, toolType[0]=UNKNOWN, id[1]=1, x[1]=22.7715, y[1]=-133, toolType[1]=UNKNOWN, pointerCount=2, eventTime=508758000000, downTime=508742000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf5e46458}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```
- monkey-202.txt: 20000 events injected; ends: ## Network stats: elapsed time=290217ms (0ms mobile, 0ms wifi, 290217ms not connected) | // Monkey finished | exit 0

monkey-303.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=240.418, y[0]=552.795, toolType[0]=UNKNOWN, id[1]=1, x[1]=178.19, y[1]=168.291, toolType[1]=UNKNOWN, pointerCount=2, eventTime=699367000000, downTime=699343000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf869cddd}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
// Build Changelist: 12960925
// Build Time: 1737651906000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=240.418, y[0]=552.795, toolType[0]=UNKNOWN, id[1]=1, x[1]=178.19, y[1]=168.291, toolType[1]=UNKNOWN, pointerCount=2, eventTime=699367000000, downTime=699343000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=0xf869cddd}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
// 	at android.view.View.performOnTouchCallback(View.java:16447)
// 	at android.view.View.dispatchTouchEvent(View.java:16404)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3122)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2803)
```
- monkey-303.txt: 20000 events injected; ends: ## Network stats: elapsed time=278032ms (0ms mobile, 0ms wifi, 278032ms not connected) | // Monkey finished | exit 0

## Crawler events (0)

## Atlas's error lines (664, 86 kinds)
- ×57 11-25 20:00:54.845 [com.anjy.atlas] ImeBackDispatcher: Ime callback not found. Ignoring unregisterReceivedCallback. callbackId: 133214057
- ×49 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3116)
- ×49 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2789)
- ×44 11-25 20:00:56.444 [com.anjy.atlas] FrameTracker: force finish cuj, time out: J<IME_INSETS_HIDE_ANIMATION::1@0@com.anjy.atlas>
- ×40 11-25 20:00:49.611 [com.anjy.atlas] FrameTracker: force finish cuj, time out: J<IME_INSETS_SHOW_ANIMATION::0@0@com.anjy.atlas>
- ×21 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.deliver(ViewRootImpl.java:7106)
- ×14 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.onDeliverToNext(ViewRootImpl.java:7163)
- ×14 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.forward(ViewRootImpl.java:7129)
- ×14 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.apply(ViewRootImpl.java:7137)
- ×11 11-25 20:00:23.181 [com.anjy.atlas] SurfaceSyncGroup: Failed to receive transaction ready in 1000ms. Marking SurfaceSyncGroup(wmsSync-VRI[MainActivity]#0) as ready
- ×11 11-25 20:00:23.182 [com.anjy.atlas] SurfaceSyncGroup: Failed to receive transaction ready in 1000ms. Marking SurfaceSyncGroup(VRI[MainActivity]#1) as ready
- ×8 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.ViewRootImpl.<init>(ViewRootImpl.java:1246)
- ×7 11-25 20:01:25.470 [com.anjy.atlas] InputEventReceiver: Exception dispatching input event.
- ×7 11-25 20:01:25.470 [com.anjy.atlas] MessageQueue-JNI: Exception in MessageQueue callback: handleReceiveCallback
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.MotionEvent.getY(MotionEvent.java:2795)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:941)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.View.performOnTouchCallback(View.java:16447)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.View.dispatchTouchEvent(View.java:16404)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:458)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1980)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.app.Activity.dispatchTouchEvent(Activity.java:4533)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:416)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.View.dispatchPointerEvent(View.java:16729)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7947)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:7710)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$AsyncInputStage.forward(ViewRootImpl.java:7295)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$AsyncInputStage.apply(ViewRootImpl.java:7352)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl.deliverInputEvent(ViewRootImpl.java:10214)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl.doProcessInputEvents(ViewRootImpl.java:10165)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl.enqueueInputEvent(ViewRootImpl.java:10134)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$WindowInputEventReceiver.onInputEvent(ViewRootImpl.java:10356)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.InputEventReceiver.dispatchInputEvent(InputEventReceiver.java:295)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.MessageQueue.nativePollOnce(Native Method)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.MessageQueue.next(MessageQueue.java:346)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.Looper.loopOnce(Looper.java:189)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.Looper.loop(Looper.java:317)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at android.app.ActivityThread.main(ActivityThread.java:8705)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at java.lang.reflect.Method.invoke(Native Method)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)
- ×7 11-25 20:01:25.474 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)
- ×4 11-25 20:01:24.708 [com.anjy.atlas] RippleDrawable: The RippleDrawable.STYLE_PATTERNED animation is not supported for a non-hardware accelerated Canvas. Skipping animation.
- ×4 11-25 20:05:21.565 [com.anjy.atlas] FrameEvents: addRelease: Did not find frame.
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.WindowManagerGlobal.addView(WindowManagerGlobal.java:428)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.WindowManagerImpl.addView(WindowManagerImpl.java:158)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.app.Dialog.show(Dialog.java:352)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.View.performClick(View.java:8028)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.View.performClickInternal(View.java:8005)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.View.-$$Nest$mperformClickInternal(Unknown Source:0)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.view.View$PerformClick.run(View.java:31229)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.os.Handler.handleCallback(Handler.java:959)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.os.Handler.dispatchMessage(Handler.java:100)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.os.Looper.loopOnce(Looper.java:232)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.os.Looper.loop(Looper.java:317)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at android.app.ActivityThread.main(ActivityThread.java:8705)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at java.lang.reflect.Method.invoke(Native Method)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:580)
- ×4 11-25 20:07:08.199 [com.anjy.atlas] WindowManager: 	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:886)

## Exceptions printed to the log (0, 0 kinds)

## StrictMode (0)

## Leaks (4)
- ×1 11-25 20:07:08.199 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.CrewActivity has leaked window com.android.internal.policy.DecorView{d88ad0 V.E...... R....... 0,0-320,592}[CrewActivity] that was originally added here
- ×1 11-25 20:07:12.001 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.BotExtrasActivity has leaked window com.android.internal.policy.DecorView{edbda0c V.E...... R.....ID 0,0-306,592 aid=1073741833}[BotExtrasActivity] that was originally added here
- ×1 11-25 20:08:52.397 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.CrewActivity has leaked window com.android.internal.policy.DecorView{5def853 V.E...... R....... 0,0-320,592}[CrewActivity] that was originally added here
- ×1 11-25 20:13:42.763 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.CrewActivity has leaked window com.android.internal.policy.DecorView{6c61e1 V.E...... R.....ID 0,0-320,278}[CrewActivity] that was originally added here

## Frames: {'count': 6, 'max': 187, 'over_30': 6}

## Dropbox: {'data_app_crash': 1}
