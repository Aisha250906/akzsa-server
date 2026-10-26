# AKZSA v6 repair changelog

- Fixed `LocalSemanticSearch.kt`: the `SqliteVectorStore` constructor is a Java API, so Kotlin named arguments are prohibited. The call now passes `768` positionally: `SqliteVectorStore(context.applicationContext, VECTOR_DB, 768)`.
- Updated the repair/build script so it repairs the invalid named argument rather than reintroducing it.
- The script safely defaults paths when run as a `.ps1` file and explains how to pass `-ProjectRoot`.
- Kept the Kotlin typed `compilerOptions` JVM 17 configuration and Gradle 8.14.4 bootstrap from v5.

This is a patched source package, not a claimed prebuilt/verified APK. The Windows build still needs to run with Android SDK and resolve all dependencies.
