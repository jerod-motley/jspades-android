# Card Movement & Phase Architecture

**Status:** Working proof-of-concept captured in `TestTrickPlayActivity` / `TestTrickPlayViewModel`.  
**Goal:** Document the successful approach for re-use and for porting to other platforms (iOS SwiftUI, web, etc.).

---

## Background — Why This Matters

Card animation was a persistent failure point in both the Android and iOS versions of this game. Multiple approaches were tried and scrapped:

- **iOS (SwiftUI):** `.animation()` modifiers on card offsets caused cards to snap unpredictably when state changed simultaneously with a transition; ultimately removed.
- **Android (early attempts):** Animating inside large composables that also changed game state caused recomposition loops and race conditions where cards would skip or disappear mid-flight.

The root cause in both cases was the same: **animation state and game state were entangled**. A single recomposition could reset an in-flight animation before it completed.

The `TestTrickPlayActivity` approach solves this by making animation a side-effect of phase transitions — not a direct consequence of state mutation.

---

## The Working Approach (TestTrickPlayActivity)

### Principle

> The ViewModel drives phases. The UI reacts to animation requests from a `SharedFlow`. Animation completion feeds **back** into the ViewModel to advance the phase. Game state and animation state never race against each other.

The key insight is a **strict one-way loop**:

```
ViewModel sets phase
    → phase triggers managePhase()
        → ViewModel emits AnimationRequest (SharedFlow)
            → UI animates the card
                → onAnimationComplete callback fires
                    → UI notifies ViewModel
                        → ViewModel advances to next phase
```

Nothing mutates game state while an animation is running. The UI is a dumb renderer of whatever the ViewModel asks it to move.

---

## Phases

Three phases cover one complete trick cycle:

| Phase | Meaning |
|---|---|
| `Play` | CPU/AI players take turns. ViewModel iterates the player array and emits one `AnimationRequest` per player whose card slot is `null`. |
| `PlayHuman` | Human player's turn — UI awaits a tap. (Currently a placeholder in the test harness; real game promotes here when it is the human's slot in the array.) |
| `PlayComplete` | All four players have a card assigned. UI detects this and triggers trick collection. |

**Phase transition rules:**
- `Play → Play` — after each CPU card animation completes (`onAnimationComplete` → `managePhase()` → `getTricks()` finds next null slot).
- `Play → PlayComplete` — `getTricks()` finds no more null slots; emits `PlayComplete`.
- `PlayComplete → Play` — after collection animations finish, ViewModel resets the player array (winner goes first) and sets phase back to `Play`.

There is no direct `PlayHuman → Play` transition in the test harness; in the real game a card-tap event takes that role.

---

## Player Array

The player array is the canonical ordering for one trick. It is a `List<Player>` where:

- **Index 0** is always the player who leads (won the last trick / dealt first hand).
- The remaining three slots are filled in **clockwise order**: e.g., if East leads → `[East, South, West, North]`.
- Each slot holds either `null` (hasn't played yet) or a `Card` (has played).

`getTricks()` iterates from index 0. The first slot with a `null` card is the current player to act. This means CPU players are processed automatically in turn order; the human player's slot simply waits for a UI event instead of an auto-generated card.

When a trick completes (`PlayComplete`), a winner is chosen, and `setupPlayerArray(winner)` rebuilds the array with the winner at index 0 and `null` cards in all slots.

---

## AnimationRequest

```
AnimationRequest(
    playerName : String,       // "North" | "South" | "East" | "West"
    card       : Card,         // the card being played
    position   : String,       // destination quadrant or collection destination
    originalPosition : String? // only set for collection animations
)
```

`position` values:
- `"north"` / `"south"` / `"east"` / `"west"` — play animation (off-screen → center quadrant).
- `"collect_to_north"` etc. — collection animation (current on-screen position → winner's off-screen origin).

The `originalPosition` field on a collection request identifies where the card currently sits on screen (its play position), so the animation can start from the correct pixel location rather than from off-screen.

---

## Animation Mechanics (Android / Compose)

### Card positions

All positions are relative to the screen center. Each played card lands at an offset from center proportional to the card's own size:

| Quadrant | End X | End Y |
|---|---|---|
| North | center | center − cardHeight |
| South | center | center + cardHeight |
| East | center + cardWidth | center |
| West | center − cardWidth | center |

Start position is off-screen in the corresponding direction (center ± (cardSize + 200 px buffer)).

### Animatable approach

Each card animation is a standalone Compose `@Composable` (`animateTrickPlayCard`) that:

1. Receives `(card, position, originalPosition, onAnimationComplete)`.
2. Uses `remember(card, position) { Animatable(startX/Y) }` — keyed so it resets when a new card/position arrives.
3. Runs X and Y animations in parallel inside a `coroutineScope { launch { animX.animateTo(...) }; launch { animY.animateTo(...) } }`, then joins both before calling `onAnimationComplete()`.
4. Duration: **500 ms** (`tween(500)`) for both play and collection.

Critically: **`remember` is keyed on `(card, position)`**. This means:
- Recompositions that don't change the card or position do not reset the animation.
- A new `AnimationRequest` for a new card gets a fresh `Animatable` starting from off-screen.

### The three rendering layers (inside one `Box`)

```
Box {
    HandView            ← static; cards disappear from here when played
    activeAnimations    ← cards currently in-flight (Animatable composables)
    completedAnimations ← cards at rest on screen (StaticCardAtCenter composables)
}
```

A card moves through three states:

1. **Visible in HandView** → user taps or CPU plays it.
2. **Added to `activeAnimations`** → `animateTrickPlayCard` runs; card flies toward center.
3. **Moved to `completedAnimations`** → animation completes; `StaticCardAtCenter` renders it at its final position.
4. **Removed from `completedAnimations`** → collection animation starts (card re-enters `activeAnimations` as a `collect_to_*` request), flies off-screen, then both sets are cleared.

Cards are never in `activeAnimations` and `completedAnimations` simultaneously — the `isAnimating` check prevents double-rendering during collection.

### Flow of events (one full trick)

```
1. Phase = Play
2. managePhase() → getTricks() → finds null slot for North
3. Emits AnimationRequest(North, card, "north")
4. UI: card flies from above screen to center-north (500 ms)
5. onAnimationComplete → addCardOnScreen(North) → onAnimationComplete() → managePhase()
6. getTricks() → finds null slot for East
7. Emits AnimationRequest(East, card, "east")
8. ... (repeat for South, West)
9. getTricks() → no null slots → setPhase(PlayComplete)
10. UI LaunchedEffect(PlayComplete) → animateTrickCollection()
11. ViewModel picks random winner (e.g., East), emits collect_to_east for all 4 cards
12. Each card flies off toward East's origin (500 ms)
13. Last collection onAnimationComplete → clearCardsOnScreen()
14. clearCardsOnScreen() → setupPlayerArray(East) → setPhase(Play)
15. Loop repeats
```

---

## The Missing Piece: Canonical Game Object

The test harness uses a `TestPlayer` stub and generates random cards. The real game has a `Game` data class (in `Game.kt`) and a `GameViewModel`, but **these are not shared across Activities**. Each activity that needs game state recreates or re-fetches it, leading to:

- State drift between `GameActivity`, `HandReviewActivity`, `KittyActivity`, `MatchResultsActivity`.
- No single source of truth for the current player array, trick state, or score.

### What a canonical game object should look like

Platform-agnostic requirements:

```
GameState {
    players         : List<Player>          // 4 players, index 0 = current leader
    currentTrick    : Trick                 // cards played this trick (nullable per player)
    hand            : Hand                  // cards dealt, kitty, trump suit, bid
    score           : Score                 // team book counts this hand
    match           : Match                 // hands played, team game counts
    phase           : GamePhase             // the current phase (replaces TestPhase)
}
```

This object must be:
- **Owned by a single ViewModel (or equivalent)** that survives across screen transitions.
- **Observable** — UI subscribes to diffs, not the whole object.
- **Never mutated during animation** — phase transitions schedule the next mutation after animation callbacks fire.

On Android, the right home is a `GameViewModel` scoped to the Activity task (or a `NavGraph`-scoped VM if using Compose Navigation) so all screens share the same instance without passing it via Intent extras.

---

## Platform-Agnostic Design Notes

The phase + animation-request pattern maps cleanly to any reactive UI framework:

| Concept | Android (Compose) | iOS (SwiftUI) | Web (React) |
|---|---|---|---|
| Phase | `StateFlow<Phase>` in ViewModel | `@Published var phase` in ObservableObject | `useState` + `useReducer` |
| Animation request | `SharedFlow<AnimationRequest>` | `PassthroughSubject` or `AsyncStream` | Event emitted to animation queue |
| Per-card animator | `Animatable` (reset on key change) | `withAnimation` + `.offset` keyed by id | CSS transition or Framer Motion keyed component |
| Completion callback | `onAnimationComplete` lambda | `.onAppear`/`DispatchQueue.main.async` after duration | `onAnimationEnd` or `useEffect` cleanup |
| Canonical game state | `ViewModel` (retained across recompositions) | `@StateObject` / `@EnvironmentObject` | Context or Zustand/Redux store |

The fundamental rule holds everywhere:  
**Mutate game state only in a completion callback or a phase transition handler — never inside the animation block itself.**

---

## Files

| File | Purpose |
|---|---|
| `ui/TestTrickPlayActivity.kt` | Composable UI — HandView, animateTrickPlayCard, StaticCardAtCenter, rendering layers |
| `ui/TestTrickPlayViewModel.kt` | Phase machine — player array, AnimationRequest emission, trick collection, clearCardsOnScreen |
| `data/Game.kt` | Real Game data class (not yet wired to the test harness) |
| `viewmodel/GameViewModel.kt` | Real game ViewModel (not yet shared across Activities) |
