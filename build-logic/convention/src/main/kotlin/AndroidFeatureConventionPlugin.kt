import com.ryan.scanme.buildlogic.libs
import com.ryan.scanme.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("scanme.android.library")
        pluginManager.apply("scanme.android.compose")
        pluginManager.apply("scanme.android.hilt")
        dependencies {
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:domain"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-navigation-compose"))
            add("implementation", libs.library("androidx-hilt-navigation-compose"))
        }
    }
}
