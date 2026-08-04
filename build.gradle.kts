// Top-level build file.
//
// Deliberately does NOT declare the Android Gradle Plugin here (not even with
// `apply false`): doing so forces the plugin onto the root buildscript
// classpath, which requires reaching Google's Maven even for builds that only
// touch :motion-core. Each module declares the plugins it needs instead.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
