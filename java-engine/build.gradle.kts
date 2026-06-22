plugins {
    id("java")
}

group = "dev.junyoung"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("it.unimi.dsi:fastutil:8.5.18")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// rust-engine의 `cargo run --release --bin bench_runner`에 대응하는 벤치 실행 태스크.
tasks.register<JavaExec>("bench") {
    group = "benchmark"
    description = "매칭 엔진 벤치마크 하니스 실행 (결과 CSV: build/bench-results/). " +
        "특정 시나리오만: --args=\"--scenario=ThinBook --scale=1000000\". " +
        "반복 횟수 지정(기본 10): --args=\"--runs=3\""
    mainClass = "dev.junyoung.bench.BenchRunner"
    classpath = sourceSets["main"].runtimeClasspath
}
