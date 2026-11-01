package io.github.nuke.oss.ui.overlay

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// Module Compose content over a host Activity. A zero-size ComposeView in the Activity's content view anchors the
// composition; the dialogs it shows draw in windows of their own. The host's AndroidX is another copy behind another
// loader, so the module's Compose gets lifecycle, saved state and ViewModel owners of its own.
// Main thread only. Closing is idempotent, and the Activity going away closes it as well.
internal class HostOverlay private constructor(private val view: ComposeView, private val owner: OverlayOwner) : AutoCloseable {
    override fun close() {
        if (!owner.destroy()) return
        (view.parent as? ViewGroup)?.removeView(view)
    }

    companion object {
        // Null when the Activity has no content view to anchor the composition in.
        fun attach(activity: Activity, module: Context, content: @Composable () -> Unit): HostOverlay? {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
            val owner = OverlayOwner()
            val view = ComposeView(module).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner.lifecycle))
                setContent(content)
            }
            // Detaching while the window goes away only ends the composition; the view is not removed mid-traversal.
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    owner.destroy()
                }
            })
            root.addView(view, ViewGroup.LayoutParams(0, 0))
            owner.resume()
            return HostOverlay(view, owner)
        }
    }
}

// Lifecycle, saved state and ViewModel ownership for Compose content that no module Activity owns.
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    // False when it was already destroyed.
    fun destroy(): Boolean {
        if (registry.currentState == Lifecycle.State.DESTROYED) return false
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        return true
    }
}
