plugins {
    kotlin("multiplatform")
}

kotlin {
    // JVM: solo per far girare i test del motore sul PC
    jvm()

    // JavaScript: il motore usato dalla web app (modulo ES con definizioni TypeScript)
    js(IR) {
        moduleName = "docverso-engine"
        browser {
            testTask { enabled = false }
        }
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
