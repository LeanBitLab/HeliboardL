# HeliboardL

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/heliboardl_banner_dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/images/heliboardl_banner_light.svg">
  <img alt="HeliboardL Banner" src="docs/images/heliboardl_banner_light.svg">
</picture>

[![Download](https://img.shields.io/github/v/release/LeanBitLab/HeliboardL?label=Download&style=for-the-badge&color=7C4DFF)](https://github.com/LeanBitLab/HeliboardL/releases/latest) [![Downloads](https://img.shields.io/github/downloads/LeanBitLab/HeliboardL/total?style=for-the-badge&color=7C4DFF&label=Downloads)](https://github.com/LeanBitLab/HeliboardL/releases)

**HeliboardL** is a fork of [HeliBoard](https://github.com/Helium314/HeliBoard) - a privacy-conscious and customizable open-source keyboard based on AOSP/OpenBoard.

This fork adds **AI-powered features** using the Gemini API while maintaining the offline-first philosophy of the original.

## What's New in HeliboardL

- **🤖 Gemini AI Proofreading** - Fix grammar and spelling with one tap (Standard only)
- **🌐 AI Translation** - Translate selected text directly (Standard only)
- **🎨 Modern UI** - "Squircle" key backgrounds and refined icons (incognito, etc.)
- **🕵️ Clear Incognito Mode** - Distinct "Hat & Glasses" icon for clear visibility
- **🔒 Privacy Choices** - Choose **Standard** (Offline-first with opt-in AI) or **Offline** (Hard-disabled network) versions
- **📥 Gesture Library Downloader** - Easier setup for glide typing

## Screenshots

<table>
  <tr>
    <td><img src="docs/images/Screenshot1.png" height="500" alt="Screenshot 1"/></td>
    <td><img src="docs/images/Screenshot2.png" height="500" alt="Screenshot 2"/></td>
    <td><img src="docs/images/Screenshot3.png" height="500" alt="Screenshot 3"/></td>
    <td><img src="docs/images/Screenshot4.png" height="500" alt="Screenshot 4"/></td>
    <td><img src="docs/images/Screenshot5.png" height="500" alt="Screenshot 5"/></td>
    <td><img src="docs/images/Screenshot6.png" height="500" alt="Screenshot 6"/></td>
  </tr>
</table>


## Download

You can download the latest release from the [GitHub Releases](https://github.com/LeanBitLab/HeliboardL/releases) page.

### 📦 Choose Your Version

We provide two distinct versions. **Note:** Both versions use the same package name (`helium314.keyboard.l`) and signature. You can only have **one** installed at a time.

#### 1. Standard Version (`-standard-release.apk`)
*   **Features:** Full suite including **AI Proofreading**, **AI Translation**, and **Gesture Library Downloader**.
*   **Permissions:** Request `INTERNET` permission (used *only* when you explicitly use AI features or download libraries).
*   **Best For:** Users who want smart features alongside privacy.

#### 2. Offline Version (`-offline-release.apk`)
*   **Features:** All UI/UX refinements (Squircle keys, new Icons) but **excludes** all AI and network features.
*   **Permissions:** **NO INTERNET PERMISSION** in the manifest. Guaranteed at the OS level.
*   **Best For:** Privacy purists who require a hard guarantee that no data can ever leave the device.

## Original HeliBoard Features

<ul>
  <li>Add dictionaries for suggestions and spell check</li>
  <li>Customize keyboard themes (style, colors and background image)</li>
  <li>Customize keyboard layouts</li>
  <li>Multilingual typing</li>
  <li>Glide typing (<i>requires closed source library</i>)</li>
  <li>Clipboard history</li>
  <li>One-handed mode</li>
  <li>Split keyboard</li>
  <li>Number pad</li>
  <li>Backup and restore settings</li>
</ul>

For original feature documentation, visit the [HeliBoard Wiki](https://github.com/Helium314/HeliBoard/wiki).

## Setup

### Gemini API Key (for AI features)
1. Get your free API key from [Google AI Studio](https://aistudio.google.com/apikey)
2. Go to HeliboardL Settings → Advanced → Gemini API Key
3. Enter your API key
4. Change Gemini model to **gemini-2.5-flash** or **gemini-3n-e2b-it** for best performance.

### AI Translation Setup
1.  Go to Settings → Toolbar → Customize Toolbar and add the "Translate" key.
2.  Go to **Settings → Advanced → Translation Target Language** and select your desired output language.

> [!IMPORTANT]
> **Privacy Notice**: While HeliboardL itself is open-source and respects your privacy, using the **free tier** of the Google Gemini API means your input data may be used by Google to improve their models.
> - Using AI features is **optional**.
> - **Do not process sensitive information** (passwords, credit card numbers, private addresses) using the AI Proofreading or Translation features.
> - The **Offline Version** completely removes this code and permission.

### Gesture/Glide Typing
**Standard Version:** Use the built-in downloader in Settings → Advanced → Load Gesture Typing Library.

**Offline Version:**
Since network access is disabled, you must manually install the library:
1.  Download the library file: [libjni_latinimegoogle.so](https://github.com/Helium314/HeliBoard/blob/master/app/src/main/jniLibs/arm64-v8a/libjni_latinimegoogle.so) (for arm64)
2.  Go to Settings → Advanced → Load Gesture Typing Library.
3.  Select "Load from file" and pick the downloaded `.so` file.

## Contributing

For issues specific to HeliboardL features, please open an issue in this repository.

For issues with core HeliBoard functionality, please report to the [original HeliBoard repository](https://github.com/Helium314/HeliBoard/issues).

## License

HeliboardL (as a fork of HeliBoard/OpenBoard) is licensed under **GNU General Public License v3.0**.

See [LICENSE](/LICENSE) file.

## Credits

### Original Projects
- **[HeliBoard](https://github.com/Helium314/HeliBoard)** by Helium314 - The excellent keyboard this fork is based on
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- Original icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt)

### HeliboardL
- Built with ❤️ by [LeanBitLab](https://github.com/LeanBitLab)

---

*HeliboardL • Privacy-focused keyboard with AI enhancements*
