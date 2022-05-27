/*
 * Copyright (c) 2021 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.music.MusicParent
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.ui.MainNavigationAction
import org.oxycblt.auxio.ui.NavigationViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.auxio.util.textSafe

/**
 * A [Fragment] that displays more information about the song, along with more media controls.
 * Instantiation is done by the navigation component, **do not instantiate this fragment manually.**
 * @author OxygenCobalt
 *
 * TODO: Handle RTL correctly in the playback buttons
 *
 * TODO: Make seek thumb grow when selected
 */
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    StyledSeekBar.Callback,
    Toolbar.OnMenuItemClickListener {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val navModel: NavigationViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?
    ) {
        // --- UI SETUP ---

        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.systemBarInsetsCompat
            binding.root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val queueItem: MenuItem

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { navModel.mainNavigateTo(MainNavigationAction.COLLAPSE) }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
            queueItem = menu.findItem(R.id.action_queue)
        }

        binding.playbackSong.apply {
            // Make marquee of the song title work
            isSelected = true
            setOnClickListener { playbackModel.song.value?.let(navModel::exploreNavigateTo) }
        }

        binding.playbackArtist.setOnClickListener {
            playbackModel.song.value?.let { navModel.exploreNavigateTo(it.album.artist) }
        }

        binding.playbackAlbum.setOnClickListener {
            playbackModel.song.value?.let { navModel.exploreNavigateTo(it.album) }
        }

        binding.playbackSeekBar.callback = this

        binding.playbackRepeat.setOnClickListener { playbackModel.incrementRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }

        binding.playbackPlayPause.apply {
            // Abuse the play/pause FAB (see style definition for more info)
            post { binding.playbackPlayPause.stateListAnimator = null }
            setOnClickListener { playbackModel.invertPlaying() }
        }

        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.invertShuffled() }

        // --- VIEWMODEL SETUP --

        playbackModel.song.observe(viewLifecycleOwner, ::updateSong)
        playbackModel.parent.observe(viewLifecycleOwner, ::updateParent)
        playbackModel.positionSecs.observe(viewLifecycleOwner, ::updatePosition)
        playbackModel.repeatMode.observe(viewLifecycleOwner, ::updateRepeat)
        playbackModel.isPlaying.observe(viewLifecycleOwner, ::updatePlaying)
        playbackModel.isShuffled.observe(viewLifecycleOwner, ::updateShuffled)

        playbackModel.nextUp.observe(viewLifecycleOwner) { nextUp ->
            // The queue icon uses a selector that will automatically tint the icon as active or
            // inactive. We just need to set the flag.
            queueItem.isEnabled = nextUp.isNotEmpty()
        }

        logD("Fragment Created")
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        binding.playbackSong.isSelected = false
        binding.playbackSeekBar.callback = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_queue -> {
                navModel.mainNavigateTo(MainNavigationAction.QUEUE)
                true
            }
            else -> false
        }
    }

    override fun seekTo(positionSecs: Long) {
        playbackModel.seekTo(positionSecs)
    }

    private fun updateSong(song: Song?) {
        if (song == null) return

        val binding = requireBinding()
        val context = requireContext()
        binding.playbackCover.bind(song)
        binding.playbackSong.textSafe = song.resolveName(context)
        binding.playbackArtist.textSafe = song.resolveIndividualArtistName(context)
        binding.playbackAlbum.textSafe = song.album.resolveName(context)
        binding.playbackSeekBar.durationSecs = song.durationSecs
    }

    private fun updateParent(parent: MusicParent?) {
        requireBinding().playbackToolbar.subtitle =
            parent?.resolveName(requireContext()) ?: getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionSecs: Long) {
        requireBinding().playbackSeekBar.positionSecs = positionSecs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        requireBinding().playbackRepeat.apply {
            isActivated = repeatMode != RepeatMode.NONE
            setImageResource(repeatMode.icon)
        }
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isActivated = isPlaying
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isActivated = isShuffled
    }
}
