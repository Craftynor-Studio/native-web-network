import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val jitpackBuild = providers.environmentVariable("JITPACK").orNull == "true"
group = if (jitpackBuild) providers.environmentVariable("GROUP").get() else "com.craftynorstudio"
version = if (jitpackBuild) providers.environmentVariable("VERSION").get() else "0.1.0"

android {
    namespace = "com.craftynorstudio.nativewebnetwork"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    // The standalone website SDK is included only in the test APK, never in the library AAR.
    sourceSets["androidTest"].assets.srcDir("sdk")
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
dependencies {
    implementation(libs.webkit)
    implementation(libs.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
publishing {
    repositories { maven { name = "LocalBuild"; url = uri(layout.buildDirectory.dir("repository")) } }
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "native-web-network"
            version = project.version.toString()
            artifact(file("sdk/native-web-network.js")) { classifier = "sdk"; extension = "js" }
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Native Web Network")
                description.set("App-policy-controlled HTTPS networking for Android WebView with a standalone JavaScript SDK, by Craftynor Studio.")
                url.set("https://github.com/Craftynor-Studio/native-web-network")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers { developer { id.set("Craftynor-Studio"); name.set("Craftynor Studio") } }
                scm {
                    url.set("https://github.com/Craftynor-Studio/native-web-network")
                    connection.set("scm:git:https://github.com/Craftynor-Studio/native-web-network.git")
                }
            }
        }
    }
}
