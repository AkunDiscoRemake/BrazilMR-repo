plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
tasks.register<JavaExec>("verifyCore") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.brazilmr.core.CoreTestSuiteKt")
}
tasks.test { finalizedBy("verifyCore") }
