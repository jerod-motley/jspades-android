# Architecture — Platform Agnostic

Purpose
- Provide a concise, platform-agnostic architecture for a clean, maintainable BidWhist app.
- Capture the canonical data model, phase machine, event-driven transitions (fire-and-forget), and ViewModel ownership so ports to other platforms are straightforward.

Principles
- Single Source of Truth: one canonical game object (GameState) owned by a single controller/ViewModel.
- Event-driven: UI and other subsystems submit events/commands to the engine; the engine processes them and emits a new snapshot.
- Phase machine: phases are authoritative; phase handlers drive side-effects (including animation requests).
- Animation as side-effect: animations are driven by requests from the authoritative layer and never mutate canonical state directly.

1) Canonical Data Model (GameState)
- The app must expose one immutable-ish snapshot object representing the entire game state. Example shape:

```
GameState {
  players: List<Player>           // 4 players, index 0 = leader
  currentTrick: Trick             // mapping playerIndex -> Card? (null means not played)
  hands: Map<PlayerId, Hand>      // cards held by each player
  kitty: Hand                     // kitty cards if applicable
  bid: Bid?                       // current bid info
  trump: Suit?                    // current trump
  score: Score                    // per-team scoring
  phase: GamePhase                // phase enum describing high-level flow
  metadata: { timestamps, ids }   // optional: for telemetry and determinism
}

Player {
  id: String
  name: String
  hand: Hand (read-only projection)
  runtimeFlags: { didBid, currentCard, seatIndex? }
}
```

- Do NOT model `seats[]` or other legacy arrays that duplicate player ordering. Use `players[]` where index 0 is canonical leader; re-order the list when winner rotates.
- Keep mutations centralized: only the engine (or the single authoritative controller) may mutate `GameState`.

2) Phase Machine
- Model phases as a distinct enum, e.g. `GamePhase { Lobby, Deal, Bid, Play, TrickResolve, Score, Finished }`.
- Drive behavior with a `switch`/`when` on `phase` inside the controller/engine. Each case implements the authoritative actions for that phase.

Pattern:
- `onStateSnapshot(GameState s) { switch (s.phase) { case Play: handlePlay(s); break; ... } }`

- Each phase handler may:
  - Inspect `GameState` snapshot
  - Submit internal or external events (AI decisions, timers)
  - Emit side-effects as *requests* (animation requests, sound events, network sends)
  - Transition to a new phase by submitting an engine event (processed serially)

3) Event-driven Phase Changes (Fire-and-Forget)
- Events/Commands: UI and subsystems create events (e.g., `PlaceBid`, `PlayCard`, `StartNextHand`) and enqueue them to the engine's single processing queue.
- Engine processes events serially (single-threaded loop or protected by a mutex) to avoid race conditions. Each processed event results in a new `GameState` emission.

Background sleeping, callbacks and fire-and-forget:
- Long-running work (AI deliberation, network) should run off the main/UI thread; the engine emits interim phases or posts events when finished.
- Use fire-and-forget for UI triggers: UI submits an event and returns; listen to `GameState` updates to observe completion.
- For time-based delays, schedule timers inside the engine or a dedicated scheduler; do not block the engine main loop.

4) ViewModel / Controller Ownership
- The `ViewModel` (or equivalent controller) is the canonical holder of `GameState` and the single source clients subscribe to.
- Responsibilities:
  - Maintain the event queue and process events through the engine
  - Emit `GameState` snapshots via an observable (StateFlow / ObservableObject / Redux store)
  - Expose a minimal command API to UI (submitEvent(event))
  - Emit side-effect requests (AnimationRequest, SoundRequest) separately from snapshot stream

- The UI must be passive: it renders `GameState`, subscribes to side-effect streams (animation requests), and submits user events. UI components should not attempt to mutate `GameState` directly.

5) Animation & UI Side-Effects
- Make animations pure side-effects driven by a dedicated stream (e.g., `SharedFlow<AnimationRequest>`). AnimationRequest includes `player`, `card`, `position`, and optionally `origin` for collection.
- UI executes animations and reports back completion to the controller via `submitEvent(AnimationCompleted(...))` or a dedicated callback.
- Do not mutate `GameState` while an animation is running. Schedule state changes to occur after `AnimationCompleted` events.

6) Concurrency & Determinism
- Engine processes events deterministically on a single thread or within a guarded critical section.
- Emit full snapshots after each event handling to make UI rehydration and testing simple.
- Avoid partial mutations; prefer building a new `GameState` from the prior snapshot using pure functions.

7) Cross-screen sharing
- Scope the ViewModel to a lifecycle that covers all related screens (Activity task / navigation graph / app-scoped store). This prevents multiple copies of game state and view-model drift.

8) Portability notes (iOS / Web)
- Replace the ViewModel + StateFlow with platform equivalents:
  - iOS: `ObservableObject` / `@Published` or Combine publishers
  - Web: Redux / Zustand / RxJS
- Maintain the same event queue model and phase switch logic; this keeps the engine portable and testable.

9) Testing & Debugging
- Build the engine as a pure module with no UI dependencies so it can be unit tested. Provide deterministic helpers to inject RNG seeds and timers.
- Expose a debug stream of events and snapshots to reproduce issues in tests and across platforms.

10) Practical recommendations
- Keep the canonical `GameState` small and serializable (for save/load and server sync).
- Avoid duplicating player state in UI-specific VMs; if a UI needs derived state, compute it from `GameState` on each snapshot.
- Use short-lived side-effect streams (animation, audio) rather than embedding UI concerns into the canonical state.

Summary
- The simplest, most portable architecture centers a single `GameState` owned by a controller/ViewModel, drives behavior with a phase switch, processes events on a single queue, and treats animations as UI-only side-effects with completion callbacks that feed state transitions. This pattern reduces race conditions, simplifies testing, and makes cross-platform ports straightforward.
