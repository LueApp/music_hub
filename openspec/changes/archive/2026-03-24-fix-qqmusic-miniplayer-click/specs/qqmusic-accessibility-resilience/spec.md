## ADDED Requirements

### Requirement: UI tree dump on strategy failure
When all ID-based click strategies fail to find a matching element, PlayerAccessibilityService SHALL log a dump of the visible accessibility node tree to aid diagnosis of changed resource IDs.

#### Scenario: All ID strategies fail
- **WHEN** `tryClickMiniPlayer()` finds no nodes for any of the known resource IDs AND retryCount >= 2
- **THEN** the service SHALL call `dumpUITree()` which logs each visible node's resource ID, class name, bounds, clickable state, and content description via `Log.d`

#### Scenario: Dump is limited in scope
- **WHEN** `dumpUITree()` is called
- **THEN** it SHALL log at most 50 nodes, traversing the tree breadth-first to top-level nodes and their immediate children only

#### Scenario: Dump does not repeat
- **WHEN** a dump has already been logged for the current click request (same `requestClickMiniPlayer()` invocation)
- **THEN** subsequent retries SHALL NOT dump again

### Requirement: Heuristic mini player detection fallback
When ID-based strategies fail, PlayerAccessibilityService SHALL attempt to find the mini player by structural properties (position, size, class) before falling through to the hardcoded coordinate fallback.

#### Scenario: Bottom-bar heuristic finds mini player
- **WHEN** no known resource IDs are found AND the accessibility tree contains a ViewGroup in the bottom 20% of the screen that is wider than 80% of screen width and taller than 40dp equivalent
- **THEN** the service SHALL gesture-click the left side of that element (album art area) and log the element's details for future ID extraction

#### Scenario: Bottom-bar heuristic finds no match
- **WHEN** no known resource IDs are found AND no element matches the heuristic criteria
- **THEN** the service SHALL fall through to the existing hardcoded coordinate fallback (`tryFallbackClick()`)

#### Scenario: Heuristic skips navigation bars
- **WHEN** a candidate element in the bottom 20% of the screen has a resource ID containing "nav" or "tab" or "bottom_bar"
- **THEN** the service SHALL skip that element and continue searching

### Requirement: Updated resource IDs
PlayerAccessibilityService SHALL use the current QQ Music resource IDs for the music card and mini player bar. The IDs SHALL be updated based on runtime UI tree dump analysis.

#### Scenario: IDs match current QQ Music version
- **WHEN** QQ Music is launched via deep link AND the accessibility service attempts to find the music card or mini player
- **THEN** the service SHALL use resource IDs that match the currently installed QQ Music version's layout

#### Scenario: IDs stored as named constants
- **WHEN** resource IDs are referenced in the code
- **THEN** they SHALL be defined as named constants (not inline strings) for easy updating
