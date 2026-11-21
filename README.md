# Salvage Fishing (Forge 1.20.1)

Casting into a liquid starts a dive: the hook drops down a 2D shaft between rock walls. Steer it with A/D (a hard
counter-strafe stop when you switch direction) or by clicking and dragging (the arrow's stretch sets the pull).
Touching anything snags it; rock ends the dive with nothing; right-click reels in.

Everything in the shaft comes from the liquid's fishing loot table. Rarer items get commoner the deeper you go
(weight w becomes w^(1 - 1.6 × depth)), and it gets darker.

## Data
`data/<ns>/salvagefishing_pools/<name>.json`:
```json
{ "fluid": "#minecraft:water", "loot_table": "minecraft:gameplay/fishing", "theme": "water" }
```
Optional: `hook` (only this bobber entity type), `direct` (catch goes straight to the inventory). A table at
`<loot_table>/<dim namespace>/<dim path>` is used in that dimension if it exists. Themes: `water`, `lava`.

The caught stack is passed through global loot modifiers once more as table `salvagefishing:catch`, with the real
bobber and angler, so angler-based modifiers still apply. `ItemFishedEvent` is fired as for normal fishing.
