plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

sourceSets {
    test {
        kotlin.srcDir("../tools/spider-probe")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.quickjs:quickjs-jvm:0.9.2")
}

tasks.register<JavaExec>("spiderProbe") {
    group = "verification"
    description = "用 QuickJS 统计命令行传入的配置里有多少 JS 爬虫能返回数据"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.jianxia.tools.SpiderProbeKt")
}
