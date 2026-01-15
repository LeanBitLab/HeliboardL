// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EXPERIMENTAL_SUFFIX = "dictionaries_experimental/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    // HeliboardL fork repo
    const val GITHUB = "https://github.com/LeanBitLab/HeliboardL"
    const val LICENSE = "$GITHUB/blob/main/LICENSE"
    // Original HeliBoard wiki and community links
    const val ORIGINAL_GITHUB = "https://github.com/Helium314/HeliBoard"
    const val LAYOUT_WIKI_URL = "$ORIGINAL_GITHUB/wiki/2.-Layouts"
    const val WIKI_URL = "$ORIGINAL_GITHUB/wiki"
    const val CUSTOM_LAYOUTS = "$ORIGINAL_GITHUB/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$ORIGINAL_GITHUB/discussions/categories/custom-colors"
}

val combiningRange = 0x300..0x35b
