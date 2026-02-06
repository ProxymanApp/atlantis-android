plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.proxyman.atlantis"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        buildConfigField("String", "VERSION_NAME", "\"${project.findProperty("VERSION_NAME") ?: "1.0.0"}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        buildConfig = true
    }
    
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // OkHttp - compileOnly so users provide their own version
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
    
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                
                groupId = project.findProperty("GROUP") as String? ?: "com.proxyman"
                artifactId = project.findProperty("POM_ARTIFACT_ID") as String? ?: "atlantis-android"
                version = project.findProperty("VERSION_NAME") as String? ?: "1.0.0"
                
                pom {
                    name.set(project.findProperty("POM_NAME") as String? ?: "Atlantis Android")
                    description.set(project.findProperty("POM_DESCRIPTION") as String? ?: "")
                    url.set(project.findProperty("POM_URL") as String? ?: "")
                    
                    licenses {
                        license {
                            name.set(project.findProperty("POM_LICENCE_NAME") as String? ?: "")
                            url.set(project.findProperty("POM_LICENCE_URL") as String? ?: "")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set(project.findProperty("POM_DEVELOPER_ID") as String? ?: "")
                            name.set(project.findProperty("POM_DEVELOPER_NAME") as String? ?: "")
                        }
                    }
                    
                    scm {
                        url.set(project.findProperty("POM_SCM_URL") as String? ?: "")
                        connection.set(project.findProperty("POM_SCM_CONNECTION") as String? ?: "")
                        developerConnection.set(project.findProperty("POM_SCM_DEV_CONNECTION") as String? ?: "")
                    }
                }
            }
        }
    }
}
