package io.github.nuke.oss.app.shared.activity

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Resources

// Gives a module Activity that runs in a host process the module's own resources. They sit in a separate Resources
// object, so module IDs never collide with the host's and the host's AssetManager is left alone.
internal class ModuleResourcesContext private constructor(
    base: Context,
    private val moduleResources: Resources,
    private val themeId: Int,
) : ContextWrapper(base) {
    private val moduleTheme by lazy { moduleResources.newTheme().apply { applyStyle(themeId, true) } }

    override fun getResources(): Resources = moduleResources
    override fun getAssets() = moduleResources.assets
    override fun getTheme(): Resources.Theme = moduleTheme

    companion object {
        // Null when the module package cannot be opened from this host, for example under package visibility rules.
        // Flags 0 loads resources only: the module code in this process already came from the framework.
        fun create(base: Context, modulePackage: String, themeId: Int): ModuleResourcesContext? = try {
            val module = base.createPackageContext(modulePackage, 0)
            // The host's current configuration keeps night mode, locale and density in step with the Activity.
            val configured = module.createConfigurationContext(base.resources.configuration)
            ModuleResourcesContext(base, configured.resources, themeId)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
