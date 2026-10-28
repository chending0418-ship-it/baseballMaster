plugins { id("org.jetbrains.kotlin.jvm"); id("org.jetbrains.kotlin.plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { systemProperty("parity.fixtures", rootProject.file("../test-fixtures/android-parity").absolutePath) }

// Generated wire files are validation artifacts, never reference-fixture replacements.
tasks.test {
    systemProperty("wire.output", rootProject.file("../output/validation/android/an04-wire-exchange/kotlin").absolutePath)
    outputs.dir(rootProject.file("../output/validation/android/an04-wire-exchange/kotlin"))
    exclude("**/AppleSwiftWireReturnTest.class")
    exclude("**/TeamDirectoryIntegrationTest.class")
    exclude("**/StandaloneLiveIntegrationTest.class")
    exclude("**/AppleCommandStateReturnTest.class")
    exclude("**/AppleSwiftSessionTest.class")
}
tasks.register<Test>("liveServiceTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/StandaloneLiveIntegrationTest.class")
    systemProperty("parity.fixtures", rootProject.file("../test-fixtures/android-parity").absolutePath)
    inputs.files(rootProject.file("../scripts/android/an06-local-live.mjs"))
    inputs.files(fileTree(rootProject.file("../live")) { include("*.mjs") })
}
// Node 24 is only needed for this explicit, isolated existing-service integration.
tasks.register<Test>("teamServiceTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/TeamDirectoryIntegrationTest.class")
    systemProperty("parity.fixtures", rootProject.file("../test-fixtures/android-parity").absolutePath)
    inputs.files(rootProject.file("../scripts/android/an05-local-service.mjs"))
    inputs.files(fileTree(rootProject.file("../collaboration")) { include("*.mjs") })
}
// Explicit second leg of Kotlin -> original Swift models -> Kotlin. Missing artifacts fail.
tasks.register<Test>("swiftWireTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/AppleSwiftWireReturnTest.class")
    inputs.dir(providers.gradleProperty("swiftWireDirectory"))
    systemProperty("swift.wire.directory", providers.gradleProperty("swiftWireDirectory").orElse("").get())
    systemProperty("parity.fixtures", rootProject.file("../test-fixtures/android-parity").absolutePath)
}
tasks.register<Test>("swiftCommandStateTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/AppleCommandStateReturnTest.class")
    inputs.dir(providers.gradleProperty("swiftCommandDirectory"))
    systemProperty("swift.command.directory", providers.gradleProperty("swiftCommandDirectory").orElse("").get())
}
tasks.register<Test>("swiftSessionTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/AppleSwiftSessionTest.class")
    inputs.file(providers.gradleProperty("swiftCoreBinary"))
    systemProperty("swift.core.binary", providers.gradleProperty("swiftCoreBinary").orElse("").get())
    systemProperty("parity.fixtures", rootProject.file("../test-fixtures/android-parity").absolutePath)
}
