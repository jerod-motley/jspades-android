# jSpades2

jSpades2 is a digital implementation of the card game Spades. It bundles several playable modes (team and solo variants), an offline AI opponent, a set of built-in challenges, and common gameplay options. A reference implementation contains the rules, help pages, and the game logic that informed this project.

Key points
- **Core gameplay**: Standard Spades trick-taking rules (spades are permanent trumps). Players bid before each hand and score points for making bids. Overtricks ("bags") are tracked and penalized when accumulated.
- **AI & multiplayer**: Includes CPU opponents and local multiplayer support classes; the code references `PlayerCpu` and `PlayerMulti`. Online multiplayer was considered but depends on available infrastructure and player base.

Modes (available game types)
- **jSpades (two-team)**: Team-based play where teammates share a single team bid. Typical 4-player, 13-cards-per-player game with team scoring.
- **Classic Mode (four-player)**: Individual bids per player (including Nil / blind-nil behavior). Uses a standard 52-card deck, supports blind/nil bids and limited card-passing for blind bids.
- **Kitty Mode (four-player, kitty)**: Uses a 54-card deck (jokers) or variant; deals 12 cards per player and creates a 6-card kitty. Players who hold a special card (configurable deuce/ace behavior) may claim the kitty and exchange cards.
- **Solo Modes (four/three/two player solo)**: Players play individually rather than as fixed teams. Variants include Four Solo, Three Solo, and Two Solo modes with per-player bidding and scoring.
- **Two/Three Player Variants**: Adjusted deal sizes and turn/order logic to support 2- and 3-player Spades variants.

Challenges and achievements
- The app contains a set of built-in challenges (examples): "Walk All Kings", "Get X Books", "No Aces", "Get All Books", "By Yourself", "Double Bid", "Set Two", "Walk 4 Ladies", etc. Challenges are mapped to particular game modes and can be selected from the Challenge UI.
- Achievements and challenge progress are persisted locally in the app database.

Options and rules you can configure
- **Max score**: two presets (named in settings as `low` and `high`) — maps to 250 or 500 points maximum.
- **Losing score**: two presets (`low` and `high`) — maps to −250 or −500 (a score below this ends the game for the team/player).
- **Deuce behavior**: toggle whether the deuce (2♠ or alternatively the deuce/ace depending on deck variant) is treated specially ("deuce wild" on/off).
- **Blind bidding**: supported in modes that allow it (blind/nil bids with special scoring multipliers).

Scoring summary
- Successful bids typically score 10 points per book (trick) bid; large/10+ bids may score double (per classic/jSpades rules implemented). Blind bids usually use a 20× multiplier. Overtricks (bags) are counted and accumulated; reaching 10 bags triggers a −100 penalty and bag counters are reduced.

Implementation notes (reference implementation)
- Persistent storage: lightweight SQLite DB seeded with settings, challenges, and basic tables for games/players/hands.
- Decks: Several deck variants exist (52-card, 54-card with jokers, a "jokers but no deuces" variant) to support different modes. Note: in Spades variants that include jokers, jokers are treated as spades (typically the highest spade ranks) and do not require their own separate suit — they are played and compared as spades.
- Code organization: Mode-specific logic lives in subclasses of `Game` (e.g. `GameFourClassic`, `GameFourKitty`, `GameFourSolo`, `GameTwoPlayer`, `GameThreePlayer`). UI controllers and screens include help pages, challenge lists, and settings screens.

Where to look in the source (reference implementation)
- Help pages and mode-specific rules: look for help HTML or documentation files in the project.
- Mode implementations: search for `Game*` classes (for example `GameFourClassic`, `GameFourKitty`, `GameFourSolo`, `GameTwoPlayer`, `GameThreePlayer`).
- Challenges and seeded data: search for a data helper or seed data that creates the challenge list and default settings.
- Options UI: check the settings screen or settings controller/viewmodel implementations.

If you want, I can:
- Turn this into a more user-facing README section (with screenshots or play instructions), or
- Produce a distilled CHANGELOG of differences between a reference implementation and this `jSpades2` project.
