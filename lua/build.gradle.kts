plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.luaj:luaj-jse:3.0.1")
}
tasks.register<JavaExec>("verifyLua") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.brazilmr.lua.LuaTestSuiteKt")
}
tasks.test { finalizedBy("verifyLua") }
