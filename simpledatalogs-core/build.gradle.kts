import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A pure Kotlin/JVM library with no runtime dependency besides the Kotlin stdlib.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

// Java 8 bytecode, so Android minSdk 21 and old servers can both load it.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
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
    testImplementation(libs.junit)
}

// Show each failed assertion in full in the test log.
tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Published as com.xkdlabs:simpledatalogs-core, signed only when a key is configured.
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(artifactId = "simpledatalogs-core")

    pom {
        name.set("SimpleDataLogs core")
        description.set("The pure Kotlin/JVM client for simpledatalogs: a crash-safe outbox, batching and retries, for servers and the Android SDK.")
    }
}
