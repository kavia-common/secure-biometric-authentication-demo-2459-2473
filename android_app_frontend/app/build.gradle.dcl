androidApplication {
    namespace = "org.example.app"

    dependencies {
        implementation("org.apache.commons:commons-text:1.11.0")
        implementation(project(":utilities"))

        // AndroidX BiometricPrompt (biometric + device credential fallback)
        implementation("androidx.biometric:biometric:1.2.0-alpha05")

        // EncryptedSharedPreferences / Android Keystore helpers
        implementation("androidx.security:security-crypto:1.1.0-alpha06")

        // Lifecycle/ViewModel (non-Compose)
        implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

        // Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

        // Networking
        implementation("com.squareup.retrofit2:retrofit:2.11.0")
        implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

        // Unit testing (JUnit4) - required for :app:testDebugUnitTest discovery in this setup
        implementation("junit:junit:4.13.2")
    }
}
