# Stalker

A Minecraft villager fixates on a player, follows them, watches them from behind trees, and — if it gets close enough unobserved — "gets" them. It stays a completely vanilla `Villager`: no custom entity, no custom texture, no new sounds. Everyone else on the server can still trade with it. It is a server-side Fabric mod for Minecraft 26.3.

Status: **alpha (0.3.0)**, on the [releases page](https://github.com/cumsauce/stalker/releases). The full loop works in game, and the core behaviours are covered by unit and server game tests (see [Testing](#testing)).

## The idea

Every so often, a nearby adult villager quietly picks you and starts following. It keeps its distance, hides behind whatever is handy, and peeks out to watch. It is silent while it stalks — no humming, no trading. If you spot it in your field of view it breaks for cover; if you corner it with nothing to hide behind, it stands very still and pretends to be an ordinary villager.

If it reaches you unobserved, you will know. It plays a sting, turns your camera toward it, and then either snatches you (a rare 15%: you are thrown a hundred blocks away) or blinds you and chases. Blind, poisoned and beaten, you have nine seconds to run before it collapses, dazed, and you can walk away for good.

The strike itself has two triggers. It always attacks when you cannot see it and it is close. But it also loses patience: if it is close, no other villager has it in sight, no other player is near, and you have stood still for a few seconds, it strikes even while you are looking straight at it. It only has to hide from witnesses, not from its victim.

While it is only stalking — not chasing — you can buy it off. What counts as payment depends on its profession: a farmer wants golden carrots, a cleric wants an ender pearl, a nitwit wants nothing. Offer something it values enough and it takes it and moves on. Offer too little and it takes it anyway, says thank you, and then kills you.

## How it works

All the logic is server-side, driven from a per-level tick handler that keys everything by UUID, so nothing depends on the villager staying loaded.

- **Bindings** use Fabric's data attachment API on both entities: the villager remembers which player it stalks, the player remembers which villager stalks them. They survive restarts, chunk unloads and dimension changes. If the villager's chunk unloads or it ends up in another dimension — cases where it is not rendered at all — it is brought back out of sight; it never visibly teleports next to you.
- **Movement** runs inside the villager's own brain. A custom `Activity` (gated on a memory the manager writes each tick) drives navigation through the vanilla pathfinder, so an unbound villager's behavior is untouched and a bound one still flees zombies and raids. It walks slowly while you might be watching and only picks up the pace on a committed advance you cannot see, so it can keep up over long distances without giving itself away.
- **Senses** are plain geometry: a `Level#clip` line-of-sight test, a look-cone dot product, and a 48-candidate cover search (16 directions × 3 radii) that only counts spots the player cannot see, the villager can actually stand in, and that are dry. Candidate legs are charged for slow ground — water, cobweb, soul sand — so it detours over dry land rather than wading.
- **The tell** is a custom S2C packet that turns your camera toward the villager for a moment. On a vanilla client, or with the mod only on the server, it degrades to the sound cue alone.
- **Sounds** are vanilla events, pitched and positioned: a low mumble when the binding is made, footsteps and a mumble as it stalks, a soft noise when it peeks, a heartbeat once it is close and hidden, and a slow stare sting when it decides to stop waiting. There are no custom audio assets.

## Install

Needs [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5+ and [Fabric API](https://modrinth.com/mod/fabric-api). Drop `stalker-<version>.jar` into `mods/`. Minecraft 26.3, Java 25.

It goes on the **server** (or in the `mods/` folder of a single-player world). You do not need it on the client — everything works without it — but the camera-snap on the strike needs it on both sides.

## Commands

`/stalker` (permission level 2):

| Command | Effect |
| --- | --- |
| `start [player]` | Force the nearest eligible villager to bind (the test command) |
| `stop [player]` | Clear the binding |
| `status [player]` | Phase, villager UUID and profession, distance, line of sight, timers |
| `phase <stalk\|cover\|chase\|stun> [player]` | Force a phase |
| `spot [player]` | Force the "spotted" condition |
| `strike [player]` | Force a chase strike now |
| `bribe [player]` | Show that villager's profession table and threshold |
| `reload` | Re-read `config/stalker.json` |
| `debug` | Toggle the particle overlay (cover candidates, line-of-sight rays, strike range) |
| `spawn [count]` | One-command test setup: places a real vanilla `village_plains` at your position, scatters `count` (default 12) extra employed villagers, and binds a stalker immediately |
| `village [huts]` | Build a small hamlet around you: a bell, `huts` (default 4, max 8) simple huts with a door, bed and torch, and one employed villager per hut. Block-level: use on empty-ish ground. (For a real generated village, vanilla's `/place structure minecraft:village_plains` also works.) |

## Config

`config/stalker.json`, written with all defaults on first run. Every number in the mod is a key, so nothing needs a rebuild to tune. `/stalker reload` re-reads it.

| Key | Default | Meaning |
| --- | --- | --- |
| `bindChancePerMinute` | 0.004 | Per-player, per-minute chance to be picked (~once per 4 hours) |
| `bindMaxDistance` | 48 | How close an eligible villager must be |
| `requireNight`, `requireAlone` | false | Restrict binds to night / to being alone |
| `orbitInner`, `orbitOuter` | 12, 24 | The band it stalks you from |
| `spottedRange`, `spottedAngleDeg` | 28, 25 | What counts as you seeing it |
| `strikeRange` | 2.2 | How close it must be to strike while out of your line of sight |
| `teleportChance` | 0.15 | Chance the strike is the snatch (it throws *you*) instead of the chase |
| `teleportDistance`, `teleportMaxLift` | 100, 3 | How far the snatch throws you, and how high you land |
| `chaseDurationTicks` | 180 | The 9-second blindness chase |
| `chaseSpeed`, `meleeRange`, `hitCooldownTicks` | 1.15, 1.6, 22 | Chase movement and hitting |
| `hitDamage`, `poisonAmplifierCap`, `poisonDurationTicks` | 2, 2, 200 | Damage and the poison that scales with each hit |
| `stunDurationTicks`, `stunEscapeDistance` | 160, 40 | The dazed window, and how far you must get to escape |
| `bribeThreshold`, `emeraldValue` | 8, 10 | What it takes to buy it off |
| `nitwitAcceptsEmerald` | false | Whether a nitwit values anything at all |
| `blacklistTicks` | 12000 | Immunity after it is satisfied (10 minutes) |
| `relocateBehindTicks`, `relocateRingMin`, `relocateRingMax`, `crossDimensionFollow` | 600, 48, 64, true | Bringing it back when its chunk is unloaded or it is in another dimension |
| `mumble*`, `footstep*`, `chaseHeartbeat*` | | Cue cadences and volumes (the bind, peek and ambush cues are fixed vanilla pitches) |

The patience strike's range and hold are constants, not config keys: within `strikeRange × 2`, with no villager witness in line of sight and no other player within 32 blocks, it strikes after 60 ticks (3 s) of you standing still.

## Development

You need JDK 25.

```sh
./gradlew build                # compile + unit tests
./gradlew runClient            # dev client
./gradlew runServer            # dev server
./gradlew runGameTest -Pstalker.gameTests   # server game tests
```

Building inside a VM from a folder shared with the host over VirtioFS needs `-Pstalker.runDir=<dir on a local disk>` for the game runs, because Minecraft cannot lock its saves there. On a machine with no system JVM, set `JAVA_HOME` to a JDK 25 (e.g. `/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`).

`docs/PLAN.md` has the design, the mechanics in detail, the milestones, and the notes from verifying the 26.3 API against the decompiled sources.

## Testing

- **Unit tests** (`src/test`, run by `./gradlew build`): the per-profession bribe tables, the config load/save round-trip, the bind-eligibility rules, the brain memory-declaration invariant, the approach planner, and the patience-strike trigger.
- **Game tests** (`src/gametest`, `./gradlew runGameTest -Pstalker.gameTests`): a bound villager's brain ticks without error (the regression for the "Unregistered memory fetched" crash), the hamlet builder places a bell and villagers, the spawn ring produces employed adults, and a villager on a path opens a closed door. These run on a real server and are what CI runs on every push.

## Releasing

Pushing a `v<version>` tag that matches `version` in `gradle.properties` makes CI build the jar and publish a GitHub release:

```sh
# bump version= in gradle.properties, commit, then:
git tag v0.1.3 && git push origin main v0.1.3
```

## Known gaps

- **Climbing ladders** is not implemented: the vanilla ground pathfinder will not route a villager up a ladder, so the stalker cannot follow you up one. It will keep tracking you at ground level (and reappear out of sight when you come back down), but a custom `NodeEvaluator` would be needed for real climbing.
- `brainResetTicks` is a declared config key with no reader — the fallback approach it was for was not needed.
- `StalkerManager.onVillagerRemoved` is exposed but not hooked to an entity-removal event; a dead or converted villager is caught by a per-tick liveness check and a 60-second orphan fallback instead.
- No custom audio: the cues are pitched vanilla sounds by design. Custom `.ogg` assets are optional polish.

## License

MIT OR Apache-2.0, at your option, as declared in `fabric.mod.json`.

Prior art: [From The Fog](https://www.curseforge.com/minecraft/mc-mods/from-the-fog) is the closest thing to this idea, but it is a datapack under CC BY-NC-SA with a no-AI-generated-forks clause. No code or assets were taken from it.
