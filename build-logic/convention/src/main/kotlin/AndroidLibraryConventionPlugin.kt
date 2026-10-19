import com.android.build.api.dsl.LibraryExtension
import com.ryan.scanme.buildlogic.configureKotlinAndroid
import com.ryan.scanme.buildlogic.libs
import com.ryan.scanme.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
        }
        dependencies {
            add("testImplementation", libs.library("junit"))
        }
    }
}
