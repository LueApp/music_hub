## ADDED Requirements

### Requirement: WebView-based platform login

The system SHALL provide a WebView-based login flow for NetEase Cloud Music and QQ Music. A single `PlatformLoginActivity` SHALL load the platform's official web login page. For NetEase, the URL SHALL be `https://music.163.com/#/login`. For QQ Music, the URL SHALL be `https://y.qq.com/`. The WebView SHALL enable JavaScript and DOM storage.

#### Scenario: NetEase login via WebView
- **WHEN** the user taps "Login" for NetEase on the For You tab
- **THEN** a WebView opens showing the NetEase login page where the user can enter credentials

#### Scenario: QQ Music login via WebView
- **WHEN** the user taps "Login" for QQ Music on the For You tab
- **THEN** a WebView opens showing the QQ Music login page

### Requirement: Auth cookie detection and extraction

The system SHALL monitor `CookieManager` after each page load in the login WebView. For NetEase, login success SHALL be detected when cookies contain `MUSIC_U=`. For QQ Music, login success SHALL be detected when cookies contain `qqmusic_key=`. Upon detection, the system SHALL extract all relevant cookies and finish the activity with `RESULT_OK`.

#### Scenario: NetEase login detected
- **WHEN** the user completes login on the NetEase WebView and `MUSIC_U` cookie appears
- **THEN** the system extracts cookies, stores them, and closes the WebView returning success

#### Scenario: QQ Music login detected
- **WHEN** the user completes login on the QQ Music WebView and `qqmusic_key` cookie appears
- **THEN** the system extracts cookies, stores them, and closes the WebView returning success

#### Scenario: User cancels login
- **WHEN** the user presses back without completing login
- **THEN** the activity finishes with `RESULT_CANCELED` and no cookies are stored

### Requirement: Secure cookie storage

Auth cookies SHALL be stored using `EncryptedSharedPreferences` from `androidx.security:security-crypto`. Cookies SHALL be keyed by platform name (e.g., `auth_netease`, `auth_qqmusic`). The stored value SHALL be the full cookie string needed for API requests.

#### Scenario: Cookies persisted across app restarts
- **WHEN** the user logs in and restarts the app
- **THEN** the stored auth cookies are still available and the user remains logged in

#### Scenario: Cookies encrypted at rest
- **WHEN** auth cookies are stored
- **THEN** they are encrypted via EncryptedSharedPreferences and not readable as plain text

### Requirement: Auth cookie injection into API requests

The system SHALL inject stored auth cookies into HTTP requests for authenticated API calls. The `PlatformAuthManager` SHALL provide an `injectAuth(request, platform)` method that adds the stored cookie header to an OkHttp request.

#### Scenario: Cookie injected into NetEase recommendation request
- **WHEN** the system fetches NetEase daily recommendations
- **THEN** the request includes `Cookie: MUSIC_U={stored_value}` header

#### Scenario: Cookie injected into QQ Music recommendation request
- **WHEN** the system fetches QQ Music recommendations
- **THEN** the request includes `Cookie: qqmusic_key={stored_value}; qm_keyst={stored_value}` header

### Requirement: Login state management

The `PlatformAuthManager` SHALL expose `isLoggedIn(platform): Boolean` to check if valid auth cookies exist for a platform. It SHALL expose `logout(platform)` to clear stored cookies for a platform.

#### Scenario: Check login state
- **WHEN** the For You tab loads
- **THEN** it checks `isLoggedIn("netease")` and `isLoggedIn("qqmusic")` to determine whether to show login buttons or recommendation lists

#### Scenario: Logout clears cookies
- **WHEN** the user taps "Logout" for a platform
- **THEN** stored cookies for that platform are cleared and the For You tab shows the login button again

### Requirement: Handle expired auth sessions

The system SHALL detect expired auth sessions when recommendation API calls return 401/403 or error responses indicating authentication failure. The system SHALL update the login state to logged-out and prompt the user to re-login.

#### Scenario: Expired session detected
- **WHEN** a recommendation fetch returns a 401/403 or auth error
- **THEN** the system clears stored cookies for that platform and shows "Session expired, please login again" with a login button
