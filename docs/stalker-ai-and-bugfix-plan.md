# Stalker — AI improvements and bug-fix implementation plan

> **For agentic workers:** Execute the steps in order. Each step is one edit or one command with a
> checkable result. Steps use checkbox (`- [ ]`) syntax. Do not skip, reorder, merge or "improve"
> steps. Where a step says JUDGEMENT it has already been decided — apply the decision as written.

**Goal:** Fix the five confirmed bugs in the stalker phase machine / binding lifecycle and apply
three small, PLAN.md-backed AI changes to `tickStalk`, leaving the repo compiling and all JUnit
tests green after every group.

**Architecture:** All changes are inside `world/StalkerManager.java`, `world/BindingRules.java`,
`strike/Strikes.java` and one new test method file. No new classes. No new memory module reads
(the MemoryAccesses invariant is untouched). No change to `BribeTables`, `Cues` or client code.

**Tech stack:** Minecraft 26.3, Fabric Loom 1.18.2 (unobfuscated, official Mojang names, no
remapping), Java 25, JUnit 5 (`useJUnitPlatform`), Fabric API 0.162.0+26.3.

**Spec:** `PLAN.md` in the repo root (sections §2, §3, §4, §6, §7 are cited below).

**Reviewed revision:** `main` at `fc8924b` (v0.7.0), working tree clean.

---

## 0. Global constraints (apply to every step)

- Work in `/Volumes/My Shared Files/mc/horror-mod` only. Ignore the `fart-sound` worktree.
- **Do not commit.** Leave all edits unstaged in the working tree.
- Build command (used for every "Check" that says `BUILD`):
  ```
  cd "/Volumes/My Shared Files/mc/horror-mod"; JAVA_HOME=/opt/homebrew/opt/openjdk@25 PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH ./gradlew build --offline
  ```
  Timeout 900000 ms. Expected last lines: `BUILD SUCCESSFUL`.
- Test command (used for every "Check" that says `TEST`):
  ```
  cd "/Volumes/My Shared Files/mc/horror-mod"; JAVA_HOME=/opt/homebrew/opt/openjdk@25 PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH ./gradlew test --offline
  ```
  Timeout 900000 ms. Expected: `BUILD SUCCESSFUL`. Baseline is 68 tests in 9 classes; after this
  plan it is **74 tests** (6 new in `BindingRulesTest`).
- `runGameTest` does NOT run here. Every behaviour change in `StalkerManager`/`Strikes` is marked
  **MANUAL IN-GAME VERIFICATION** with exact human steps in §9. The machine check for those steps
  is "compiles + existing tests green".
- Invariants that must survive (do not touch):
  1. Memory-access invariant: `MoveTargetTask` reads only `STALKING_TARGET` and `STALKING_SPEED`,
     both in its constructor map (`StalkerBrain.java:241-243`). This plan adds no memory reads.
     `MemoryAccessesTest` must stay green.
  2. `BribeTables.value(String,String,Set,int)` and the 5-arg nitwit overload: not touched.
  3. Cues lie-layer contract (`sound/Cues.java:22-31`): ambient cues from a stale origin; event
     tells (`strikeTell`, `ambushWindup`, `dashForCover`, `peek`, `bindStart`, `stun`,
     `bribeAccepted`, `playerDied`, `snatchTeleport`) stay true-position. This plan calls no new
     cue and moves no existing cue call.
- Tabs for indentation (the source uses tabs). Copy the "After" blocks exactly.
- Line numbers below are from `fc8924b`. **Always locate by the quoted anchor text, not the line
  number** — earlier steps shift later lines. Within one file, apply steps in the order given.

---

## 1. Findings summary (what the plan fixes, by severity)

Legend: **CONFIRMED** = read in source and (where an external API is involved) checked against the
compiled jars in the Loom/Gradle cache. **SPECULATIVE** = reasoned, not executed in a live game.

| ID | Severity | Where | Trigger | Symptom | Status |
| -- | -------- | ----- | ------- | ------- | ------ |
| BUG-1 | HIGH | `StalkerManager.java:216-220` (`ENTITY_UNLOAD` handler) → `onVillagerRemoved` 1313-1333 | Bound villager's chunk unloads (player outruns view distance, enters a portal) or villager changes dimension (incl. the mod's own cross-dim `relocate`, 940-945) | Binding dropped **and 10-minute blacklist applied**, contradicting PLAN §7 "Bindings survive restarts, chunk unloads and dimension changes" | CONFIRMED. Fabric `ServerLevelEntityCallbacksMixin` injects `ENTITY_UNLOAD` at `ServerLevel$EntityCallbacks.onTrackingEnd`, which vanilla calls for every `RemovalReason` (`KILLED, DISCARDED, UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER, CHANGED_DIMENSION` — enum verified with `javap` on `minecraft-merged.jar`). `Entity.getRemovalReason()` and `RemovalReason.shouldDestroy()` exist. |
| BUG-2 | HIGH | `StalkerManager.java:1243-1257` `forceStrike`; caller `BribeInteract.java:107` | Player bribes below threshold → pretend-accept → 40–80 ticks later `forceStrike` | `forceStrike` calls `Strikes.strike` (1255) which rolls `teleportChance` (`Strikes.java:102`) → 15 % of betrayals **snatch-teleport** instead of chasing. PLAN §6 says "CHASE (variant B forced, tell and all)". The javadoc at 1243 even says "Forces a variant B strike". `/stalker strike` has the same roll. | CONFIRMED |
| BUG-3 | MED | `StalkerManager.java:651-676` (hidden-at-cover block) | Any peek while in COVER | (a) line 651 erases the move memory and calls `navigation.stop()` **every tick**, then 663 re-sets it → `MoveTargetTask.stop()`/`start()` each tick and an A* re-path every tick for 30 ticks (`StalkerBrain.java:253-278`); the sidestep is a 1-tick stutter. (b) If the player looks at the cover mid-peek the whole `if` at 655 is skipped, so `peekTicks` freezes > 0 and the villager stays leaned out, exposed. (c) The "return to centre" at 664-665 is filtered out by `issueMovement`'s stale test (0.75² < 4, `ticksSinceIssue` < 10) and would be erased next tick anyway. | CONFIRMED (a,b,c from code); the visible magnitude of the stutter is SPECULATIVE |
| BUG-4 | MED | `StalkerManager.java:769-775` fallback + `638-644` dash timeout | Best-ranked cover is unpathable (fenced garden, cliff) and all 3 probes fail | `findCoverNow` still picks `candidates.get(0)`; dash times out after 80 ticks → `coverPos=null` → next tick re-search (cooldown long expired) → same candidate → loop. Villager sprints at a wall in 80-tick bursts for the whole COVER phase. | CONFIRMED |
| BUG-5a | MED | `StalkerBrain.java:61,64` codec-backed memories + `StalkerManager.java:1049-1051` | Bound villager unloads with `STALKING_TARGET` set, reloads later | Brain memories persist with the entity (both registered with codecs). On reload `AssertStalkingTask` latches STALKING and `MoveTargetTask` walks to a stale point. The only healer, `isEligible` 1049-1051, unbinds the attachment but never calls `StalkerBrain.release`. | CONFIRMED (persistence) / SPECULATIVE (how far it walks) |
| BUG-5b | MED | `StalkerManager.java:964-979` `resolveVillager` | Villager unloaded for > 1200 ticks (becomes reachable only after BUG-1 fix, which stops the instant drop) | `dropPlayerSide` — unbind with NO blacklist, villager side left bound (one-sided binding, the exact case `BindingRules` javadoc warns about). PLAN §7 wants relocation ("villager unloaded … → teleport to ring 48–64"). `getEntityInAnyDimension` cannot see an unloaded entity, so relocation never happens. | CONFIRMED (cause); fix uses `ServerLevel.setChunkForced(int,int,boolean)` (exists, verified) — **MEDIUM confidence** that entities attach quickly enough; see Group 7 |

AI changes (all inside `tickStalk`, `StalkerManager.java:347-420`), each tied to PLAN.md:

| ID | What | Why (PLAN evidence) |
| -- | ---- | ------------------- |
| AI-1 | When no cover hop fits, walk to the **plan target** (`ApproachPlanner.planTarget`: intercept point or last-seen position) instead of `player.position()` (`StalkerManager.java:392-394`) | `ApproachPlanner.java:118-124` was written to give a last-seen fallback and lead the target; the fallback at 392-394 throws it away and homes on the live position even when LOS is blocked — which is also a small "lie-layer" leak in movement. |
| AI-2 | Seek/hold cover whenever **outside ambush range** (`distance > strikeRange*2 = 4.4`), not only `distance >= orbitInner (12)` (`StalkerManager.java:395`) | PLAN §2 "<12: hold behind cover or keep closing to strike range if unseen". Today an unspotted villager 4.4–12 blocks away with the player half-facing it (look dot > 0.35, so no approach) gets `target = null` and **freezes in the open**. |
| AI-4 | When no target and not at cover and outside ambush range → `actNormal` (`StalkerManager.java:408` only does this when spotted) | PLAN §3 "no cover → act normal". Today an unspotted villager with no cover candidates stands dead still. Ambush stillness (≤ 4.4) is preserved. |

Considered and **rejected** (do not implement):
- AI-3 "stand off when advancing within 4.4 and visible": changes strike geometry; low confidence it reads better than the current close-in. Not in PLAN.md.
- AI-5 add radius 2 to `CoverSearch.RADII`: PLAN §3 fixes radii at {4,7,10}.
- `coneExposure` re-sort (`CoverSearch.java:78-83`): stable, correct.
- `Strikes.tickStun` duplicating the manager's stun escape/timeout: idempotent, same tick, harmless.
- `orbitOuter` config field is unused anywhere outside `config/` (grep confirmed). Leave it; `StalkerConfigTest` round-trips it.

---

## 2. File map

| File | Change |
| ---- | ------ |
| `src/main/java/io/github/cumsauce/stalker/world/BindingRules.java` | + `removalEndsBinding(Entity.RemovalReason)` |
| `src/test/java/io/github/cumsauce/stalker/world/BindingRulesTest.java` | + 6 tests |
| `src/main/java/io/github/cumsauce/stalker/world/StalkerManager.java` | ENTITY_UNLOAD filter, ENTITY_LOAD hook, `onVillagerLoaded`, `forceStrike`, `tickCover` peek block, `findCoverNow`/dash-timeout failed-cover set, `tickStalk` target logic, `nextPlanHop` fallback, `resolveVillager` force-load, `State` fields |
| `src/main/java/io/github/cumsauce/stalker/strike/Strikes.java` | + `strikeChase`, extract `tell` |

Dependencies: Group 1 → Group 7 (7 only matters after 1). Group 5 → none. Groups 2, 3, 4, 6 are
independent of each other but **edit the same file, so keep the given order**.

---

## 3. Group 0 — baseline

- [ ] **Step 0.1** Run `TEST`. Expected: `BUILD SUCCESSFUL`, 68 tests, 0 failures. If this fails,
  stop: the baseline is broken and nothing below applies.

---

## 4. Group 1 — BUG-1: only destroying removals end a binding

**Why:** chunk unload / dimension change must keep the binding (PLAN §7); only `KILLED`/`DISCARDED`
(lightning conversion, `/kill`, despawn) should unbind + blacklist.

- [ ] **Step 1.1** `src/test/java/io/github/cumsauce/stalker/world/BindingRulesTest.java` — add the
  import and six tests. Add after the existing `import` lines:
  ```java
  import net.minecraft.world.entity.Entity;
  ```
  Add inside the class, after the last existing `@Test` method:
  ```java
  	@Test
  	void killedEndsBinding() {
  		assertTrue(BindingRules.removalEndsBinding(Entity.RemovalReason.KILLED));
  	}

  	@Test
  	void discardedEndsBinding() {
  		assertTrue(BindingRules.removalEndsBinding(Entity.RemovalReason.DISCARDED));
  	}

  	@Test
  	void chunkUnloadKeepsBinding() {
  		assertFalse(BindingRules.removalEndsBinding(Entity.RemovalReason.UNLOADED_TO_CHUNK));
  	}

  	@Test
  	void playerUnloadKeepsBinding() {
  		assertFalse(BindingRules.removalEndsBinding(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
  	}

  	@Test
  	void dimensionChangeKeepsBinding() {
  		assertFalse(BindingRules.removalEndsBinding(Entity.RemovalReason.CHANGED_DIMENSION));
  	}

  	@Test
  	void noReasonKeepsBinding() {
  		assertFalse(BindingRules.removalEndsBinding(null));
  	}
  ```
  If `assertTrue`/`assertFalse` are not already statically imported in this file, add
  `import static org.junit.jupiter.api.Assertions.assertFalse;` and
  `import static org.junit.jupiter.api.Assertions.assertTrue;`.
  **Check:** `TEST` → compilation FAILS with `cannot find symbol ... removalEndsBinding`.

- [ ] **Step 1.2** `src/main/java/io/github/cumsauce/stalker/world/BindingRules.java` — add the
  import after `import java.util.UUID;`:
  ```java
  import net.minecraft.world.entity.Entity;
  ```
  Add before the final closing `}` of the class:
  ```java
  	/**
  	 * Whether an entity removal should end the villager's binding. Only removals that destroy the
  	 * entity do (killed, discarded — lightning conversion, /kill, despawn). Chunk unloads and
  	 * dimension changes keep it (plan §7: bindings survive both), as does a null reason.
  	 */
  	public static boolean removalEndsBinding(Entity.RemovalReason reason) {
  		return reason != null && reason.shouldDestroy();
  	}
  ```
  **Check:** `TEST` → `BUILD SUCCESSFUL`, 74 tests, 0 failures.

- [ ] **Step 1.3** `src/main/java/io/github/cumsauce/stalker/world/StalkerManager.java` — in
  `initialize()`, anchor:
  ```java
  		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
  			if (entity instanceof Villager villager) {
  				onVillagerRemoved(level, villager);
  			}
  		});
  ```
  Replace with:
  ```java
  		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
  			if (entity instanceof Villager villager
  				&& BindingRules.removalEndsBinding(villager.getRemovalReason())) {
  				onVillagerRemoved(level, villager);
  			}
  		});
  ```
  Also replace the comment's last sentence directly above it,
  `// plan-S1 (convert => unbind + blacklist). ENTITY_UNLOAD catches those removals directly.`, with:
  ```java
  		// plan-S1 (convert => unbind + blacklist). ENTITY_UNLOAD catches those removals directly —
  		// but it also fires for chunk unloads and dimension changes, which must keep the binding
  		// (plan §7), so only destroying removal reasons end it.
  ```
  **Check:** `BUILD` → `BUILD SUCCESSFUL`. **MANUAL** → §12 M1, M2.

---

## 5. Group 2 — BUG-2: pretend-bribe betrayal and `/stalker strike` are always variant B

- [ ] **Step 2.1** `src/main/java/io/github/cumsauce/stalker/strike/Strikes.java` — anchor (whole
  method):
  ```java
  	public static void strike(Villager villager, ServerPlayer player) {
  		// The tell: face the victim, sting, camera snap.
  		villager.getLookControl().setLookAt(player);
  		Cues.strikeTell(villager);
  		Net.strikeTell(player, villager, TELL_SNAP_TICKS);
  ```
  Replace those five lines with:
  ```java
  	public static void strike(Villager villager, ServerPlayer player) {
  		tell(villager, player);
  ```
  Then the method continues with the existing scream block and the `teleportChance` roll — leave
  those lines as they are. Immediately **after** the closing `}` of `strike`, insert:
  ```java
  	/**
  	 * A strike that is always variant B (chase): the full tell, then the chase, no snatch roll.
  	 * Used for the pretend-bribe betrayal (plan §6: "CHASE, variant B forced, tell and all") and
  	 * for {@code /stalker strike}.
  	 */
  	public static void strikeChase(Villager villager, ServerPlayer player) {
  		tell(villager, player);
  		if (VoiceCues.shouldScream(StalkerConfig.get().foleyVoices())) {
  			Net.voiceCue(player, villager.getX(), villager.getY(), villager.getZ(),
  					VoiceCues.VoiceCue.SCREAM, VoiceCues.screamPitch(VoiceCues.newDraw()), VoiceCues.SCREAM_VOLUME);
  		}
  		chase(villager, player);
  	}

  	/** The tell shared by both strike entry points: face the victim, sting, camera snap. */
  	private static void tell(Villager villager, ServerPlayer player) {
  		villager.getLookControl().setLookAt(player);
  		Cues.strikeTell(villager);
  		Net.strikeTell(player, villager, TELL_SNAP_TICKS);
  	}
  ```
  (The scream block is copied verbatim from `strike`, lines 97-100; the `chase` method at 156-168
  is private and in the same class, so it is callable here.)
  **Check:** `BUILD` → `BUILD SUCCESSFUL`.

- [ ] **Step 2.2** `src/main/java/io/github/cumsauce/stalker/world/StalkerManager.java` — anchor:
  ```java
  		if (level.getEntity(state.villagerId) instanceof Villager villager) {
  			Strikes.strike(villager, player);
  		}
  ```
  (this is inside `forceStrike`; it is the only `Strikes.strike` call preceded by
  `level.getEntity(state.villagerId) instanceof Villager villager`). Replace with:
  ```java
  		if (level.getEntity(state.villagerId) instanceof Villager villager) {
  			Strikes.strikeChase(villager, player);
  		}
  ```
  **Check:** `BUILD` → `BUILD SUCCESSFUL`. **MANUAL** → §12 M3.

---

## 6. Group 3 — BUG-3: a peek that actually moves, and ducks back when watched

- [ ] **Step 3.1** `StalkerManager.java` — add a constant. Anchor:
  ```java
  	private static final int PEEK_TICKS = 30;
  ```
  Insert after it:
  ```java
  	/** How far from the cover centre counts as "still at cover" while hidden; beyond it (after a
  	 * peek) the villager steps back to the centre. Smaller than the 0.75 sidestep. */
  	private static final double PEEK_RETURN_DISTANCE = 0.5;
  ```

- [ ] **Step 3.2** `StalkerManager.java`, inside `tickCover` — anchor (the whole hidden block, from
  the `// Hidden at cover.` comment through the closing brace of the peek `if`; it is the only
  occurrence of `// Hidden at cover.`):
  ```java
  		// Hidden at cover.
  		StalkerBrain.setTarget(villager, null, 0.0F);
  		villager.setPose(Pose.CROUCHING);

  		if (distance <= PEEK_PLAYER_RANGE && !Senses.lookingAtPos(player, coverCenter.add(0.0, 1.0, 0.0), c.spottedAngleDeg())) {
  			if (state.peekTicks > 0) {
  				state.peekTicks--;
  				StalkerBrain.lookAt(villager, player);

  				Vec3 away = villager.position().subtract(player.position());
  				Vec3 side = new Vec3(-away.z, 0.0, away.x).normalize().scale(0.75);
  				Vec3 peekTarget = coverCenter.add(state.peekSide ? side : side.scale(-1.0));
  				issueMovement(villager, state, peekTarget, STALK_SPEED);
  				if (state.peekTicks == 0) {
  					issueMovement(villager, state, coverCenter, STALK_SPEED);
  				}
  			} else {
  				state.peekCooldown--;
  				if (state.peekCooldown <= 0) {
  					state.peekTicks = PEEK_TICKS;
  					state.peekSide = level.getRandom().nextBoolean();
  					state.peekCooldown = rollPeekCooldown(level);
  					Cues.peek(villager);
  				}
  			}
  		}
  ```
  (There may be a blank line or a short comment between `issueMovement(... peekTarget ...)` and
  `if (state.peekTicks == 0)`; include whatever is there in the replaced range.) Replace with:
  ```java
  		// Hidden at cover. Hold at the cover centre — except mid-peek: erasing the move target every
  		// tick would stop the sidestep issued below the moment it starts. After a peek, step back.
  		if (state.peekTicks <= 0) {
  			if (villager.position().distanceTo(coverCenter) > PEEK_RETURN_DISTANCE) {
  				state.lastTarget = null;
  				issueMovement(villager, state, coverCenter, STALK_SPEED);
  			} else {
  				issueMovement(villager, state, null, 0.0F);
  			}
  		}
  		villager.setPose(Pose.CROUCHING);

  		boolean watched = Senses.lookingAtPos(player, coverCenter.add(0.0, 1.0, 0.0), c.spottedAngleDeg());
  		if (watched || distance > PEEK_PLAYER_RANGE) {
  			// Caught leaning out (or too far to bother): end the peek now; the block above ducks back.
  			state.peekTicks = 0;
  		} else if (state.peekTicks > 0) {
  			state.peekTicks--;
  			StalkerBrain.lookAt(villager, player);

  			Vec3 away = villager.position().subtract(player.position());
  			Vec3 side = new Vec3(-away.z, 0.0, away.x).normalize().scale(0.75);
  			Vec3 peekTarget = coverCenter.add(state.peekSide ? side : side.scale(-1.0));
  			issueMovement(villager, state, peekTarget, STALK_SPEED);
  		} else {
  			state.peekCooldown--;
  			if (state.peekCooldown <= 0) {
  				state.peekTicks = PEEK_TICKS;
  				state.peekSide = level.getRandom().nextBoolean();
  				state.peekCooldown = rollPeekCooldown(level);
  				Cues.peek(villager);
  			}
  		}
  ```
  Why each piece: `issueMovement(..., null, ...)` is the existing null path (`setTarget null` +
  `lastTarget = null`), so the hidden hold is unchanged when not peeking; `state.lastTarget = null`
  before the return issue defeats the stale filter at `issueMovement` (0.75² < 4 would otherwise
  swallow it); `watched` ending the peek fixes the frozen-lean (b). `Cues.peek` is still called at
  the same moment with the same arguments (lie-layer contract intact).
  **Check:** `BUILD` → `BUILD SUCCESSFUL`. **MANUAL** → §12 M4.

---

## 7. Group 4 — BUG-4: remember unreachable cover

- [ ] **Step 4.1** `StalkerManager.java` imports — anchor `import java.util.HashMap;`. Insert after it:
  ```java
  import java.util.HashSet;
  ```

- [ ] **Step 4.2** `StalkerManager.java`, `State` class — anchor (field declaration):
  ```java
  		BlockPos coverPos;
  ```
  Insert after it (keep the same indentation):
  ```java
  		/** Cover positions the villager timed out dashing to this COVER phase; skipped on re-search. */
  		final Set<BlockPos> failedCover = new HashSet<>();
  ```
  (`java.util.Set` is already imported at line 6.)

- [ ] **Step 4.3** `StalkerManager.java`, `findCoverNow` — anchor:
  ```java
  		for (BlockPos candidate : candidates) {
  			if (probes >= 3) {
  				break;
  			}
  			probes++;
  ```
  Replace with:
  ```java
  		for (BlockPos candidate : candidates) {
  			if (probes >= 3) {
  				break;
  			}
  			if (state.failedCover.contains(candidate)) {
  				continue;
  			}
  			probes++;
  ```
  Then anchor (same method, a few lines down):
  ```java
  		state.coverPos = candidates.isEmpty() ? null : candidates.get(0);
  ```
  Replace with:
  ```java
  		state.coverPos = candidates.stream()
  			.filter(candidate -> !state.failedCover.contains(candidate))
  			.findFirst()
  			.orElse(null);
  ```

- [ ] **Step 4.4** `StalkerManager.java`, `tickCover` dash timeout — anchor:
  ```java
  			if (state.dashTicks > DASH_TIMEOUT_TICKS) {
  				state.coverPos = null;
  ```
  Replace with:
  ```java
  			if (state.dashTicks > DASH_TIMEOUT_TICKS) {
  				state.failedCover.add(state.coverPos);
  				state.coverPos = null;
  ```
  (Order matters: `resetMovement()` two lines later must not clear the set — it does not; the set
  is deliberately not part of `resetMovement`.)

- [ ] **Step 4.5** `StalkerManager.java`, `tickCover` — clear the set when cover is reached and on
  both COVER→STALK exits. Three anchors:
  1. Anchor `		state.dashTicks = 0;` that is immediately followed by the `// Hidden at cover.`
     comment (after Step 3.2 it is followed by the new multi-line comment starting
     `// Hidden at cover. Hold at the cover centre`). Replace that single line with:
     ```java
     		state.dashTicks = 0;
     		state.failedCover.clear();
     ```
  2. Anchor (the no-cover exit):
     ```java
     				state.phase = Phases.Phase.STALK;
     				state.coverPos = null;
     				state.resetMovement();
     				return;
     ```
     Replace with:
     ```java
     				state.phase = Phases.Phase.STALK;
     				state.coverPos = null;
     				state.failedCover.clear();
     				state.resetMovement();
     				return;
     ```
  3. Anchor (the unspotted exit):
     ```java
     				state.phase = Phases.Phase.STALK;
     				villager.setPose(Pose.STANDING);
     				state.coverPos = null;
     				state.resetMovement();
     ```
     Replace with:
     ```java
     				state.phase = Phases.Phase.STALK;
     				villager.setPose(Pose.STANDING);
     				state.coverPos = null;
     				state.failedCover.clear();
     				state.resetMovement();
     ```
  **Check:** `BUILD` → `BUILD SUCCESSFUL`. **MANUAL** → §12 M5.

---

## 8. Group 5 — AI-1, AI-2, AI-4: `tickStalk` target selection

- [ ] **Step 5.1** `StalkerManager.java`, `nextPlanHop` — make it never return null. Three anchors:
  1. Javadoc anchor: `	 * The next hop of the committed approach plan, or null when there is none. The plan is a queue`
     → replace with
     `	 * The next hop of the committed approach plan, or the plan target itself when no hop fits. The plan is a queue`
  2. Anchor:
     ```java
     		if (state.planRebuildCooldown > 0) {
     			return state.plan.peek();
     		}
     ```
     Replace with:
     ```java
     		if (state.planRebuildCooldown > 0) {
     			Vec3 pending = state.plan.peek();
     			return pending != null ? pending : planTarget;
     		}
     ```
  3. Anchor (end of method):
     ```java
     		Vec3 hop = state.plan.peek();
     		return hop == null ? null : hop;
     ```
     Replace with:
     ```java
     		Vec3 hop = state.plan.peek();
     		return hop == null ? planTarget : hop;
     ```
  (`planTarget` is the local declared at the top of the method via
  `Vec3 planTarget = ApproachPlanner.planTarget(...)`; both replaced sites are after it.)

- [ ] **Step 5.2** `StalkerManager.java`, `tickStalk` — anchor (from `Vec3 target;` through the
  closing brace of the `if (target == null && state.spottedTicks > 0)` block; there may be
  comment lines inside the range — include them):
  ```java
  		Vec3 target;
  		if (advancing) {
  ```
  …down to and including:
  ```java
  		if (target == null && state.spottedTicks > 0) {
  			actNormal(level, player, villager, state);
  			return;
  		}
  ```
  Replace the whole range with:
  ```java
  		Vec3 target;
  		boolean holdingAtCover = false;
  		boolean outsideAmbushRange = distance > c.strikeRange() * Ambush.RANGE_FACTOR;
  		if (advancing) {
  			// Hop by hop through cover toward the plan target (intercept point, or the last place the
  			// player was seen); nextPlanHop returns the plan target itself when no hop fits.
  			target = nextPlanHop(level, player, villager, state);
  		} else if (outsideAmbushRange) {
  			// Not advancing and not yet in ambush range: get behind cover and watch from there
  			// (plan §2: inside 12 blocks, hold behind cover). Standing still at cover is the hold.
  			Optional<BlockPos> cover = ensureCover(level, player, villager, state, c);
  			Vec3 coverCenter = cover
  				.map(pos -> Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5))
  				.orElse(null);
  			holdingAtCover = coverCenter != null
  				&& coverCenter.distanceTo(villager.position()) <= MIN_COVER_DISTANCE;
  			target = holdingAtCover ? null : coverCenter;
  		} else {
  			// Inside ambush range and unspotted: stand still; tickAmbush is counting.
  			target = null;
  		}

  		// Nothing to walk to: when spotted, or when there is simply no cover to use, act like an
  		// ordinary villager (plan §3) instead of standing frozen. Holding at cover and the ambush
  		// stillness are the two deliberate stand-stills.
  		if (target == null && (state.spottedTicks > 0 || (outsideAmbushRange && !holdingAtCover))) {
  			actNormal(level, player, villager, state);
  			return;
  		}
  ```
  JUDGEMENT (pre-decided): the cover-seek threshold is `strikeRange * Ambush.RANGE_FACTOR`
  (2.2 × 2.0 = 4.4 blocks by default), i.e. the ambush range, not `orbitInner`. `c.orbitInner()` is
  then no longer read in `tickStalk`; leave the config field and its test alone. `Ambush` is in the
  same package (`world`), no import needed.
  **Check:** `BUILD` → `BUILD SUCCESSFUL`; `TEST` → 74 tests green. **MANUAL** → §12 M6, M7, M8.

---

## 9. Group 6 — BUG-5a: heal stale brain memory / one-sided binding on entity load

- [ ] **Step 6.1** `StalkerManager.java`, `initialize()` — anchor is the `ENTITY_UNLOAD` registration
  as it reads **after Step 1.3**:
  ```java
  		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
  			if (entity instanceof Villager villager
  				&& BindingRules.removalEndsBinding(villager.getRemovalReason())) {
  				onVillagerRemoved(level, villager);
  			}
  		});
  ```
  Insert immediately after it:
  ```java
  		// The stalking memories are codec-backed and saved with the villager, so one that unloaded
  		// mid-walk comes back still heading for a stale point; and a one-sided binding (owner gone
  		// or no longer claiming it) would otherwise only be healed when a bind roll happens to scan it.
  		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
  			if (entity instanceof Villager villager) {
  				onVillagerLoaded(level, villager);
  			}
  		});
  ```

- [ ] **Step 6.2** `StalkerManager.java` — add the handler. Anchor: the javadoc/method start of
  `onVillagerRemoved`:
  ```java
  	public static void onVillagerRemoved(ServerLevel level, Villager villager) {
  ```
  Insert **before** the javadoc comment that precedes this line (if there is no javadoc, insert
  directly before the line):
  ```java
  	/**
  	 * A villager (re)entering a loaded chunk: drop any persisted move target — the manager
  	 * re-issues one on its next tick if the villager is still bound and in range — and heal a
  	 * one-sided binding so the villager is not stuck ineligible and in the STALKING activity.
  	 */
  	public static void onVillagerLoaded(ServerLevel level, Villager villager) {
  		if (StalkerBrain.isHandling(villager)) {
  			StalkerBrain.setTarget(villager, null, 0.0F);
  		}
  		if (Attachments.isBound(villager) && !BindingRules.bindingIsLive(ownerState(level, villager))) {
  			Attachments.unbindVillager(villager);
  			StalkerBrain.release(villager);
  		}
  	}

  ```
  (`ownerState(ServerLevel, Villager)` is the existing private helper; `StalkerBrain.isHandling`,
  `setTarget`, `release` and `Attachments.isBound/unbindVillager` all exist with these signatures.)

- [ ] **Step 6.3** `StalkerManager.java`, `isEligible` — anchor:
  ```java
  		if (isBound && !BindingRules.bindingIsLive(owner)) {
  			Attachments.unbindVillager(villager);
  		}
  ```
  Replace with:
  ```java
  		if (isBound && !BindingRules.bindingIsLive(owner)) {
  			Attachments.unbindVillager(villager);
  			StalkerBrain.release(villager);
  		}
  ```
  **Check:** `BUILD` → `BUILD SUCCESSFUL`; `TEST` green (`MemoryAccessesTest` unchanged — no new
  memory reads). **MANUAL** → §12 M9.

---

## 10. Group 7 — BUG-5b: force-load the villager's last chunk so relocation can run

**Confidence: MEDIUM.** `ServerLevel.setChunkForced(int, int, boolean)` is confirmed to exist. The
SPECULATIVE part is timing: entities in a newly forced chunk attach asynchronously, a few ticks after
the chunk reaches full status. The design tolerates that (it keeps the chunk forced up to 200 ticks
and checks every tick), and releases the force in every path that discards the `State`, including
server stop, so a forced chunk cannot leak into the save. If M10 fails in game, revert this group
only (it is self-contained) — the rest of the plan does not depend on it.

- [ ] **Step 7.1** `StalkerManager.java` imports — anchor `import net.minecraft.server.level.ServerLevel;`.
  Insert after it:
  ```java
  import net.minecraft.server.MinecraftServer;
  import net.minecraft.resources.ResourceKey;
  import net.minecraft.world.level.ChunkPos;
  import net.minecraft.world.level.Level;
  ```
  (If any of these four is already imported, skip that line only.)

- [ ] **Step 7.2** `StalkerManager.java` constants — anchor:
  ```java
  	private static final int ORPHAN_UNBIND_TICKS = 1200;
  ```
  Insert after it:
  ```java
  	/** Longest a villager's last chunk stays force-loaded waiting for it to appear for relocation. */
  	private static final int FORCE_LOAD_MAX_TICKS = 200;
  ```

- [ ] **Step 7.3** `StalkerManager.java`, `State` — anchor `		int unloadedTicks;` (field). Insert after it:
  ```java
  		/** Where the villager last was while loaded, for force-loading its chunk when it goes missing. */
  		BlockPos lastKnownPos;
  		ResourceKey<Level> lastKnownLevel;
  		/** The chunk currently force-loaded on this binding's behalf, or null. */
  		ChunkPos forcedChunk;
  		ResourceKey<Level> forcedLevel;
  		int forcedTicks;
  ```

- [ ] **Step 7.4** `StalkerManager.java` — add two helpers. Anchor: the line
  `	private static void dropPlayerSide(ServerPlayer player) {`. Insert **before** the javadoc/comment
  preceding it (or directly before the line if none):
  ```java
  	/** Removes a player's State and releases any chunk it had force-loaded. All State removals go through here. */
  	private static State discardState(MinecraftServer server, UUID playerId) {
  		State state = STATES.remove(playerId);
  		if (state != null) {
  			releaseForcedChunk(server, state);
  		}
  		return state;
  	}

  	private static void releaseForcedChunk(MinecraftServer server, State state) {
  		if (state.forcedChunk == null) {
  			return;
  		}
  		ServerLevel forcedIn = state.forcedLevel == null ? null : server.getLevel(state.forcedLevel);
  		if (forcedIn != null) {
  			forcedIn.setChunkForced(state.forcedChunk.x(), state.forcedChunk.z(), false);
  		}
  		state.forcedChunk = null;
  		state.forcedLevel = null;
  		state.forcedTicks = 0;
  	}

  ```

- [ ] **Step 7.5** `StalkerManager.java` — route every `STATES.remove` through `discardState`.
  Six anchors, each replaced by exactly one line:
  1. In the `DISCONNECT` handler: `				STATES.remove(player.getUUID());`
     → `				discardState(server, player.getUUID());`
     (`server` is the handler lambda's second parameter: `(handler, server) ->`.)
  2. In `resolveVillager`: `			STATES.remove(player.getUUID());`
     → `			discardState(level.getServer(), player.getUUID());`
  3. In `unbind(ServerPlayer, boolean)`: `		State state = STATES.remove(player.getUUID());`
     → `		State state = discardState(player.level().getServer(), player.getUUID());`
  4. In `onVillagerRemoved`: `		STATES.remove(boundPlayer);`
     → `		discardState(level.getServer(), boundPlayer);`
  5. In `dropBinding`: `		STATES.remove(player.getUUID());`
     → `		discardState(level.getServer(), player.getUUID());`
     (`level` is `dropBinding`'s first parameter.)
  6. In `dropVillagerSide`: `		State state = STATES.remove(player.getUUID());`
     → `		State state = discardState(player.level().getServer(), player.getUUID());`
  7. In `dropPlayerSide`: `		STATES.remove(player.getUUID());`
     → `		discardState(player.level().getServer(), player.getUUID());`
  After this, `grep -n "STATES.remove" StalkerManager.java` must return exactly one line — the one
  inside `discardState`. (`player.level().getServer()` returns `MinecraftServer`, nullable in the
  type system; a `ServerPlayer` on the server always has one.)

- [ ] **Step 7.6** `StalkerManager.java`, `initialize()` — anchor:
  ```java
  		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CoverTrace.reset());
  ```
  Replace with:
  ```java
  		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
  			CoverTrace.reset();
  			// Forced chunks are saved with the level; never let one outlive the State that asked for it.
  			for (State state : STATES.values()) {
  				releaseForcedChunk(server, state);
  			}
  		});
  ```

- [ ] **Step 7.7** `StalkerManager.java`, `resolveVillager` — replace the whole method. Anchor: from
  `	private static Villager resolveVillager(ServerLevel level, ServerPlayer player, State state, StalkerConfig c) {`
  through its closing `	}` (the method body is quoted in full in §1 of the findings; it ends with
  `		return null;` then `	}`). Replace with:
  ```java
  	private static Villager resolveVillager(ServerLevel level, ServerPlayer player, State state, StalkerConfig c) {
  		Entity inLevel = level.getEntity(state.villagerId);
  		if (inLevel instanceof Villager villager) {
  			state.unloadedTicks = 0;
  			state.lastKnownPos = villager.blockPosition();
  			state.lastKnownLevel = level.dimension();
  			releaseForcedChunk(level.getServer(), state);
  			tickStuckCheck(villager, state);
  			return villager;
  		}

  		state.unloadedTicks++;
  		Entity anywhere = level.getEntityInAnyDimension(state.villagerId);
  		if (anywhere instanceof Villager villager) {
  			state.lastKnownPos = villager.blockPosition();
  			state.lastKnownLevel = villager.level().dimension();
  			if (state.unloadedTicks > c.relocateBehindTicks() && relocate(villager, player, level, c)) {
  				state.unloadedTicks = 0;
  				releaseForcedChunk(level.getServer(), state);
  				return villager;
  			}
  			// Loaded but elsewhere (other dimension, or far behind): keep ticking until relocation is due.
  			return null;
  		}

  		// Not loaded anywhere. Once relocation is due, force-load the chunk it was last seen in so it
  		// reappears for the branch above (plan §7: an unloaded villager relocates, it is not dropped).
  		// The force is bounded and released by every path that discards this State.
  		if (state.unloadedTicks > c.relocateBehindTicks() && state.forcedChunk == null
  			&& state.lastKnownPos != null && state.lastKnownLevel != null) {
  			ServerLevel home = level.getServer().getLevel(state.lastKnownLevel);
  			ChunkPos chunk = ChunkPos.containing(state.lastKnownPos);
  			if (home != null && home.setChunkForced(chunk.x(), chunk.z(), true)) {
  				state.forcedChunk = chunk;
  				state.forcedLevel = state.lastKnownLevel;
  				state.forcedTicks = 0;
  			}
  		}
  		if (state.forcedChunk != null && ++state.forcedTicks > FORCE_LOAD_MAX_TICKS) {
  			releaseForcedChunk(level.getServer(), state);
  		}

  		if (state.unloadedTicks > ORPHAN_UNBIND_TICKS) {
  			dropPlayerSide(player);
  		}
  		return null;
  	}
  ```
  Notes for the implementer: `ChunkPos` is a record in 26.3 — build it with `ChunkPos.containing(BlockPos)`
  and read chunk coordinates with the `x()`/`z()` accessor methods (there is no `(BlockPos)` constructor
  and no public `x`/`z` fields), verified with `javap` on `minecraft-merged.jar`;
  `Entity.blockPosition()` and `Level.dimension()` are vanilla. The old second `STATES.remove`
  after `dropPlayerSide` is gone on purpose — `dropPlayerSide` already discards the State
  (Step 7.5 item 7). The force-load attempt happens once per unload episode; if it is released by the
  200-tick cap, `forcedChunk` is null again and the attempt repeats on the next tick — that is
  acceptable (a lost villager retries until the 1200-tick orphan drop).
  **Check:** `BUILD` → `BUILD SUCCESSFUL`; `TEST` green; `grep -c "STATES.remove"` on the file → `1`.
  **MANUAL** → §12 M10.

---

## 11. Final verification

- [ ] **Step F.1** Run `BUILD` exactly as in §0. Expected last lines include `BUILD SUCCESSFUL`.
- [ ] **Step F.2** Run `TEST` exactly as in §0. Expected: `BUILD SUCCESSFUL`; the JUnit report at
  `build/reports/tests/test/index.html` shows **74 tests, 0 failures, 0 ignored**
  (68 baseline + 6 in `BindingRulesTest`).
- [ ] **Step F.3** `cd "/Volumes/My Shared Files/mc/horror-mod"; git status --short` — expected
  exactly these modified/added paths and nothing else:
  ```
   M src/main/java/io/github/cumsauce/stalker/strike/Strikes.java
   M src/main/java/io/github/cumsauce/stalker/world/BindingRules.java
   M src/main/java/io/github/cumsauce/stalker/world/StalkerManager.java
   M src/test/java/io/github/cumsauce/stalker/world/BindingRulesTest.java
  ?? docs/
  ```
- [ ] **Step F.4** Do not commit. Hand over to a human for §12.

---

## 12. MANUAL IN-GAME VERIFICATION (human, dev client via `./gradlew runClient`, creative→survival)

Setup for all: new flat/normal world, locate a village, `/stalker start` (binds nearest eligible
villager), `/stalker debug` to see phase text, `/gamemode survival`. Default `config/stalker.json`
unless stated. "Phase" is read from `/stalker status`.

| ID | Group | Steps | Expected (PASS) | FAIL looks like |
| -- | ----- | ----- | --------------- | --------------- |
| M1 | 1 | Bind. Sprint ≥ 12 chunks straight away (beyond server view distance) and stay 60 s. `/stalker status`. | Status still reports a binding (villager "unloaded" or relocated). No blacklist message. | "not bound" / villager blacklisted. |
| M2 | 1 | Bind. Build a nether portal, enter it, wait 40 s (`relocateBehindTicks` 600 = 30 s), look around. | Villager appears 48–64 blocks away in the Nether (cross-dim relocate). Status bound. | Unbound on portal entry. |
| M3 | 2 | Edit `config/stalker.json`: `"teleportChance": 1.0`, `/stalker reload`. Bind. Right-click villager holding 1 emerald (below `bribeThreshold` 8). Wait ≤ 4 s. Repeat 5×. | Every time: camera snap/sting tell, then **blindness + chase** (variant B). Never a teleport. Also `/stalker strike` 5× → always chase. | Any darkness + teleport (snatch). |
| M4 | 3 | Bind. Let it see you (`/stalker spot`) so it enters COVER behind a block; stand ~10 blocks away, look 90° away, wait up to 13 s (peek cooldown 140–260 ticks) and glance back after the peek sound. Then: during a peek, look straight at the cover. | Villager visibly steps ~0.75 block to one side for ~1.5 s and steps back to centre. When looked at mid-peek it ducks back within ~1 s instead of staying leaned out. | No visible sidestep; or stays leaned out while watched. |
| M5 | 4 | Fence a 3×3 pen with a single standable spot behind a wall that is the obvious best cover; put the villager outside with no gate; `/stalker spot`. Watch 20 s. | After one 4 s dash attempt it stops trying that spot: it either picks another cover or acts normal. | Repeated 4 s sprints at the fence. |
| M6 | 5 | Bind at night; face the villager at a 45–60° angle from ~8 blocks with clear LOS (not in the 25° cone, so unspotted; look dot > 0.35, so no approach). | Villager moves to and holds behind nearby cover (crouch not required in STALK) rather than standing still in the open. | Stands frozen in the open. |
| M7 | 5 | In an open plains area with no blocks within 10 m, bind, stand still 8 blocks away looking away. | Villager wanders/glances like a normal villager (`actNormal`) rather than freezing; if you walk away it resumes approach. | Stands dead still indefinitely. |
| M8 | 5 | Bind, break LOS behind a house, walk 10 blocks along the wall. | Villager heads for where you were last seen (corner), then re-acquires. Not through-the-wall homing on your live position. | Walks directly toward your live position behind the wall. |
| M9 | 6 | Bind, `/stalker phase STALK`, walk away ≥ 12 chunks (villager unloads), `/stalker stop`, walk back. Observe the villager. | Villager is idle vanilla (not walking to a fixed stale point). `/stalker start` can bind it again. | Villager marches to one spot; "no eligible villager". |
| M10 | 7 | Bind. Sprint ≥ 12 chunks away (unload), wait 40 s standing still, look around. | Villager appears 48–64 blocks away (relocated). `/forceload query` (if available in dev) lists no stalker chunk afterwards. Save & quit, reopen: no forced chunks. | Still unbound after 60 s (orphan drop), or a forced chunk persists. If FAIL: revert Group 7 only. |

---

## 13. Self-review notes (for the reviewer, not the executor)

- Spec coverage: §7 (BUG-1, 5a, 5b), §6 (BUG-2), §3 peek/no-cover (BUG-3, AI-4), §2 bands (AI-2),
  approach design (AI-1), unreachable cover (BUG-4, implied by §3 "pathable cover").
- Type consistency: `BindingRules.removalEndsBinding(Entity.RemovalReason)`;
  `Strikes.strikeChase(Villager, ServerPlayer)`; `StalkerManager.onVillagerLoaded(ServerLevel, Villager)`;
  `discardState(MinecraftServer, UUID): State`; `releaseForcedChunk(MinecraftServer, State)`;
  `State.failedCover: Set<BlockPos>`; `State.lastKnownPos/lastKnownLevel/forcedChunk/forcedLevel/forcedTicks`.
- Low-confidence items, stated: Group 7 timing (MEDIUM); visible magnitude of BUG-3 stutter
  (SPECULATIVE; the fix is correct regardless). Everything else CONFIRMED against source/jars.
