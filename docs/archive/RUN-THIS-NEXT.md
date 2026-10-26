# Run this next (Windows PowerShell)

1. Extract this ZIP completely.
2. Open PowerShell in the extracted `AKZSA-AI-Search-Continuation-v6-FIXED` folder.
3. Run the commands below. Do not paste the entire contents of the `.ps1` file line-by-line into the `>>` continuation prompt.

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
.\AKZSA-FIX-AND-BUILD.ps1 -ProjectRoot ".\AKZSA-Android" -OutputDir ".\output"
```

To repair the existing v4 project from the user’s current Downloads path, run the downloaded standalone script with the explicit project path:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& "$HOME\Downloads\AKZSA-FIX-AND-BUILD-v2.ps1" `
  -ProjectRoot "$HOME\Downloads\AKZSA-AI-Search-Continuation-v4-FIXED-SOURCE\AKZSA-AI-Search-Continuation-v4-FIXED\AKZSA-Android" `
  -OutputDir "$HOME\Downloads\AKZSA-AI-Search-Continuation-v4-FIXED-SOURCE\output"
```

Expected APK after a successful build:

`output\AKZSA-AI-Search-debug.apk`

The current reported compiler failure is fixed in source by changing:

```kotlin
SqliteVectorStore(context.applicationContext, VECTOR_DB, embeddingDimension = 768)
```

to:

```kotlin
SqliteVectorStore(context.applicationContext, VECTOR_DB, 768)
```

`SqliteVectorStore` is a Java class and its constructor accepts `(Context, String, int)` in the version used here. Kotlin named arguments are not allowed for Java constructors. If the next build fails elsewhere, fix the first new compiler/dependency diagnostic rather than treating `BUILD FAILED` as the root cause.
