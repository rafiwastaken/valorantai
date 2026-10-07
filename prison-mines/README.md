# Prison Mines (PacketEvents)

Per-player virtual prison mines. Every player mines the **same physical location**, but each one has
a private instance of it: what you break, what resets, and what your enchants clear is invisible to
everyone else. There is no grid of mine copies, the world only contains one mine area, and a packet
layer shows each player their own version of it.

```
prison-mines/
  core/     pure Kotlin, no Bukkit/PacketEvents. All the decisions. Unit-tested (24 tests).
  plugin/   thin PacketEvents + Bukkit adapter: packets in/out, events, config, commands.
```

## The idea in one paragraph

A player's instance of a mine is **a seed plus a sparse "mined" bitset**. The block a player sees at
any position is `if (mined) AIR else generator(seed, x, y, z)`. Nothing else is stored. That makes an
instance cost ~100 bytes until you start mining, reset is "swap the seed / clear the bitset", and the
whole mine never has to exist in memory per player. The **real world is air inside the mine cuboid**;
the virtual blocks only exist on the wire.

## Architecture

```
 netty thread                         main thread (once per tick)                 netty thread
 ------------                         ---------------------------                 ------------
 MinePacketListener                   InboundProcessor                            PacketSink
  - dig / place packet in mine?  -->   - gamemode, reach, enchants from PDC        - one multi-block-change
    cancel + enqueue (Inbound)         MineService.dig  (dig state machine)          packet per touched
  - chunk packet overlapping a         BreakEngine.plan  (enchants nominate)         section per player
    mine? after it is written:         hooks.beforeBreak (veto / events)           - ack (1.19+) AFTER the
    snapshot the player's view  --->   BreakEngine.commit (clip, apply to bitset)    block updates it confirms
  - foreign block updates inside       hooks.afterBreak  (rewards, effects)
    a mine? rewrite to our view        MineService.tick  (flush dirty sessions,
                                         paced RESHUFFLE re-sends, timed resets)
```

| Piece | Responsibility |
|---|---|
| `MineDefinition` | Immutable. World, **mine cuboid** (virtual, breakable), **safe cuboid** (protected ring, ⊇ mine), weighted palette, spawn, reset policy. |
| `MineIndex` | Immutable chunk→mines hash lookup. Safe to read from any thread; swapped atomically on reload. |
| `MineSession` | One player × one mine: `seed`, `BrokenSet`, dirty list, set of chunks the client has, re-send queue. Lock-guarded (netty reads, main writes). |
| `BrokenSet` | Paged sparse bitset (4096-bit pages allocated on first write). |
| `MineGenerator` | Pure `(seed,x,y,z) → palette index`. Stateless, so un-mined blocks are free. |
| `BreakEngine` | `plan` (expand enchants, clip, dedupe, cap) → hooks may veto → `commit`. |
| `MineService` | Orchestrator: players, sessions, dig state machine, flushing, resets, acks. |
| `ViewSink` / `MineHooks` | The only two seams to the platform. |

## How it feels like normal Minecraft

* **Breaking**: the client predicts the break locally as vanilla does. We cancel the dig packets
  (vanilla's world is air there and must never see them), validate on the main thread (same timing
  as vanilla: START must precede FINISH on the same block, reach check, per-tick cap), apply, send
  the section update, then send the **block-change ack** (1.19+ sequence numbers) so the client
  commits its prediction. A rejected dig re-sends the true block, so cheaters/lag just get a
  rollback, exactly like vanilla.
* **Block updates**: a mine change is one `MultiBlockChange` per 16³ section per player per tick,
  however many blocks changed. Chunks the client doesn't have get nothing; they are covered by the
  snapshot sent right after the chunk packet, so a client can't see a stale block (snapshot vs.
  break/flush is atomic under the session lock).
  If another plugin sets a block inside a mine, the outbound `BLOCK_CHANGE` is rewritten to the
  player's own view (`MULTI_BLOCK_CHANGE` triggers a section re-sync).
* **Resets** (`reset.mode`):
  * `restore`: same seed, only the blocks you mined are re-sent. O(mined). Cheapest.
  * `reshuffle`: new seed (new ore layout), the mine is re-sent chunk by chunk under a **global
    per-tick budget** (`resend-chunks-per-tick`), shared fairly between all resetting players, so
    100 players resetting at once can't spike a tick. Players standing in the mine are teleported
    to its spawn. Triggers: % mined, timer, `/pmine reset`, or API.
* **Extra feedback**: the client already plays effects for the block it mined; blocks removed by
  enchants get a capped, sampled break particle/sound for the breaker only.
* **Rewards**: tallies per block kind → items (+ enchant `rewardMultiplier`, unbiased rounding) →
  inventory. Bukkit's `BlockBreakEvent` is *not* fired (the real world never sees these breaks), so
  plugins hook `MineBlockBreakEvent` (cancellable, before) and `MineBlocksBrokenEvent` (after,
  mutable `drops` for auto-sell/tokens).

## Enchants and the safety guarantee

```kotlin
class MyEnchant : MineEnchant {
    override val id = "cross"
    override fun apply(ctx: EnchantContext, level: Int) {
        ctx.addCuboid(ctx.originX - level, ctx.originY, ctx.originZ, ctx.originX + level, ctx.originY, ctx.originZ)
        ctx.rewardMultiplier *= 1.1
    }
}
registry.register(MyEnchant())          // levels live on the pickaxe PDC: prisonmines:ench_cross
```

Enchants have **no access to the world**. They only *nominate* positions via `EnchantContext`
(`add`, `addCuboid`, `addSphere`, `addLayer`). Every nomination goes through one choke point,
`BreakBatch.add`, which rejects anything outside the mine cuboid, anything already mined, anything
past `max-blocks-per-break`. Shape helpers clip their loops to the mine first, so a huge radius is
cheap rather than just ineffective. The only mutation path is `MineSession.applyBreaks` →
the player's own bitset. There is no code path from an enchant to `World#setType`, so the safe area
**cannot** be damaged by enchants, however buggy. A throwing enchant is caught and logged; the
normal break still happens. `MineService.breakBlocks(...)` gives other plugins the same clipped path.
(`enchantsCanNeverEscapeTheMineCuboid` tests an enchant that tries `Int.MIN..Int.MAX` boxes, radius
1000 spheres and out-of-bounds corners.)

The **safe area** (`SafeAreaListener`) protects the *physical* blocks around the mine: break, place,
buckets, explosions, fluids, pistons, fire, mob spawns, optionally PvP.

## Performance model

| | cost |
|---|---|
| Idle player | nothing per tick. Only sessions that changed are queued; timed resets are checked once a second. |
| Memory / session | ~100 B + 512 B per touched 4096-block page (worst case `volume/8` bytes, e.g. 125 KB for a 100³ mine). |
| Break | O(blocks removed). Dedupe via a per-mine epoch-stamp array shared by all sessions (no allocation per break). |
| Flush | O(dirty): group by section, sort+dedupe, one packet per section. |
| Netty hot path | a few `==` checks on packet type, then one `HashMap` lookup. Chunk packets are only decoded in worlds that contain a mine (a cheaper x/z peek is a possible optimisation). |
| Burst safety | per-player `max-breaks-per-tick`, per-break `max-blocks-per-break`, global re-send budget. |

Core smoke test: 500 players on one 64×32×64 mine, 20 ticks of one break each, ≈ 40 ms total (JVM
cold, includes asserting every player's state stays independent).

## Trade-offs you should know about

1. **The real world is air inside the mine.** This is deliberate. If the server thought a block was
   solid where your client shows a hole, vanilla's movement validation would rubber-band anyone who
   walks through a tunnel they dug. With air on the server, the client's own collision decides.
   Consequence: vanilla's "Flying is not enabled" check sees nothing under a player standing on a
   virtual block and kicks after ~5 s. **Set `allow-flight=true` in `server.properties`**;
   `flight-guard` (default on) cancels flight toggles for survival players inside safe areas so this
   doesn't actually grant flight there. (Fall damage should be unaffected since vanilla trusts the client's on-ground flag; worth confirming on your version.)
2. Players in the same physical spot see each other "inside" blocks the other hasn't mined.
3. Lighting is the server's (air), so virtual caves aren't dark.
4. Instances live in memory until the player quits (not persisted across restarts).
5. No anti-cheat for mining *speed* (reach + dig-sequence validation only). The dig state machine is
   where to add a per-tool minimum break time.
6. Gravity blocks / physics aren't simulated for virtual blocks (use non-gravity materials).

## Verification status: please read

* `core` builds and its **24 unit tests pass** (`gradle :core:test`): isolation between players,
  coalescing, ack ordering, dig state machine, reset modes + pacing, enchant clipping, caps,
  self-healing, key packing, sparse set, generator distribution.
* `plugin` was **not compiled against the real PacketEvents / Paper jars**: their Maven repos
  weren't reachable from the environment that wrote this. It was type-checked against hand-written
  stubs of the APIs it uses, which catches mistakes in this code but **not** a wrong recollection of
  an API. The calls most worth a glance on your first build (all in `plugin/`):
  `PacketEvents.getAPI().eventManager`, `event.tasksAfterSend`, `user.sendPacketSilently`,
  `WrappedBlockState.getByString(..).globalId`, the `WrapperPlayServerMultiBlockChange` /
  `EncodedBlock(id,x,y,z)` constructors and `chunkPosition` meaning "section coordinates",
  `WrapperPlayServerEffect(type,pos,data,flag)`, `WrapperPlayClientPlayerBlockPlacement.face.modX`,
  `Player#isChunkSent(long)` (Paper). Bump `packetEventsVersion` in `plugin/build.gradle.kts` to
  the build you run.
* The netty/main-thread interplay (snapshot vs. break) is reasoned through and lock-protected, but
  not stress-tested against a real client. Test with a few clients, including a 1.18 and a 1.20+.

## Setup

1. Install the PacketEvents plugin; set `allow-flight=true`.
2. `gradle :plugin:jar` → drop `plugin/build/libs/plugin-*.jar` into `plugins/`.
3. Edit `config.yml` (a `starter` mine is included) → `/pmine reload`.
4. `/pmine enchant explosive 500` on a held pickaxe to try enchants. `/pmine reset`, `/pmine info`.

Permissions: `prisonmines.reset` (default true), `prisonmines.admin`, `prisonmines.bypass` (ops).

## Where to extend

* **Persistence**: serialize `(seed, BrokenSet)` per session; both are trivially compact.
* **Layered / noise generators**: implement `MineGenerator` (must stay a pure function of seed+position).
* **Cached encoded sections**: with `restore` resets, many players share identical template sections.
* **Folia**: sessions are per-player, the tick would move onto a region/entity scheduler.
