## ADDED Requirements

### Requirement: Discover bottom navigation tab

The system SHALL add a "Discover" (发现) tab as the 5th item in the bottom navigation bar. The tab SHALL use an `ic_discover` compass icon. The tab id (`nav_discover`) SHALL match the navigation graph destination id.

#### Scenario: Discover tab visible
- **WHEN** the app launches
- **THEN** a 5th "Discover" tab icon is visible in the bottom navigation bar

#### Scenario: Discover tab navigates to discover fragment
- **WHEN** the user taps the Discover tab
- **THEN** the app navigates to the DiscoverFragment

### Requirement: Tabbed sections within Discover

The DiscoverFragment SHALL contain a `TabLayout` with `ViewPager2` providing three sections: "排行榜" (Charts), "歌单" (Browse), and "推荐" (For You). Each section SHALL be a nested fragment within the ViewPager2.

#### Scenario: Switch between sections
- **WHEN** the user swipes or taps a tab label
- **THEN** the corresponding section content is displayed

#### Scenario: Charts section is default
- **WHEN** the user first opens the Discover tab
- **THEN** the Charts section is displayed by default

### Requirement: Chart list display

The Charts section SHALL display chart cards grouped by platform. Each card SHALL show the chart name, platform badge (icon or label), and optionally update frequency. Tapping a chart card SHALL navigate to a chart detail view.

#### Scenario: Chart cards displayed
- **WHEN** the Charts section loads
- **THEN** chart cards from NetEase, QQ Music, and Bilibili are displayed with platform badges

#### Scenario: Navigate to chart detail
- **WHEN** the user taps a chart card
- **THEN** the app navigates to ChartDetailFragment showing the chart's songs

### Requirement: Discovery song row with preview and add actions

Each song in a discovery list (chart detail, browsed playlist, recommendations) SHALL display title, artist, cover art, and platform badge. Each row SHALL have two action buttons: a play button for deep link preview and an add (+) button for adding to library/playlist.

#### Scenario: Preview a song via deep link
- **WHEN** the user taps the play button on a discovery song
- **THEN** the system launches the song's deep link via DeepLinkLauncher, opening the native platform app

#### Scenario: Add a song to library
- **WHEN** the user taps the add (+) button on a discovery song
- **THEN** a bottom sheet appears with options "添加到曲库" (Add to Library) and "添加到歌单..." (Add to Playlist)

#### Scenario: Add to library from bottom sheet
- **WHEN** the user selects "添加到曲库" from the bottom sheet
- **THEN** the song is saved to the local library via `repository.insertSong()` and a confirmation toast is shown

#### Scenario: Add to playlist from bottom sheet
- **WHEN** the user selects "添加到歌单" from the bottom sheet
- **THEN** a playlist picker dialog appears listing all local playlists, and selecting one adds the song to that playlist

#### Scenario: Song already in library
- **WHEN** the user tries to add a song that already exists in the library (same platform + platformSongId)
- **THEN** the system shows a toast indicating the song is already in the library and does not create a duplicate

### Requirement: Loading and error states

All discovery data fetching SHALL show a loading indicator during fetch and an error state with retry button on failure.

#### Scenario: Loading state shown
- **WHEN** chart songs or browse playlists are being fetched
- **THEN** a progress indicator is displayed

#### Scenario: Error with retry
- **WHEN** a fetch operation fails
- **THEN** an error message is shown with a "重试" (Retry) button that re-triggers the fetch

### Requirement: Discover works in all app modes

The Discover tab SHALL function identically in standalone, player, and controller modes. Discovery is a local browsing feature. In controller mode, deep link previews launch on the controller device, and songs are added to the controller's local library.

#### Scenario: Discover in controller mode
- **WHEN** the app is in controller mode and the user uses the Discover tab
- **THEN** all discovery features work locally on the controller device (browsing, previewing, adding to local library)
