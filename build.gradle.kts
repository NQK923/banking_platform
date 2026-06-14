plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
}

group = "com.ewallet"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-Xshare:off")
    }

    dependencies {
        "implementation"(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
        "testImplementation"(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.5")
    }
}
