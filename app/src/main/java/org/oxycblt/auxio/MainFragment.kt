package org.oxycblt.auxio

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import org.oxycblt.auxio.databinding.FragmentMainBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.accent
import org.oxycblt.auxio.ui.getTransparentAccent
import org.oxycblt.auxio.ui.isLandscape
import org.oxycblt.auxio.ui.toColor

/**
 * The primary "Home" [Fragment] for Auxio.
 * TODO: Make navigation stack instead of artificially rerouting to LibraryFragment
 */
class MainFragment : Fragment() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentMainBinding.inflate(inflater)

        // If the music was cleared while the app was closed [Likely due to Auxio being suspended
        // in the background], then navigate back to LoadingFragment to reload the music.
        // This may actually not happen in normal use, but its a good failsafe.
        if (MusicStore.getInstance().songs.isEmpty()) {
            findNavController().navigate(MainFragmentDirections.actionReturnToLoading())

            return null
        }

        val colorActive = accent.first.toColor(requireContext())
        val colorInactive = getTransparentAccent(
            requireContext(), accent.first, 150
        )

        // Set up the tints for the navigation icons + text
        val navTints = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(colorInactive, colorActive)
        )

        val navController = (
            childFragmentManager.findFragmentById(R.id.explore_nav_host)
                as NavHostFragment?
            )?.findNavController()

        // --- UI SETUP ---

        binding.lifecycleOwner = this

        binding.navBar.itemIconTintList = navTints
        binding.navBar.itemTextColor = navTints

        navController?.let { controller ->
            binding.navBar.setOnNavigationItemSelectedListener {
                navigateWithItem(controller, it)
            }
        }

        // --- VIEWMODEL SETUP ---

        // Change CompactPlaybackFragment's visibility here so that an animation occurs.
        if (!isLandscape(resources)) {
            handleCompactPlaybackVisibility(binding, playbackModel.song.value)

            playbackModel.song.observe(viewLifecycleOwner) {
                handleCompactPlaybackVisibility(binding, it)
            }
        }

        detailModel.navToItem.observe(viewLifecycleOwner) {
            if (it != null && navController != null) {
                val curDest = navController.currentDestination?.id

                val isOk = when (it) {
                    is Song -> (detailModel.currentAlbum.value?.id == it.album.id) magic (curDest != R.id.album_detail_fragment)
                    is Album -> (detailModel.currentAlbum.value?.id == it.id) magic (curDest != R.id.album_detail_fragment)
                    is Artist -> (detailModel.currentArtist.value?.id == it.id) magic (curDest != R.id.artist_detail_fragment)

                    else -> false
                }

                if (isOk) {
                    binding.navBar.selectedItemId = R.id.library_fragment
                }
            }
        }

        playbackModel.restorePlaybackIfNeeded(requireContext())

        logD("Fragment Created.")

        return binding.root
    }

    /**
     * Magic boolean logic that gets navigation working.
     * true true -> true |
     * true false -> false |
     * false true -> true |
     * false false -> false |
     */
    private infix fun Boolean.magic(other: Boolean): Boolean {
        return if (!this && !other) false else !(this && !other)
    }

    /**
     * Custom navigator code that has proper animations, unlike BottomNavigationView.setupWithNavController().
     */
    private fun navigateWithItem(navController: NavController, item: MenuItem): Boolean {
        if (navController.currentDestination!!.id != item.itemId) {
            // Create custom NavOptions myself so that animations work
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setEnterAnim(R.animator.nav_default_enter_anim)
                .setExitAnim(R.animator.nav_default_exit_anim)
                .setPopEnterAnim(R.animator.nav_default_pop_enter_anim)
                .setPopExitAnim(R.animator.nav_default_pop_exit_anim)
                .build()

            return try {
                navController.navigate(item.itemId, null, options)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        return false
    }

    /**
     * Handle the visibility of CompactPlaybackFragment. Done here so that there's a nice animation.
     */
    private fun handleCompactPlaybackVisibility(binding: FragmentMainBinding, song: Song?) {
        if (song == null) {
            logD("Hiding CompactPlaybackFragment since no song is being played.")

            binding.compactPlayback.visibility = View.GONE
            playbackModel.disableAnimation()
        } else {
            binding.compactPlayback.visibility = View.VISIBLE
        }
    }
}
