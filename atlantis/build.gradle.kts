import java.util.concurrent.Callable

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

val libraryGroup = providers.gradleProperty("GROUP").get()
val libraryArtifact = providers.gradleProperty("POM_ARTIFACT_ID").get()
val libraryVersion = providers.gradleProperty("VERSION_NAME").get()
val isSnapshot = libraryVersion.endsWith("-SNAPSHOT")
val centralRepository = layout.buildDirectory.dir("central-repository")

android {
    namespace = "com.proxyman.atlantis"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        buildConfigField("String", "VERSION_NAME", "\"$libraryVersion\"")
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
                
                groupId = libraryGroup
                artifactId = libraryArtifact
                version = libraryVersion
                
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

        repositories {
            if (isSnapshot) {
                maven {
                    name = "Sonatype"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                    credentials {
                        username = project.findProperty("ossrhUsername") as String?
                        password = project.findProperty("ossrhPassword") as String?
                    }
                }
            } else {
                maven {
                    name = "CentralBundle"
                    url = uri(centralRepository)
                }
            }
        }
    }

    signing {
        providers.environmentVariable("MAVEN_SIGNING_KEY").orNull?.let { key ->
            val password = providers.environmentVariable("MAVEN_SIGNING_PASSWORD").orElse("").get()
            useInMemoryPgpKeys(key, password)
        }
        setRequired(Callable {
            gradle.taskGraph.allTasks.any {
                it is PublishToMavenRepository && it.publication.name == "release"
            }
        })
        sign(publishing.publications["release"])
    }

    if (!isSnapshot) {
        val cleanStaging = tasks.register<Delete>("cleanCentralRepository") {
            delete(centralRepository)
        }
        val publishStaging = tasks.named("publishReleasePublicationToCentralBundleRepository") {
            dependsOn(cleanStaging)
        }
        val versionPath = "${libraryGroup.replace('.', '/')}/$libraryArtifact/$libraryVersion"
        tasks.register<Zip>("bundleMavenCentralRelease") {
            group = "publishing"
            description = "Builds the signed deployment bundle for the Maven Central Portal."
            dependsOn(publishStaging)
            from(centralRepository.map { it.dir(versionPath) }) {
                exclude("**/*.asc.*")
                into(versionPath)
            }
            archiveFileName.set("$libraryArtifact-$libraryVersion-central.zip")
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        }
    }
}
