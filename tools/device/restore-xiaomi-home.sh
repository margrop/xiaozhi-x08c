adb shell cmd package set-home-activity --user 0 com.xiaomi.micolauncher/.Launcher
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
