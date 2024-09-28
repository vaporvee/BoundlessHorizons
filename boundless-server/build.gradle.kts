plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "com.vaporvee"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-api:2.24.0")
    implementation("org.apache.logging.log4j:log4j-core:2.24.0")
    implementation("com.google.code.gson:gson:2.8.8")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks {
    jar {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Main-Class" to "com.vaporvee.boundlessutil.BoundlessServer"
            )
        }
    }

    shadowJar {
        archiveFileName.set("boundless-server.jar")
        manifest {
            attributes(
                "Main-Class" to "com.vaporvee.boundlessutil.BoundlessServer"
            )
        }
    }
}
