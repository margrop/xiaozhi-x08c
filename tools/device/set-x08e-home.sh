adb shell cmd package set-home-activity --user 0 moe.rua.x08clauncher/.MainActivity
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
