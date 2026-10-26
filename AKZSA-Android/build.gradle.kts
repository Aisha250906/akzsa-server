// Kotlin 2.3.21 is the compatible line for AGP 8.13.0's bundled R8 8.13.x.
// Kotlin 2.4 requires R8 9.1.29 per Android's compatibility table; AGP 8.13
// does not bundle that R8 line. Keep Gradle 8.14.4 and align the Kotlin plugins.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
}
