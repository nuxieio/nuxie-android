plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "io.nuxie.sdk"
  compileSdk = 34

  defaultConfig {
    minSdk = 21
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  api(project(":nuxie-core"))

  implementation("androidx.annotation:annotation:1.8.2")

  testImplementation("junit:junit:4.13.2")
}
