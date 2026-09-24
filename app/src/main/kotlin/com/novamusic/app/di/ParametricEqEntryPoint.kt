/*
 * NovaMusic — GPL-3.0.
 *
 * Follows the same entry-point pattern as LyricsHelperEntryPoint: the settings UI needs the
 * parametric EQ controller without a ViewModel, and this keeps Hilt wiring out of composables.
 */

package com.novamusic.app.di

import com.novamusic.app.eq.ParametricEqController
import com.novamusic.app.eq.data.ParametricEqRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ParametricEqEntryPoint {
    fun parametricEqController(): ParametricEqController

    fun parametricEqRepository(): ParametricEqRepository
}
