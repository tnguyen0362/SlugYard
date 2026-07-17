package com.nuvio.tv.ui.screens.home

object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    fun update(url: String?) {
        currentHeroBackdropUrl = url
        // Keep this in lockstep with the actual hero.  Retaining a prior non-null
        // URL here made a failed/missing backdrop look like the next title's art.
        lastDisplayedUrl = url
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        return url
    }
}
