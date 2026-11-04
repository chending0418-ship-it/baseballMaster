# Android PDF parity native harness

After `:app:compileDebugKotlin` succeeds, this uses those current `ReportExport*.class` files plus `ReportStatisticsProjection*.class` from the newly compiled core.jar and the existing Debug APK as a classpath dependency. It compiles only a standalone Kotlin harness and patch DEX, runs Android native PdfDocument via `adb shell app_process`, then pulls PDFs. It does **not** assemble or install an APK, start the App, write Room records or send production requests. All fixtures are in-memory; device files are under `/data/local/tmp/baseballmaster-pdf-parity`.

```sh
python3 scripts/android/pdf-parity/run-native-pdf.py
python3 scripts/android/pdf-parity/verify-native-pdfs.py
```

Configuration: `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`, `ANDROID_SERIAL` (default emulator-5562), `ANDROID_PLATFORM_JAR`, `PDF_PARITY_DEPENDENCY_APK`, `PDF_PARITY_OUTPUT`. Point GRADLE_USER_HOME at the existing dependency cache used by the successful Gradle build. The verify script needs pdfplumber, Pillow and pdftoppm (bundled runtime provides them). `HARNESS_COMPILE_ONLY=1` validates the standalone source without executing on a device.

Default output: `output/validation/android/0.8.2/pdf-parity/native-harness/`. Includes actual PDF files, ReportExport class and dependency APK SHA-256, PDF text/geometry checks, and numbered review sheets containing every page. Review sheets still require human visual inspection. Native Typeface uses the platform preinstalled system font map, matching the font setup normally inherited from Zygote.

The 15 samples cover the three single-game exports, team/player reports, 30-inning score tables, 64 long Chinese names, long event descriptions in both styles, selected appearance, no events, empty boxscore, coach pitch, timed play and ten-player slow pitch. These are export layout fixtures, not a claim of complete real-match or cross-platform data migration coverage.
