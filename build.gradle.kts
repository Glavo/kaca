/*
 * Copyright 2024 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("java-library")
}

subprojects {
    group = "org.glavo"
    version = "0.1.0" + "-SNAPSHOT"

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
