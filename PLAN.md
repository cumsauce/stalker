# Stalker — Plan

Draft, 2026-10-14. "Stalker" (mod id `stalker`) is a working name; renaming before the first push is cheap. Written to be executed milestone by milestone by GLM Flash — every number named here is a default, all of them live in one config file, and every milestone ends with a build, a test run and a manual checklist.

## Status (2026-10-14)

M0–M6 implemented in one pass (fan-out of four agents over the packages): skeleton, bindings/attachments, manager state machine + senses + cover, `StalkerBrain` activity + `registerBrainGoals` mixin, strikes/chase/stun + cues + strike-tell payload + client camera snap, bribes + commands + config, 13 unit tests. `./gradlew build` green; dedicated-server smoke test boots with no mixin errors (world-level manual checklists below still pending). Remaining: M7 (server game tests), entity-removal event wiring (`StalkerManager.onVillagerRemoved` is exposed but unhooked — an orphan fallback clock covers it), localization (`en_us.json`), debug particles, and the in-game milestone checklists. Toolchain note: this machine needs `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.

## Goal

A server-side Fabric mod for Minecraft 26.3 (the same toolchain as earshot: loader 0.19.5, Fabric API 0.162.0+26.3, loom 1.18.2, Gradle 9.7.1, Java 25, Mojang mappings, plain Java) in which a random nearby villager silently fixates on a player and begins following them. It keeps its distance, hides behind trees and walls, peeks out to watch, and goes still when spotted. If it reaches you from behind it "gets" you: mostly a music sting with blindness and poison while it closes in and keeps hitting you; rarely it snatches you away, teleporting you a hundred blocks from where you stood. You can pay it off — what counts as payment depends on its profession, and a payment it doesn't value is fatal. Survive its nine-second chase and it collapses, dazed, and you can break away for good.

The villager stays a completely vanilla `Villager`: no custom entity, no custom spawn, no new texture. The horror is that it is *just a villager*, and everyone else on the server can trade with it.

Non-goals: client-only operation (all gameplay logic is server-side), custom audio assets (vanilla sound events, pitched and volume-shaped; custom `.ogg` stingers are optional polish), new items or blocks, changing vanilla villager behaviour for unbound villagers, single-player-only assumptions (works on dedicated servers; multi-player supported).

## What I checked empirically (2026-10-14, before executing)

1. **Persistence: Data Attachment API over hand-rolled saved data.** Fabric's docs position [Data Attachments](https://docs.fabricmc.net/develop/serialization/data-attachments) as the modern way to attach codec-serialized data to entities, players and levels in 1.21+, with automatic save and configurable copy-on-death/dimension-travel; [PersistentState](https://wiki.fabricmc.net/tutorial:persistent_states) remains the tool for global maps. The plan therefore stores the binding as attachments on the two entities (phase + partner UUID on both the villager and the player, blacklist history on the player) instead of a custom `Bindings` saved-data class — less code, and death/dimension handling comes free. M1 verifies the attachment API's exact 26.3 shape (`AttachmentRegistry`, `AttachmentType.builder`) via genSources, same as every other name.
2. **Villager AI: the community-standard layer is the Brain, not GoalSelector, and not raw navigation overrides.** Villagers have used the Brain system since 1.14; the documented way to add behaviour is a custom `Activity` with a memory-gated task list injected at `initBrain` HEAD ([Fabric wiki: villager activities](https://wiki.fabricmc.net/tutorial:villager_activities), with a full example repo). The wiki does not endorse an external manager driving `navigation.moveTo` past the brain — that is the uncharted part of the original plan. Revised approach (Risks #1): the manager stays as the *orchestrator* (rolls, senses, phase machine, timers — all the parts that are not movement), but movement executes as one custom `Activity` whose tasks are gated on a custom `MemoryModuleType` (STALKING_TARGET present). Memory-gating means an unbound villager's brain is byte-for-byte vanilla behaviour, and no `stopAll` fighting is needed. Fallback ladder unchanged, but now secondary.
3. **Prior art: From The Fog** ([repo](https://github.com/LunarEclipseStudios/From-The-Fog), [mechanics](https://minecraft-horror.fandom.com/wiki/From_the_Fog_(Herobrine))) validates the design vocabulary — spawn out of sight, disappear-on-look, cadence config — but is a datapack (no Java to learn from) and is **CC BY-NC-SA with an explicit no-AI-generated-forks clause: take nothing from it, not even its config vocabulary verbatim.** Its "vanish when looked at" is the opposite of our design on purpose: our villager hides *physically* behind cover instead of despawning, which is strictly harder but is what makes it a villager and not a glitch.
4. **Cost caution from the activities tutorial:** wide per-villager searches (their POI example searches a 48-block radius) are the thing that gets expensive with many villagers. The cover search is exactly that shape — it stays throttled to one search per 2 s per villager and reuses one clip batch, as planned.

## Decisions to confirm

Cheap to change now, annoying later. Defaults in parentheses.

1. **The rare teleport moves the player** (~15 % of strikes: the *player* is teleported ~100 blocks to a safe landing spot up to 3 blocks above ground; the villager stays, is satisfied, and loses interest for 10 minutes). Read again — "small chance of teleporting 100 blocks away up to 3 in the air" was ambiguous between moving the player and the villager relocating; "the villager got you" reads as the player. (Default: player.)
2. **Working name / mod id** (`stalker`, package `io.github.cumsauce.stalker`, group as earshot).
3. **A satisfied stalker won't rebind the same player for 10 minutes** (blacklist per player+villager pair).
4. **Nitwits value nothing, not even emeralds** — every bribe is a pretend-accept (default on, config `nitwitAcceptsEmerald = false`).
5. **The random bind has no conditions** beyond: survival/adventure mode, an eligible adult villager within 48 blocks, no existing binding. No night-only or alone-only rule by default; both exist as config switches (`requireNight`, `requireAlone`, default false).

## The loop

```
                 random roll / /stalker start
                          │
                          ▼
   ┌─────────── STALK ───────────┐        bribe ≥ threshold ──► accept, item taken,
   │  orbit at 12–24 blocks,     │──────► (same in COVER)      unbind, blacklist
   │  approach only unseen       │
   │                             │        bribe < threshold ──► pretend-accept: sounds
   │  spotted (seen, in cone)    │                          identical, then CHASE
   ▼                             │
 COVER ── hide behind block,     │
   peek cycles, unspotted ───────┘
   │
   │ within 2.2 blocks, roughly behind, unseen
   ▼
 STRIKE ──┬─ 15 %: player teleported ~100 blocks, unbind, blacklist
          └─ 85 %: sting + camera-snap tell, BLINDNESS 9 s
                     │
                     ▼
                  CHASE ── stays out of sight, closes in, hits every 1.1 s,
                  │        damage and poison climb with each hit
                  │ 9 s survived ── or ── player dies (unbind, blacklist)
                  ▼
                 STUN ── 8 s dazed, no movement
                  │ player > 40 blocks away ──► unbind, blacklist
                  └ timeout ──► back to STALK (it will re-close)
```

Bindings survive restarts, chunk unloads and dimension changes: the pair is stored by UUID in the world's saved data, and a stalker that falls too far behind or gets unloaded quietly relocates to somewhere out of sight, 48–64 blocks from you, in your dimension.

## Mechanics

### 1. Binding

- Every server minute, for each eligible player, roll `bindChancePerMinute` (default 0.004 — roughly once per four hours per player). `/stalker start [player]` forces it.
- Eligible villager: adult (`!isBaby()`), not already bound to anyone, not blacklisted against this player, within 48 blocks of the player, same dimension. Babies never stalk.
- On bind: `setPersistenceRequired()` (it never despawns), `stopRiding()` (boats, minecarts, camels — "it can get out of boats"), `Brain.stopAll(level)` once (verify exact name at M0), and the villager goes silent (§5). No sound, no message. The player learns about it only through the cues.
- Stored per level dimension in a `PersistentState`-style saved data (`stalker_binds`): one binding per player; a villager can be bound to at most one player; a separate blacklist map `playerUUID|villagerUUID → expiryTick`.
- Unbind + 10-minute blacklist when: bribe accepted, teleport strike, player dies during CHASE, player escapes during STUN, villager dies or is removed (including zombie conversion and the lightning→witch transformation — hook entity removal).

### 2. Following (STALK)

A server tick handler drives every bound villager (all state lives in the manager, keyed by UUID, so nothing depends on the entity staying loaded):

- **Movement**: `villager.getNavigation().moveTo(x, y, z, speed)` re-issued every 10 ticks (or when the target moves > 2 blocks). Speed 1.0 while closing, 1.25 in COVER sprints. Villager brain behaviours occasionally issue their own `moveTo`; ours re-issues every half-second and wins, and `Brain.stopAll(level)` is called every 40 ticks while bound to reset wandering/work behaviour (verify at M0; fallback ladder in Risks).
- **Distance bands**: > 64 blocks — close directly, stealth optional (catch-up). 24–64 — advance only when the player is looking away (dot of player look × direction-to-villager ≤ 0.35) or line of sight is blocked; otherwise hold. 12–24 — seek cover and watch. < 12 — hold behind cover, or keep closing to strike range if unseen.
- **Stuck handling**: if horizontal position moved < 0.5 blocks over 60 ticks while it wants to move — `stopRiding()` again, `jumpFromGround()` if `horizontalCollision`, and after two stuck cycles allow a one-time catch-up teleport (same rules as relocation, §6).
- **Terrain**: normal villager pathfinding; it opens no doors (villagers *can* path through doors — allow it, it's creepy), falls back to waiting if the player is somewhere unreachable, and the relocation rule eventually pulls it level.

### 3. Senses and cover

Pure-geometry pieces are separate static functions so they can be unit-tested (§Testing).

- **Line of sight**: `level.clip(ClipContext)` from player eye to villager eye, `ClipContext.Block.VISUAL` — blocked means no LOS.
- **Spotted**: LOS clear **and** angle between the player's look vector and the direction to the villager < 25° **and** distance < 28 blocks → `spotted` for 100 ticks. Being spotted does not chase it off inside 4 blocks (too late — it lunges instead, §4).
- **On spotted**: sprint (1.25) to the best cover point, then hide.
- **Cover search**: 48 candidates — 16 compass directions × radii {4, 7, 10} blocks, kept on the villager's level (±3 blocks Y). A candidate is cover if the player's eye cannot see the candidate eye position (clip blocked) and the block below is solid. Score = distance from villager + 0.5 × distance from player; take the lowest. `navigation.moveTo` to it; if no path or no candidate: **freeze and act normal** — stand still, slow idle looks at random points, one 1-second stare straight at the player, resume STALK after 100 ticks. (The fake-normal freeze is the fallback when there is nothing to hide behind.)
- **Hiding (COVER)**: pose `Pose.CROUCHING` while hidden (cheap synced flag — villagers ignore it in vanilla, so no behaviour change; the client lean animation keys off it in M6). Re-enters STALK when unspotted for 100 ticks. If the player walks within 8 blocks of its cover, find new cover or freeze.
- **Peeking**: while hiding and the player is within 32 blocks and not looking at the cover point, every 140–260 ticks: peek for 30 ticks — sidestep ~0.75 blocks out the open side (a real positional lean-out the player can see), `lookControl.setLookAt(player)` for the peek, then back behind and pose again.

### 4. Strike, chase, stun

- **Trigger**: distance < 2.2 blocks **and** dot(player look, direction to villager) < 0.2 (roughly behind/side) **and** phase STALK or COVER. In COVER within 4 blocks it lunges regardless of facing.
- **The tell** (you must *know* it got you): the villager snaps to face you, a sting plays (`entity.enderman.scream` pitch 0.65 + `entity.warden.heartbeat`), and a custom S2C packet turns the player's camera toward the villager's head for ~12 ticks (eased lerp; client-side, only with the mod on the client — sound alone is the fallback).
- **Variant A — snatched (15 %, `teleportChance`)**: also `MobEffects.DARKNESS` for 60 ticks, `entity.enderman.teleport` at both ends, and the *player* is teleported 100 ± 16 blocks in a random horizontal direction to the top of the heightmap (`MOTION_BLOCKING`), landing 0–3 blocks above the ground. No fall damage worth mentioning (≤ 3 blocks). Unbind + blacklist: it got what it wanted.
- **Variant B — the chase (85 %)**: `MobEffects.BLINDNESS` 180 ticks (this *is* the 9-second timer), `MobEffects.POISON` amplifier 0 for 200 ticks. The villager closes to melee (range 1.6, speed 1.15, repath every 5 ticks, prefers the point behind the player: `player.pos − playerLook × 1.5`) and hits on a 22-tick cooldown while in range:
  - hit N: damage `2 + 2 × poisonAmplifier` (2/4/6 = 1/2/3 hearts), reapply POISON at amplifier `min(N−1, 2)` for 200 ticks. Worst case ~7 hits ≈ 32 damage + poison — lethal if you stand there, escapable if you run (villager 1.15 < sprint 1.3, and blindness fog does not slow you).
  - `entity.warden.heartbeat` from the villager every 30 ticks at low volume — the blind player can track it by ear. This is intentional.
- **Stun**: when the 180-tick chase ends with the player alive, the villager freezes 160 ticks — no navigation, `MobEffects.CONFUSION` for 100 ticks, head lolling (`lookControl` set to random slow targets), stun sound (`entity.villager.no`, pitch 1.5).
  - Player > 40 blocks away at any point during the stun → unbind + blacklist (aggro removed).
  - Stun times out with the player still near → back to STALK; it will close in again (5-second strike cooldown so the escape window is real).
- **Player death during CHASE**: unbind + blacklist, and the villager plays `entity.villager.yes` once.

### 5. Silence and sound cues

- **Silent while stalking**: mixin `Villager#getAmbientSound` → `null` while bound. The villager does not hum, and since bound villagers refuse interaction (§6) there are no trade sounds either. Hurt/death sounds stay.
- **Subtle cues while following** (all positional from the villager, all `world.playSound(null, x, y, z, …)` so they are spatial):

| Moment | Event | Pitch | Volume | Cadence |
| --- | --- | --- | --- | --- |
| Stalking mumble | `entity.villager.ambient` | 0.5–0.6 | 0.25 | every 600–1400 ticks, within 32 blocks |
| Stalking footsteps | `block.grass.step` | 1.0 | 0.6 | ≤ 1 per 100 ticks, only while moving, 8–20 blocks, LOS blocked |
| Spotted, dashing for cover | `block.grass.step` ×2 | 1.2 | 0.7 | once per dash |
| Strike tell | `entity.enderman.scream` + `entity.warden.heartbeat` | 0.65 / 1.0 | 1.0 | once |
| Snatch teleport | `entity.enderman.teleport` (both ends) | 1.0 / 0.8 | 1.0 / 0.8 | once |
| Chase heartbeat | `entity.warden.heartbeat` | 1.0 | 0.8 | every 30 ticks |
| Stun | `entity.villager.no` | 1.5 | 1.0 | once |
| Bribe accepted (real or pretend) | `entity.villager.trade` + `entity.villager.yes` | 1.0 | 1.0 | once |

  The pretend-accept uses the *identical* sound and timing as a real accept — that is the deception.

### 6. Bribes

- **When**: phases STALK and COVER only ("stalking but not in a close-in blindness sequence"). During CHASE and STUN, interaction is dead.
- **How**: server-side `UseEntityCallback` (verify at M0): right-click with an item, not sneaking. While the villager is bound to *you*, the callback consumes the interaction — the trade screen never opens (other players can still trade normally; it's a normal villager to them). Exactly one item is taken per click.
- **Decision**: item value ≥ `bribeThreshold` (8) for the villager's profession → **accept**: item taken, accept sounds, unbind + blacklist (it moves on — resumes vanilla life, walks away). Below threshold → **pretend-accept**: item taken, *identical* sounds, then 40–80 ticks later it is in CHASE (variant B forced, tell and all).
- **Emerald** = 10, above every threshold, every profession — except nitwits by default (`nitwitAcceptsEmerald = false`).
- **Value tables** (points; stored in code, inspectable via `/stalker bribe`, threshold per table):

| Profession | 1 pt | 2 pts | 3 pts | 8+ pts |
| --- | --- | --- | --- | --- |
| Farmer | wheat, carrot, potato, beetroot, seeds | hay bale, pumpkin, melon | bread, cake | golden carrot, emerald |
| Fisherman | cod, string | salmon | tropical fish | pufferfish, emerald |
| Shepherd | any wool, dye | | shears | emerald |
| Fletcher | flint, feather | stick | arrow | bow, emerald |
| Librarian | paper | book | ink sac | enchanted book, emerald |
| Cartographer | paper | glass pane | compass, map | emerald |
| Cleric | rotten flesh, bone | gold nugget | redstone, lapis | ender pearl, emerald |
| Armorer | coal, iron nugget | iron ingot | lava bucket | diamond, emerald |
| Weaponsmith | coal, iron nugget | iron ingot | flint-and-steel | diamond axe/sword, emerald |
| Toolsmith | coal, iron nugget | iron ingot | stone tools | diamond, emerald |
| Butcher | raw chicken, raw porkchop | raw beef, sweet berries | cooked meats | rabbit stew, emerald |
| Leatherworker | leather | rabbit hide | leather armor (any) | saddle, emerald |
| Mason | clay ball, brick | terracotta (any) | quartz block | emerald |
| Unemployed | any raw food | any cooked food | | emerald |
| Nitwit | — (nothing has value; every offer is pretend) | | | — (config: emerald) |

### 7. Persistence, relocation, multiplayer

- Bindings live as entity attachments (on both the villager and the player, so either side's data is self-describing) and are resolved each tick via `serverLevel.getEntity(villagerUUID)`. The villager-side attachment enables copy-on-death=false (a dead stalker unbinds); the player-side survives death outside the chase.
- **Relocation**: if the villager is unloaded, further than 128 blocks behind, or in a different dimension for > 600 ticks while its player is online — teleport it to a point 48–64 blocks from the player, LOS-blocked from the player's eyes if any such point exists (reuse cover search at that radius), in the player's dimension. Silent, never in view. Cross-dimension follows `crossDimensionFollow` (default true) via `changeDimension` (verify at M0).
- Player logout: binding persists, ticking pauses. Player death (outside CHASE): binding survives — it liked that one.
- One binding per player; one player per villager; `/stalker start` picks a different villager if the nearest is taken. Multiple players can each have their own stalker.
- Creative/spectator players are never bound and cause instant unbind on mode switch (`requireSurvivalMode`).

### 8. Commands and config

`/stalker` (permission level 2):

| Subcommand | Effect |
| --- | --- |
| `start [player]` | Force-bind the nearest eligible villager (the test command) |
| `stop [player]` | Clear the binding |
| `status [player]` | Phase, villager UUID/profession, distance, LOS, spotted, timers |
| `phase <stalk|cover|chase|stun> [player]` | Force a phase (testing) |
| `spot [player]` | Force the spotted condition for 100 ticks |
| `strike [player]` | Force variant B strike now |
| `bribe [player]` | That villager's profession table and threshold |
| `reload` | Reload the config file |
| `debug` | Toggle particles: cover candidates (green = chosen, red = rejected), LOS rays, strike range ring |

Config: `config/stalker.json`, codec-based JSON written-if-absent, in the style of earshot's `EarshotConfig` (record + codec + unit test). Every number in the Tuning table below is a key. `/stalker reload` re-reads it.

## Architecture

Flat single Gradle project at the repo root (no `mod/` nesting — no Rust, no natives). Package `io.github.cumsauce.stalker`, resource namespace `stalker`.

```
horror-mod/
  gradle.properties          # same versions as earshot, mod id/version block
  build.gradle               # earshot's minus natives/bench/xz; gametest source set on
  settings.gradle            # rootProject.name = 'stalker'
  src/main/java/io/github/cumsauce/stalker/
    Stalker.java             # ModInitializer: config, events, attachment-less state, commands
    StalkerClient.java       # ClientModInitializer: strike-tell packet receiver (camera snap)
    config/StalkerConfig.java
    command/StalkerCommands.java
    world/Attachments.java   # attachment types: binding (villager+player), phase, blacklist history
    world/StalkerManager.java# the state machine; END_WORLD_TICK per level
    world/Phases.java        # enum STALK/COVER/CHASE/STUN
    world/StalkerBrain.java  # custom Activity + memory-gated move/look tasks; initBrain injection
    world/Senses.java        # static geometry: canSee, inLookCone, isSpotted, behind (pure math + clip)
    world/CoverSearch.java   # candidates + scoring; returns best cover or empty
    bribe/BribeTables.java   # per-profession item→points; accept/pretend decision (pure)
    bribe/BribeInteract.java # UseEntityCallback wiring
    net/StrikeTellPayload.java, Net.java   # S2C record: villager position, duration
    mixin/VillagerSilenceMixin.java        # getAmbientSound → null while bound
    mixin/client/VillagerLeanMixin.java    # (M6) head lean while Pose.CROUCHING
  src/main/resources/
    fabric.mod.json          # environment "*", main + client entrypoints, 2 mixin configs
    stalker.mixins.json, stalker.client.mixins.json
    assets/stalker/lang/en_us.json, icon.png (M6)
  src/test/java/...          # JUnit 5: geometry, damage scaling, bribe tables, transitions
  src/gametest/java/...      # fabricApi.configureTests, server-side game tests (M7)
```

Mixins: two gameplay mixins (silence, client lean) plus the accessor/injection mixins `StalkerBrain` needs for `initBrain` (per the activities tutorial: one injection, accessors only where registration points are private — keep this set minimal and list each target at M0). Everything else is events (`ServerTickEvents.END_WORLD_TICK`, `CommandRegistrationCallback`, `UseEntityCallback`) and vanilla calls (`getNavigation()`, `getLookControl()`, `playSound`, `addEffect`, `teleportTo`).

Tick budget: N bound villagers × (1 LOS clip + occasional 48-clip cover search every 2 s + nav recompute every 10 ticks). Negligible next to vanilla villager pathing; assert in the M7 game test anyway.

## Technical decisions and risks

1. **Movement runs inside the villager's Brain as a custom Activity, not against it.** Empirically (see the check above) the supported layer for villager behaviour is `Activity` + memory-gated `Task`s injected at `initBrain` HEAD; an external manager issuing `moveTo` over a live brain is uncharted and fights schedules/work/panic. So: `StalkerManager` owns everything that is not movement (rolls, senses, cover choice, phases, timers, effects, sounds) and each tick writes the chosen move target into the villager's STALKING_TARGET memory; a single custom `Activity`, registered only when the memory is present, has high-priority `Task`s that `moveTo` that target at the manager-set speed and `lookAt` the player during peeks. Unbound villagers never enter the activity — their brain is untouched vanilla. **Verify at M0** (genSources + the tutorial's example repo): `MemoryModuleType` registration, `initBrain` injection point in 26.3, `Task` constructor shape, `setTaskList` priority pairing, and whether adult-only registration needs the `isBaby` guard like the tutorial's. Fallback ladder if the activity misbehaves: (a) manager re-issues the memory + `moveTo` every 10 ticks on top of the task (hybrid); (b) drop the activity, `Brain.stopAll(level)` every 40 ticks plus direct navigation (the original plan); (c) last resort, `setNoAi(true)` and drive `navigation`/`moveControl` manually from the manager (verify where `PathNavigation.tick()` is normally called).
2. **`Pose.CROUCHING` as the hide/peek carrier.** Pose is already synced entity data; villagers ignore it, the vanilla renderer ignores it, and non-player dimensions don't change for poses. Risk: some eye-height/hitbox side effect — verify in M3 (`getEyeHeight` with the pose set). Fallback: drop the pose, keep the positional peek (which is visible on its own) and key the client lean off the strike-tell packet channel instead (a tiny `peek` S2C flag).
3. **Camera-snap tell needs the client mod.** On a vanilla client the tell degrades to sound-only. Acceptable: the mod is meant for modded clients and single-player; the packet is fire-and-forget.
4. **No custom sounds.** Vanilla events, pitched down. Custom stingers are an optional M6 stretch (three short `.ogg`s under `assets/stalker/sounds/` + `sounds.json`); skip unless assets appear.
5. **Villager remains vanilla.** No `ZombieVillager` special-casing beyond "entity removed → unbind". Cured-then-restalked villagers just get bound again by the random roll like anyone else.
6. **Everything tunable.** If playtests say 9 seconds of blindness is unfun, it's one config key, not a code change.

## Milestones

Each milestone: implement, `./gradlew build` (compiles + unit tests) green, run the manual checklist in a dev client (`./gradlew runClient`, use `-Pstalker.runDir=<local disk>` like earshot's VM trick if the shared folder complains), then `git commit`. Do not start the next milestone with a red build.

### M0 — Skeleton and toolchain parity

- Copy earshot's wrapper (Gradle 9.7.1), `settings.gradle` (rename), `gradle.properties` (identical version block; `version=0.1.0`; drop xz/lwjgl/modmenu), `build.gradle` (loom 1.18.2, Java 25 toolchain, JUnit 5 BOM, `processResources` version expansion, `-Pstalker.runDir` override, `fabricApi.configureTests` with `enableGameTests = true` and mod id `stalker-gametest`), `.gitignore` (gradle/build/run/idea), `git init`.
- `fabric.mod.json`: environment `"*"`, entrypoints `main: Stalker`, `client: StalkerClient`, both mixin configs, depends as earshot's (`minecraft ~26.3`, `java >=25`, `fabric-api *`). Empty mixin config arrays are fine for now. `Stalker` logs its version on init.
- **Verify names at M0, before writing AI code**: `./gradlew genSources`, then confirm in the decompiled sources the exact 26.3 signatures of: `Villager` brain construction (`initBrain`/equivalent) and `Brain#setTaskList`, `MemoryModuleType` + `Activity` registration, `Level#clip`, `AttachmentRegistry`/`AttachmentType` (Fabric) incl. copy-on-death flags, `ServerLevel#getEntity(UUID)`, `UseEntityCallback` (Fabric), `Pose`, `MobEffects` field names, Fabric payload registration (`PayloadTypeRegistry.playS2C()`). Record the findings in this file, as earshot's M0 section does.
- **Done when**: build green; dev client reaches the title screen; the init log line appears; the API-name list above is filled in.

### M0 results (2026-10-14, verified in the 26.3 decompiled sources — `./gradlew genSources`)

- **Mappings renames vs the plan's 1.21-era names:** `Villager` is `net.minecraft.world.entity.npc.villager.Villager` (with `AbstractVillager` beside it); `Activity` is **`net.minecraft.world.entity.schedule.Activity`** (registry object, constructor access-widened public — a custom `Activity` can be made with `new Activity("stalking")` + `Registry.register(BuiltInRegistries.ACTIVITY, ...)`); `CONFUSION` is now **`MobEffects.NAUSEA`** (`BLINDNESS`, `POISON`, `DARKNESS` unchanged, all `Holder<MobEffect>`).
- **Villager brain construction has no per-instance `initBrain` injection point.** Villagers build their brain from a static `BRAIN_PROVIDER` whose activity lists come from `VillagerGoalPackages`; the only per-instance hook is `private void registerBrainGoals(Brain<Villager>)` (also called by `public void refreshBrain(ServerLevel)` — which is what baby→adult and profession changes go through, so our activity must be added there too, or it survives refresh via the mixin on `registerBrainGoals` since `refreshBrain` calls it). **Mixin plan: `@Inject(at = HEAD)` into `Villager#registerBrainGoals`, call `brain.addActivity(STALKING_ACTIVITY, priorities, requirements, memoriesToErase)`** (idempotent per fresh brain). No accessors needed — everything else is public.
- `Brain#addActivity(Activity, ImmutableList<Pair<Integer, ? extends BehaviorControl<? super E>>>, Set<Pair<MemoryModuleType<?>, MemoryStatus>> conditions, Set<MemoryModuleType<?>> memoriesToEraseWhenStopped)` replaces `setTaskList`; priorities are a flat map of priority → per-activity behavior set. `Brain#stopAll(ServerLevel, E)` takes the entity. `setMemory`/`getMemory`/`checkMemory`/`setActiveActivityIfPossible`/`isActive` all public.
- Custom behavior: subclass `net.minecraft.world.entity.ai.behavior.Behavior<E>` (`BehaviorControl` impl; constructors `(Map<MemoryModuleType<?>, MemoryStatus>)` and `(..., minDuration, maxDuration)`); `Behavior#getRequiredMemories` is honored by `addActivity`.
- `MemoryModuleType` registration: `Registry.register(BuiltInRegistries.MEMORY_MODULE_TYPE, id, new MemoryModuleType<>(Optional.of(codec)))` — the private `register(String)` helpers mirror this shape.
- `Level#clip` lives on `BlockGetter#clip(ClipContext)` (Level inherits it). `Level#getEntity(UUID)` public (line ~857); `ServerLevel` adds `getEntityInAnyDimension(UUID)`.
- `MobEffects`: `BLINDNESS`, `POISON`, `DARKNESS`, `NAUSEA` (not CONFUSION) — `Holder<MobEffect>`.
- `Pose.CROUCHING` exists (`net.minecraft.world.entity.Pose`).
- Fabric: `net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry`/`AttachmentType` present (fabric-data-attachment-api-v1 2.2.31); `net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry` present (fabric-networking-api-v1 6.3.9); **`UseEntityCallback` is `net.fabricmc.fabric.api.event.player.UseEntityCallback`** in fabric-events-interaction-v0 (not entity-events).
- Silence mixin target: `protected @Nullable SoundEvent Mob#getAmbientSound()` (Villager inherits; ambient loop in `Mob#baseTick` calls it) — inject into `Villager`'s override or `Mob` with an instanceof guard; prefer targeting `Villager#getAmbientSound` directly.
- Toolchain note: this Mac has no system JVM — run Gradle with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (JDK 25.0.4.1).

### M1 — Bindings and persistence

- `StalkerConfig` skeleton (5 keys: chances, threshold, durations — enough to load/save), `Attachments` (binding + blacklist as entity attachments), `Phases`, `/stalker start|stop|status` with permission 2.
- Bind eligibility (§1), persistence-required, stop-riding, per-pair blacklist map.
- **Done when**: `/stalker start` binds the nearest adult villager (status shows UUID + profession); `/stalker start` on a bound player errors; a second player's `start` refuses a taken villager; full quit-and-reload keeps the binding in `/stalker status`; babies are refused; killing the bound villager (or the player in creative) leaves no orphaned attachment data.

### M2 — The follow

- `StalkerManager` tick loop: distance bands, unseen-approach gating (look dot), nav re-issue, stuck handling (stop-riding, jump, catch-up teleport), `StalkerBrain` activity driving movement from the manager's memory target, `Senses.canSee`.
- Silence mixin (`getAmbientSound` → null while bound).
- **Done when**: a bound villager follows you 100+ blocks across chunk borders; exits a boat you put it in; stays ~12–24 blocks off, closing mainly while you look away; never hums; `/stalker stop` returns it to vanilla behaviour (it hums again and wanders off); unbound villagers are untouched.

### M3 — Stealth: senses, cover, peek

- Spotted detection (cone + LOS + range), `CoverSearch` (48 candidates, scoring, path), COVER sprint, fake-normal freeze fallback, hide pose, peek cycle (sidestep + look), `/stalker spot`, `/stalker debug` particles.
- Unit tests: cone/behind math, cover scoring order on synthetic candidates.
- **Done when**: looking straight at it within 28 blocks makes it dash behind the nearest tree/wall (debug shows green candidate); with no cover around it freezes and stares; hidden, it leans out and looks at you every ~10 s; it closes to strike range only while you look away; pose change doesn't alter its hitbox or eye height (check F3 + arrows to the body).

### M4 — Strike, chase, stun

- Strike trigger + tell (`StrikeTellPayload`, client camera lerp, sting sounds), variant A (snatch: player teleport with heightmap landing), variant B (blindness/poison chase, melee scaling, heartbeat), stun (freeze, confusion, escape window), death/escape unbind rules, `/stalker phase`, `/stalker strike`.
- Unit tests: damage formula `2 + 2×min(N−1, 2)`, chase/stun timing transitions, teleport landing pick given a synthetic heightmap column.
- **Done when**: approached from behind → camera snaps to it + sting; 15 %-config forces the snatch variant and lands you ~100 blocks away on solid ground (set `teleportChance: 1.0` to test, then `0.0`); surviving 9 s of blindness leaves it dazed; walking 40+ blocks during the daze clears `/stalker status`; dying to it clears the binding and plays the satisfied `yes`; it never strikes from the front.

### M5 — Bribery

- `BribeTables` (all 15 professions + emerald + nitwit rule), `BribeInteract` (interaction consumed while bound to you, one item per click), accept vs pretend paths, pretend's delayed guaranteed chase, `/stalker bribe`.
- Unit tests: every `VillagerProfession` has a table; emerald beats every threshold; nitwit refuses by default; threshold boundary accepts at 8, pretends at 7.
- **Done when**: a farmer offered a golden carrot takes it, sounds pleased, and `/stalker status` shows unbound + blacklist; offered wheat it takes it with the *same* sound, then ~2 s later the tell fires and you're in the chase; the trade screen never opens while bound; a nitwit pretends at an emerald; another player can trade with an unbound stalker normally.

### M6 — Ambience, config, polish

- Cue scheduler (mumble, footsteps) with rate limits and the LOS-gated conditions; full config surface wired + `/stalker reload`; `en_us.json` for all command feedback; icon; README; client lean mixin (`Pose.CROUCHING` → subtle head tilt; purely visual).
- **Done when**: cues are audible and positional but never rhythm-regular (randomized cadence); every tuning key from the file round-trips through edit + reload; optional: custom `.ogg` stingers if assets are provided.

### M7 — Hardening and game tests

- Server game tests (structure `stalker:cover_plain`: a tree, a wall, open ground): cover search picks the wall behind the tree from synthetic eye positions; spotted math; manager phase transitions driven by synthetic positions; performance guard (manager tick under 1 ms averaged over 1000 ticks).
- Edge cases: dimension change → relocation into the player's dimension; player logout/login; zombie conversion unbinds; villager death unbinds; two players stalked simultaneously by two villagers; creative mode never binds.
- Final tuning pass against the table below; update this file's milestone sections with results, earshot-style.

## Tuning table (single source of truth — every value a `StalkerConfig` key)

| Key | Default | Meaning |
| --- | --- | --- |
| `bindChancePerMinute` | 0.004 | Random bind roll per eligible player per minute |
| `bindMaxDistance` | 48 | Villager search radius for binding |
| `requireNight` / `requireAlone` | false | Extra bind conditions (alone = no other player within 64) |
| `orbitInner` / `orbitOuter` | 12 / 24 | Hold band in STALK |
| `spottedRange` / `spottedAngleDeg` | 28 / 25 | Spotted cone |
| `strikeRange` | 2.2 | Strike trigger (2026-10-16: superseded §4's facing rule — it now strikes only when out of LOS, not when behind) |
| `teleportChance` | 0.15 | Variant A share of strikes |
| `teleportDistance` / `teleportMaxLift` | 100 (±16) / 3 | Snatch landing |
| `chaseDurationTicks` | 180 | Blindness = the chase timer |
| `chaseSpeed` / `meleeRange` / `hitCooldownTicks` | 1.15 / 1.6 / 22 | Chase combat |
| `hitDamage` / `poisonAmplifierCap` / `poisonDurationTicks` | 2 + 2×amp / 2 / 200 | Scales with the poison |
| `stunDurationTicks` / `stunEscapeDistance` | 160 / 40 | The escape window |
| `bribeThreshold` / `emeraldValue` / `nitwitAcceptsEmerald` | 8 / 10 / false | Bribes |
| `blacklistTicks` | 12000 | Satisfied/escaped pair immunity (10 min) |
| `relocateBehindTicks` / `relocateRingMin` / `relocateRingMax` | 600 / 48 / 64 | Relocation |
| `crossDimensionFollow` | true | Portal-hopping stalker |
| `brainResetTicks` | 40 | Brain-reset cadence while bound (fallback ladder (b) only) |
| cue cadences/volumes | see §5 | Mumble, footsteps, heartbeat |

## Testing strategy

- **Unit (JUnit 5, per milestone)**: everything pure — cone/behind/spot math, damage and poison scaling, bribe tables and thresholds, phase transition table, teleport landing choice over a synthetic heightmap, config codec round-trip. Style follows earshot's tests (no Minecraft bootstrap needed — keep logic pure so this is possible).
- **Manual (per milestone)**: the checklists above, in a dev client, using the `/stalker` commands and `debug` particles — the particles are the stalker equivalent of earshot's `rays` command.
- **Game tests (M7)**: server-side Fabric game tests for cover search and manager transitions on structure templates.

## Notes for the implementer (GLM Flash)

- Mirror earshot's conventions: tabs, Javadoc on public members, Mojang mappings, records + codecs for data, commands under `command/`, config under `config/`, one class per concern.
- **Never guess a mapping.** Anything not verified at M0 gets checked in the genSources decompile before use — an invented method name is a build failure anyway, but a *plausible* one is worse: verify, then write.
- No magic numbers in logic; every tunable goes through `StalkerConfig` (the table above is exhaustive).
- The villager must stay a vanilla `Villager`. No custom entity, no spawner, no texture change, no nametag. Its power is being ordinary.
- When behavior fights the villager brain, prefer *more re-issuing* over *more suppression* (see Risks #1 ladder) — suppression is where the weird bugs live.
- Commit per milestone. If a milestone's checklist partially fails, record exactly which lines fail in the commit message rather than declaring it done.

## Requirement traceability

| Requirement | Where |
| --- | --- |
| Random chance for a villager to stalk a player | §1, M1 |
| Tracks by UUID, keeps following | §1, §7, M1/M2 |
| Watches from behind cover, peeking animations | §3, M3 (+lean M6) |
| Stays out of sight; darts behind things when spotted | §3, M3 |
| Silent voice while stalking | §5, M2 |
| Striking-distance "you know it got you" tell (face them + sound) | §4, M4 |
| Small chance: teleport ~100 blocks, up to 3 in the air | §4 variant A, M4 |
| Larger chance: sting + blindness + poison | §4 variant B, M4 |
| Chase: out of sight, closes in, keeps hitting, scales with poison | §4, M4 |
| Outlast 9 s → stunned, escape removes aggro | §4 stun, M4 |
| Bribes while stalking (not during blindness), profession-dependent | §6, M5 |
| Below threshold → pretend-accept, then kill | §6, M5 |
| Gets out of boats etc. | §1, §2, M2 |
| Subtle sound cues while following | §5, M6 |
| Test command to force stalking | §8 `/stalker start`, M1 |
| Same Fabric as earshot | Goal, M0 |
