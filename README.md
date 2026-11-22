# Salvage Fishing (Forge 1.20.1)

Casting into a liquid starts a dive: the hook drops down a 2D shaft between rock walls. Hold A/D (or the mouse
button, with the cursor within reach of the hook) to drift. Jig to jerk the hook sideways like a flappy-bird hop:
double-tap A/D for the first jig, then each tap while it's live (0.75 s) jigs again, bigger each time, at most one
jig per 0.5 s. Holding the mouse far from the hook jigs toward it. The line hangs loose from the top, gets yanked by
jigs, and the hook swings along it. Touching anything snags it; rock or the floor ends the dive with nothing;
right-click reels in. Common catches swim back and forth (flipping as they turn); rare, treasure-like ones sit
still. When the floor comes into view the view stops.

Everything in the shaft comes from the liquid's fishing loot table. Rarer items get commoner the deeper you go
(weight w becomes w^(1 - 1.6 × depth)), and it gets darker.

## Data
`data/<ns>/salvagefishing_pools/<name>.json`:
```json
{ "fluid": "#minecraft:water", "loot_table": "minecraft:gameplay/fishing", "theme": "water" }
```
Optional: `hook` (only this bobber entity type), `direct` (catch goes straight to the inventory).
`colors`: any of `sky, top, mid, bottom, foam, light, deep, bubble` as `"#RRGGBB"`/`"#AARRGGBB"` to give a liquid its own look
(rock is always a dark silhouette, so no textures are needed). A table at
`<loot_table>/<dim namespace>/<dim path>` is used in that dimension if it exists.

**Floor treasure:** one item rests in the middle of the dive's floor, rolled from the pool's `floor_loot_table` if set, else `<pool namespace>:floor/<pool name>`. No table or an empty roll = no floor treasure. Salvage Fishing ships `salvagefishing:floor/water` with a **gold block** as a placeholder: override that loot table in your pack (a mod overriding it should load after Salvage Fishing). Themes: `water`, `lava`.

The caught stack is passed through global loot modifiers once more as table `salvagefishing:catch`, with the real
bobber and angler, so angler-based modifiers still apply. `ItemFishedEvent` is fired as for normal fishing.
