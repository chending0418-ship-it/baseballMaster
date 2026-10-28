plugins { id("org.jetbrains.kotlin.jvm"); id("org.jetbrains.kotlin.plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { systemProperty("parity.fixtures", rootProject.file("../test-fixtures/android-parity").absolutePath) }

// Generated wire files are validation artifacts, never reference-fixture replacements.
tasks.test {
    systemProperty("wire.output", rootProject.file("../output/validation/android/an04-wire-exchange/kotlin").absolutePath)
    outputs.dir(rootProject.file("../output/validation/android/an04-wire-exchange/kotlin"))
    exclude("**/AppleSwiftWireReturnTest.class")
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
