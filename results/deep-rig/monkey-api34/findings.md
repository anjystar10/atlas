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

11-25 21:04:43.862  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 7030
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=136.743, y[0]=148.021, toolType[0]=UNKNOWN, id[1]=1, x[1]=224.533, y[1]=190.477, toolType[1]=UNKNOWN, pointerCount=2, eventTime=307041000000, downTime=307038000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-23128840}
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

11-25 21:07:04.339  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 8431
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=161.903, y[0]=350.87, toolType[0]=UNKNOWN, id[1]=1, x[1]=276.182, y[1]=79.9814, toolType[1]=UNKNOWN, pointerCount=2, eventTime=447521000000, downTime=447518000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-418663602}
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

11-25 21:07:13.573  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 9075
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=73.9873, y[0]=-22.3633, toolType[0]=UNKNOWN, id[1]=1, x[1]=143.86, y[1]=328.008, toolType[1]=UNKNOWN, pointerCount=2, eventTime=456748000000, downTime=456745000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-696480076}
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

11-25 21:08:17.215  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 9284
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=112.689, y[0]=38.6973, toolType[0]=UNKNOWN, id[1]=1, x[1]=150.544, y[1]=142.2, toolType[1]=UNKNOWN, pointerCount=2, eventTime=520398000000, downTime=520393000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-1064240399}
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

11-25 21:10:16.113  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 9667
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=183.211, y[0]=-13.2383, toolType[0]=UNKNOWN, id[1]=1, x[1]=308.99, y[1]=115.891, toolType[1]=UNKNOWN, pointerCount=2, eventTime=639292000000, downTime=639290000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-165094260}
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

11-25 21:10:21.411  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 10625
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=226.075, y[0]=297.601, toolType[0]=UNKNOWN, id[1]=1, x[1]=163.509, y[1]=25.2188, toolType[1]=UNKNOWN, pointerCount=2, eventTime=644593000000, downTime=644590000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-746667861}
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

11-25 21:11:56.745  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 10846
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=50.3652, y[0]=159.793, toolType[0]=UNKNOWN, id[1]=1, x[1]=169.251, y[1]=-88, toolType[1]=UNKNOWN, pointerCount=2, eventTime=739926000000, downTime=739924000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-184220942}
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

11-25 21:12:04.109  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 11628
java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=119.867, y[0]=221.23, toolType[0]=UNKNOWN, id[1]=1, x[1]=211.994, y[1]=547.025, toolType[1]=UNKNOWN, pointerCount=2, eventTime=747287000000, downTime=747284000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-721436574}
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

11-25 21:13:10.366  (×1)
```
FATAL EXCEPTION: main
Process: com.anjy.atlas, PID: 11706
java.lang.NullPointerException: Attempt to invoke virtual method 'boolean android.os.CancellationSignal.isCanceled()' on a null object reference
	at com.android.internal.view.ScrollCaptureViewSupport.onScrollCaptureImageRequest(ScrollCaptureViewSupport.java:240)
	at android.view.ScrollCaptureConnection.lambda$requestImage$1(ScrollCaptureConnection.java:149)
	at android.view.ScrollCaptureConnection.$r8$lambda$B-K9IOFMSTMXjr1AmzzeRKza8aI(Unknown Source:0)
	at android.view.ScrollCaptureConnection$$ExternalSyntheticLambda4.run(Unknown Source:6)
	at android.os.Handler.handleCallback(Handler.java:958)
	at android.os.Handler.dispatchMessage(Handler.java:99)
	at android.os.Looper.loopOnce(Looper.java:205)
	at android.os.Looper.loop(Looper.java:294)
	at android.app.ActivityThread.main(ActivityThread.java:8177)
	at java.lang.reflect.Method.invoke(Native Method)
	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:552)
	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)
```

## ANRs (0, traces 0)

## Native crashes (0; known emulator SIGILL in the wake word engine: 0)

## Monkey

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=219.771, y[0]=56.5459, toolType[0]=UNKNOWN, id[1]=1, x[1]=90.1592, y[1]=403.153, toolType[1]=UNKNOWN, pointerCount=2, eventTime=79353000000, downTime=79351000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-238039370}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=219.771, y[0]=56.5459, toolType[0]=UNKNOWN, id[1]=1, x[1]=90.1592, y[1]=403.153, toolType[1]=UNKNOWN, pointerCount=2, eventTime=79353000000, downTime=79351000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-238039370}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=297.873, toolType[0]=UNKNOWN, id[1]=1, x[1]=209.82, y[1]=267.634, toolType[1]=UNKNOWN, pointerCount=2, eventTime=116818000000, downTime=116810000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-292729973}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=320, y[0]=297.873, toolType[0]=UNKNOWN, id[1]=1, x[1]=209.82, y[1]=267.634, toolType[1]=UNKNOWN, pointerCount=2, eventTime=116818000000, downTime=116810000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-292729973}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=236.509, y[0]=416.296, toolType[0]=UNKNOWN, id[1]=1, x[1]=211.256, y[1]=517.384, toolType[1]=UNKNOWN, pointerCount=2, eventTime=152972000000, downTime=152967000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-160819299}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=236.509, y[0]=416.296, toolType[0]=UNKNOWN, id[1]=1, x[1]=211.256, y[1]=517.384, toolType[1]=UNKNOWN, pointerCount=2, eventTime=152972000000, downTime=152967000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-160819299}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=200, y[0]=140.451, toolType[0]=UNKNOWN, id[1]=1, x[1]=290.913, y[1]=552, toolType[1]=UNKNOWN, pointerCount=2, eventTime=158251000000, downTime=158248000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-393442786}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=200, y[0]=140.451, toolType[0]=UNKNOWN, id[1]=1, x[1]=290.913, y[1]=552, toolType[1]=UNKNOWN, pointerCount=2, eventTime=158251000000, downTime=158248000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-393442786}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=2.65332, y[0]=165.092, toolType[0]=UNKNOWN, id[1]=1, x[1]=0, y[1]=31.3105, toolType[1]=UNKNOWN, pointerCount=2, eventTime=220420000000, downTime=220418000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-184074314}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=2.65332, y[0]=165.092, toolType[0]=UNKNOWN, id[1]=1, x[1]=0, y[1]=31.3105, toolType[1]=UNKNOWN, pointerCount=2, eventTime=220420000000, downTime=220418000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-184074314}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=206.592, y[0]=339.038, toolType[0]=UNKNOWN, id[1]=1, x[1]=49.3643, y[1]=232.77, toolType[1]=UNKNOWN, pointerCount=2, eventTime=232277000000, downTime=232274000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-27531789}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=206.592, y[0]=339.038, toolType[0]=UNKNOWN, id[1]=1, x[1]=49.3643, y[1]=232.77, toolType[1]=UNKNOWN, pointerCount=2, eventTime=232277000000, downTime=232274000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-27531789}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-101.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=136.743, y[0]=148.021, toolType[0]=UNKNOWN, id[1]=1, x[1]=224.533, y[1]=190.477, toolType[1]=UNKNOWN, pointerCount=2, eventTime=307041000000, downTime=307038000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-23128840}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=136.743, y[0]=148.021, toolType[0]=UNKNOWN, id[1]=1, x[1]=224.533, y[1]=190.477, toolType[1]=UNKNOWN, pointerCount=2, eventTime=307041000000, downTime=307038000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-23128840}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```
- monkey-101.txt: 20000 events injected; ends: ## Network stats: elapsed time=308654ms (0ms mobile, 0ms wifi, 308654ms not connected) | // Monkey finished | exit 0

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=161.903, y[0]=350.87, toolType[0]=UNKNOWN, id[1]=1, x[1]=276.182, y[1]=79.9814, toolType[1]=UNKNOWN, pointerCount=2, eventTime=447521000000, downTime=447518000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-418663602}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=161.903, y[0]=350.87, toolType[0]=UNKNOWN, id[1]=1, x[1]=276.182, y[1]=79.9814, toolType[1]=UNKNOWN, pointerCount=2, eventTime=447521000000, downTime=447518000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-418663602}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=73.9873, y[0]=-22.3633, toolType[0]=UNKNOWN, id[1]=1, x[1]=143.86, y[1]=328.008, toolType[1]=UNKNOWN, pointerCount=2, eventTime=456748000000, downTime=456745000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-696480076}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=73.9873, y[0]=-22.3633, toolType[0]=UNKNOWN, id[1]=1, x[1]=143.86, y[1]=328.008, toolType[1]=UNKNOWN, pointerCount=2, eventTime=456748000000, downTime=456745000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-696480076}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=112.689, y[0]=38.6973, toolType[0]=UNKNOWN, id[1]=1, x[1]=150.544, y[1]=142.2, toolType[1]=UNKNOWN, pointerCount=2, eventTime=520398000000, downTime=520393000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-1064240399}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=112.689, y[0]=38.6973, toolType[0]=UNKNOWN, id[1]=1, x[1]=150.544, y[1]=142.2, toolType[1]=UNKNOWN, pointerCount=2, eventTime=520398000000, downTime=520393000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-1064240399}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=183.211, y[0]=-13.2383, toolType[0]=UNKNOWN, id[1]=1, x[1]=308.99, y[1]=115.891, toolType[1]=UNKNOWN, pointerCount=2, eventTime=639292000000, downTime=639290000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-165094260}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=183.211, y[0]=-13.2383, toolType[0]=UNKNOWN, id[1]=1, x[1]=308.99, y[1]=115.891, toolType[1]=UNKNOWN, pointerCount=2, eventTime=639292000000, downTime=639290000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-165094260}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-202.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=226.075, y[0]=297.601, toolType[0]=UNKNOWN, id[1]=1, x[1]=163.509, y[1]=25.2188, toolType[1]=UNKNOWN, pointerCount=2, eventTime=644593000000, downTime=644590000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-746667861}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=226.075, y[0]=297.601, toolType[0]=UNKNOWN, id[1]=1, x[1]=163.509, y[1]=25.2188, toolType[1]=UNKNOWN, pointerCount=2, eventTime=644593000000, downTime=644590000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-746667861}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```
- monkey-202.txt: 20000 events injected; ends: ## Network stats: elapsed time=279635ms (0ms mobile, 0ms wifi, 279635ms not connected) | // Monkey finished | exit 0

monkey-303.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=50.3652, y[0]=159.793, toolType[0]=UNKNOWN, id[1]=1, x[1]=169.251, y[1]=-88, toolType[1]=UNKNOWN, pointerCount=2, eventTime=739926000000, downTime=739924000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-184220942}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=50.3652, y[0]=159.793, toolType[0]=UNKNOWN, id[1]=1, x[1]=169.251, y[1]=-88, toolType[1]=UNKNOWN, pointerCount=2, eventTime=739926000000, downTime=739924000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-184220942}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-303.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.IllegalArgumentException
// Long Msg: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=119.867, y[0]=221.23, toolType[0]=UNKNOWN, id[1]=1, x[1]=211.994, y[1]=547.025, toolType[1]=UNKNOWN, pointerCount=2, eventTime=747287000000, downTime=747284000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-721436574}
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=119.867, y[0]=221.23, toolType[0]=UNKNOWN, id[1]=1, x[1]=211.994, y[1]=547.025, toolType[1]=UNKNOWN, pointerCount=2, eventTime=747287000000, downTime=747284000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-721436574}
// 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
// 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
// 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
// 	at android.view.View.dispatchTouchEvent(View.java:15655)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
// 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
// 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2801)
// 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3120)
```

monkey-303.txt CRASH com.anjy.atlas
```
// Short Msg: java.lang.NullPointerException
// Long Msg: java.lang.NullPointerException: Attempt to invoke virtual method 'boolean android.os.CancellationSignal.isCanceled()' on a null object reference
// Build Label: google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys
// Build Changelist: 12077443
// Build Time: 1720722121000
// java.lang.NullPointerException: Attempt to invoke virtual method 'boolean android.os.CancellationSignal.isCanceled()' on a null object reference
// 	at com.android.internal.view.ScrollCaptureViewSupport.onScrollCaptureImageRequest(ScrollCaptureViewSupport.java:240)
// 	at android.view.ScrollCaptureConnection.lambda$requestImage$1(ScrollCaptureConnection.java:149)
// 	at android.view.ScrollCaptureConnection.$r8$lambda$B-K9IOFMSTMXjr1AmzzeRKza8aI(Unknown Source:0)
// 	at android.view.ScrollCaptureConnection$$ExternalSyntheticLambda4.run(Unknown Source:6)
// 	at android.os.Handler.handleCallback(Handler.java:958)
// 	at android.os.Handler.dispatchMessage(Handler.java:99)
// 	at android.os.Looper.loopOnce(Looper.java:205)
// 	at android.os.Looper.loop(Looper.java:294)
// 	at android.app.ActivityThread.main(ActivityThread.java:8177)
// 	at java.lang.reflect.Method.invoke(Native Method)
// 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:552)
// 	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)
// 
```
- monkey-303.txt: 20000 events injected; ends: ## Network stats: elapsed time=275984ms (0ms mobile, 0ms wifi, 275984ms not connected) | // Monkey finished | exit 0

## Crawler events (0)

## Atlas's error lines (1063, 114 kinds)
- ×98 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3114)
- ×98 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2787)
- ×57 11-25 21:00:53.040 [com.anjy.atlas] ImeBackDispatcher: Ime callback not found. Ignoring unregisterReceivedCallback. callbackId: 139581031
- ×51 11-25 21:00:52.639 [com.anjy.atlas] FrameTracker: force finish cuj, time out: J<IME_INSETS_ANIMATION::0@0@com.anjy.atlas>
- ×42 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.deliver(ViewRootImpl.java:6229)
- ×28 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.onDeliverToNext(ViewRootImpl.java:6286)
- ×28 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.forward(ViewRootImpl.java:6252)
- ×28 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$InputStage.apply(ViewRootImpl.java:6260)
- ×28 11-25 21:04:18.777 [com.anjy.atlas] SpeechRecognizer: not connected to the recognition service
- ×19 11-25 21:00:26.338 [com.anjy.atlas] SurfaceSyncGroup: Failed to receive transaction ready in 1000ms. Marking SurfaceSyncGroup(wmsSync-VRI[MainActivity]#0) as ready
- ×19 11-25 21:00:26.339 [com.anjy.atlas] SurfaceSyncGroup: Failed to receive transaction ready in 1000ms. Marking SurfaceSyncGroup(VRI[MainActivity]#1) as ready
- ×14 11-25 21:00:56.165 [com.anjy.atlas] InputEventReceiver: Exception dispatching input event.
- ×14 11-25 21:00:56.166 [com.anjy.atlas] MessageQueue-JNI: Exception in MessageQueue callback: handleReceiveCallback
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.MotionEvent.nativeGetAxisValue(Native Method)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.MotionEvent.getY(MotionEvent.java:2667)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.widget.ScrollView.onTouchEvent(ScrollView.java:913)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.View.dispatchTouchEvent(View.java:15655)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.anjy.atlas.SwipeScrollView.dispatchTouchEvent(stoa_Views.kt:253)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.policy.DecorView.superDispatchTouchEvent(DecorView.java:490)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.policy.PhoneWindow.superDispatchTouchEvent(PhoneWindow.java:1904)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.app.Activity.dispatchTouchEvent(Activity.java:4377)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.anjy.atlas.MainActivity.dispatchTouchEvent(MainActivity.kt:650)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.policy.DecorView.dispatchTouchEvent(DecorView.java:448)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.View.dispatchPointerEvent(View.java:15919)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$ViewPostImeInputStage.processPointerEvent(ViewRootImpl.java:7021)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6815)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$AsyncInputStage.forward(ViewRootImpl.java:6417)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$AsyncInputStage.apply(ViewRootImpl.java:6474)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl.deliverInputEvent(ViewRootImpl.java:9211)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl.doProcessInputEvents(ViewRootImpl.java:9162)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl.enqueueInputEvent(ViewRootImpl.java:9131)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.ViewRootImpl$WindowInputEventReceiver.onInputEvent(ViewRootImpl.java:9337)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.view.InputEventReceiver.dispatchInputEvent(InputEventReceiver.java:267)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.MessageQueue.nativePollOnce(Native Method)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.MessageQueue.next(MessageQueue.java:335)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.Looper.loopOnce(Looper.java:162)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.os.Looper.loop(Looper.java:294)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at android.app.ActivityThread.main(ActivityThread.java:8177)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at java.lang.reflect.Method.invoke(Native Method)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:552)
- ×14 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: 	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)
- ×8 11-25 21:00:56.172 [com.anjy.atlas] MessageQueue-JNI: java.lang.IllegalArgumentException: invalid pointerIndex -1 for MotionEvent { action=POINTER_UP(1), id[0]=0, x[0]=219.771, y[0]=56.5459, toolType[0]=UNKNOWN, id[1]=1, x[1]=90.1592, y[1]=403.153, toolType[1]=UNKNOWN, pointerCount=2, eventTime=79353000000, downTime=79351000000, deviceId=-1, source=TOUCHSCREEN, displayId=0, eventId=-238039370}
- ×6 11-25 21:04:34.770 [com.anjy.atlas] RippleDrawable: The RippleDrawable.STYLE_PATTERNED animation is not supported for a non-hardware accelerated Canvas. Skipping animation.
- ×6 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.ViewRootImpl.<init>(ViewRootImpl.java:1009)
- ×6 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.ViewGroup.dispatchKeyEvent(ViewGroup.java:1966)
- ×4 11-25 21:13:10.113 [com.anjy.atlas] FrameEvents: updateAcquireFence: Did not find frame.
- ×3 11-25 21:00:25.782 [com.anjy.atlas] AtlasOrb: orb renderer
- ×3 11-25 21:00:25.782 [com.anjy.atlas] AtlasOrb: java.lang.IllegalStateException: software graphics (Android Emulator OpenGL ES Translator (Google SwiftShader) Google (Google Inc.))
- ×3 11-25 21:00:25.782 [com.anjy.atlas] AtlasOrb: 	at com.anjy.atlas.GlOrbView$Renderer.setup(OrbGl.kt:231)
- ×3 11-25 21:00:25.782 [com.anjy.atlas] AtlasOrb: 	at com.anjy.atlas.GlOrbView$Renderer.run(OrbGl.kt:182)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.WindowManagerGlobal.addView(WindowManagerGlobal.java:397)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.WindowManagerImpl.addView(WindowManagerImpl.java:150)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.app.Dialog.show(Dialog.java:352)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.View.performClick(View.java:7659)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.View.performClickInternal(View.java:7636)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.ViewRootImpl$InputStage.deliver(ViewRootImpl.java:6229)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.ViewRootImpl$InputStage.onDeliverToNext(ViewRootImpl.java:6286)
- ×3 11-25 21:08:17.180 [com.anjy.atlas] WindowManager: 	at android.view.ViewRootImpl$InputStage.forward(ViewRootImpl.java:6252)
- ×3 11-25 21:08:17.182 [com.anjy.atlas] WindowManager: 	at android.os.Looper.loopOnce(Looper.java:162)
- ×3 11-25 21:08:17.182 [com.anjy.atlas] WindowManager: 	at android.os.Looper.loop(Looper.java:294)

## Exceptions printed to the log (0, 0 kinds)

## StrictMode (0)

## Leaks (3)
- ×1 11-25 21:08:17.180 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.BotExtrasActivity has leaked window DecorView@707052c[BotExtrasActivity] that was originally added here
- ×1 11-25 21:09:38.453 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.CrewActivity has leaked window DecorView@2f60a9f[CrewActivity] that was originally added here
- ×1 11-25 21:13:43.884 WindowManager: android.view.WindowLeaked: Activity com.anjy.atlas.CrewActivity has leaked window DecorView@5b16f4e[CrewActivity] that was originally added here

## Frames: {'count': 28, 'max': 153, 'over_30': 28}

## Dropbox: {'data_app_crash': 1}
