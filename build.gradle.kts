import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.cramium.activecard"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            proto {
                srcDir("src/main/proto")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    packaging {
        resources.excludes.add("META-INF/*")
        resources.excludes.add("io/grpc/testing/integration/empty.proto")
        resources.excludes.add("io/grpc/testing/integration/test.proto")
        resources.excludes.add("io/grpc/testing/integration/messages.proto")
        resources.excludes.add("tmp/stuff.proto")
    }
}

// Compatible with macOS on Apple Silicon
val archSuffix = if (Os.isFamily(Os.FAMILY_MAC)) ":osx-x86_64" else ""
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3$archSuffix"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.android.support.test:runner:1.0.2")
    androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
    implementation("com.polidea.rxandroidble3:rxandroidble:1.19.0")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.zxing:core:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.10.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.google.protobuf:protoc:3.25.3")
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
}