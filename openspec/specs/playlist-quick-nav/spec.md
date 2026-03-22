## ADDED Requirements

### Requirement: Fast scrollbar on playlist song list

The playlist detail `RecyclerView` SHALL display a vertical fast scrollbar that allows the user to drag a scroll thumb to quickly jump to any position in the song list.

#### Scenario: User drags the scrollbar thumb on a 200-song playlist
- **WHEN** the user touches and drags the scrollbar thumb downward on a playlist with 200 songs
- **THEN** the list SHALL scroll rapidly to the corresponding position, allowing the user to reach any point in the list without swiping through individual items

#### Scenario: Short playlist does not show scrollbar thumb
- **WHEN** the playlist contains few enough songs that all fit on screen
- **THEN** the scrollbar thumb SHALL NOT appear since scrolling is unnecessary

### Requirement: Search songs within a playlist

The playlist detail screen SHALL display a search bar above the song list. Typing into the search bar SHALL filter the displayed songs in real time, matching against song title or artist name (case-insensitive, substring match).

#### Scenario: User searches by song title
- **WHEN** the user types "月亮" into the search bar
- **THEN** only songs whose title contains "月亮" SHALL be displayed in the list

#### Scenario: User searches by artist name
- **WHEN** the user types "周杰伦" into the search bar
- **THEN** only songs whose artist name contains "周杰伦" SHALL be displayed in the list

#### Scenario: Search with no results
- **WHEN** the user types a query that matches no songs in the playlist
- **THEN** the song list SHALL be empty and an appropriate empty state SHALL be shown

#### Scenario: User clears the search bar
- **WHEN** the user clears all text from the search bar
- **THEN** the full unfiltered song list SHALL be displayed again

### Requirement: Platform filter chips

The playlist detail screen SHALL display platform filter chips (全部 / 网易云 / QQ音乐 / B站) below the search bar. Selecting a chip SHALL filter the displayed songs to only those from the selected platform. The "全部" (All) chip SHALL be selected by default.

#### Scenario: User selects the NetEase chip
- **WHEN** the user selects the "网易云" chip
- **THEN** only songs from the NetEase platform SHALL be displayed in the playlist song list

#### Scenario: User selects the QQ Music chip
- **WHEN** the user selects the "QQ音乐" chip
- **THEN** only songs from the QQ Music platform SHALL be displayed

#### Scenario: User selects the Bilibili chip
- **WHEN** the user selects the "B站" chip
- **THEN** only songs from the Bilibili platform SHALL be displayed

#### Scenario: User returns to "All" filter
- **WHEN** the user selects the "全部" chip after having a platform filter active
- **THEN** songs from all platforms SHALL be displayed

#### Scenario: Combined search and platform filter
- **WHEN** the user has both a search query ("love") and a platform filter ("QQ音乐") active
- **THEN** only songs from QQ Music whose title or artist contains "love" SHALL be displayed

### Requirement: Filtered song count display

When search or platform filter is active, the displayed song count SHALL reflect the number of filtered results, not the total playlist size.

#### Scenario: Filtered count with platform filter
- **WHEN** a playlist has 100 songs and the user selects the "网易云" filter, yielding 40 matches
- **THEN** the song count text SHALL display "40 首歌曲"

#### Scenario: Filtered count with search query
- **WHEN** a playlist has 100 songs and the user searches "rock", yielding 5 matches
- **THEN** the song count text SHALL display "5 首歌曲"

### Requirement: Search and filter in controller mode

When the app is in controller mode, the search bar and platform filter chips SHALL still be functional. Filtering SHALL operate on the in-memory list of songs fetched from the remote player device.

#### Scenario: Controller mode user searches playlist songs
- **WHEN** the app is in controller mode and the user types "海阔天空" into the search bar
- **THEN** the displayed songs SHALL be filtered to only those whose title or artist contains "海阔天空", using in-memory filtering on the remotely fetched song list

#### Scenario: Controller mode user applies platform filter
- **WHEN** the app is in controller mode and the user selects the "B站" chip
- **THEN** only Bilibili songs from the remotely fetched list SHALL be displayed
