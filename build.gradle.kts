plugins {
    id("com.android.application") version "9.5.0" apply false
    // Also raises AGP's built-in Kotlin from 2.2: Compose 1.12 and later need 2.4.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
