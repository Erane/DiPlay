plugins { id("com.android.application") }
android {
    namespace = "com.diplay.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.diplay.probe"
        minSdk = 18
        targetSdk = 37
    }
    lint { abortOnError = false }
}
