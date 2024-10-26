plugins {
    id("java-library")
}

subprojects {
    group = "org.glavo"
    version = "0.1.0" + "-SNAPSHOT"

    if (!project.name.startsWith("plumo")) {
        return@subprojects
    }

    apply {
        plugin("java-library")
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        compileOnlyApi("org.jetbrains:annotations:25.0.0")
        testImplementation(platform("org.junit:junit-bom:5.11.3"))
        testImplementation("org.junit.jupiter:junit-jupiter")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
