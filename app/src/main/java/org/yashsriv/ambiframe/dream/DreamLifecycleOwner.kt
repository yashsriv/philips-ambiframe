package org.yashsriv.ambiframe.dream

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Gives a `DreamService` window the lifecycle and saved-state owners Compose expects. An Activity
 * provides these for free; a Service does not, so [ArtDreamService] drives this owner by hand.
 */
internal class DreamLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun destroy() {
        stop()
        if (registry.currentState != Lifecycle.State.DESTROYED &&
            registry.currentState.isAtLeast(Lifecycle.State.CREATED)
        ) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    /** Installs this owner on [view] (normally the window's decor view) for Compose to find. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
