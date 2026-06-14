plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
}

val grpcVersion = "1.68.2"
val protobufVersion = "4.29.3"

fun nativeExecutableClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    val os = when {
        osName.contains("windows") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        osName.contains("linux") -> "linux"
        else -> throw GradleException("Unsupported OS for protobuf native executable: $osName")
    }
    val archName = System.getProperty("os.arch").lowercase()
    val arch = when {
        archName == "x86_64" || archName == "amd64" -> "x86_64"
        archName == "aarch64" || archName == "arm64" -> "aarch_64"
        else -> throw GradleException("Unsupported architecture for protobuf native executable: $archName")
    }
    return "$os-$arch"
}

val nativeClassifier = nativeExecutableClassifier()

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion:$nativeClassifier@exe"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion:$nativeClassifier@exe"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

tasks.withType<Copy>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
