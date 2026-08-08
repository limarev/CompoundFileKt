plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.21"
    `maven-publish`
}

group = "io.github.limarev"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "io.github.limarev.compoundfile"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "compound-file-kt"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
