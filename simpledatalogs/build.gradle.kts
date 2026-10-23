import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android library: a thin facade over simpledatalogs-core, plus lifecycle and autocapture wiring.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.xkdlabs.simpledatalogs.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    // Java 8 bytecode, matching the core.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // The library ships unminified; apps shrink it with their own R8 run.
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Lint findings fail the build, including calls above minSdk.
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

// Every public declaration states its visibility, as a library should.
kotlin {
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        optIn.add("com.xkdlabs.simpledatalogs.InternalSimpleDataLogsApi")
    }
}

dependencies {
    api(project(":simpledatalogs-core"))

    // Optional at runtime: apps add it themselves when they want the install referrer.
    compileOnly(libs.installreferrer)

    testImplementation(libs.junit)
}

// Published as com.xkdlabs:simpledatalogs (an AAR), signed only when a key is configured.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(artifactId = "simpledatalogs")

    pom {
        name.set("SimpleDataLogs for Android")
        description.set("Track events from Android apps to simpledatalogs: a crash-safe outbox, batching, retries and opt-in autocapture.")
    }
}
