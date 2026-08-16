# StaffCommands

Permission-gated replacements for the staff aliases in Paper's `commands.yml`.

## The problem

Aliases declared in `commands.yml` become `FormattedCommandAlias` instances, and
those carry **no permission**. Paper filters the command graph it sends to each
client node by node, so a vanilla or plugin command a player has no permission
for is hidden from `/` autocomplete and from `/help`. A `commands.yml` alias has
nothing to filter on, so **every player sees every staff alias** — `/ban`,
`/kick`, `/jail` and friends sit in everyone's autocomplete and only fail once
they are run and the underlying command rejects them. That is a Bukkit
limitation, not a misconfiguration.

## What it does

StaffCommands registers those same aliases as real commands through Paper's
`BasicCommand` API (`LifecycleEvents.COMMANDS`), each with a permission.
`permission()` gates both visibility and execution, so *can see it* equals *can
use it*.

The plugin is a generic loader — every command is defined in `config.yml`, so
entries can be added, changed or removed without touching the code.

### Design points

- **Gate on the permission of the command the entry actually runs**
  (`ban` → `minecraft.command.ban`), so no new permission nodes have to be
  invented and the gate can never drift from the thing it protects. A macro with
  no single underlying command is the exception and declares its own node.
- **Fail closed.** An entry with no `permission`, or with no `commands`, is
  logged at SEVERE and *not* registered. A permission-less command would be
  visible to and usable by everyone — the exact leak this plugin exists to
  close — so a typo in `config.yml` has to drop the entry, not publish it.
- **Run as the sender by default.** That preserves the staff member's identity
  (a ban's `source` is their name, not CONSOLE) and their position, which
  matters for any template using `~` or `distance=` — the console executes at
  the world origin. Prefix a line with `@console ` to run that line as the
  console instead: for sub-steps the staff member should not need their own
  permission for (a Discord broadcast, `tellraw @a`), or that are already
  anchored elsewhere (`execute at {player} ...`).
- **Required arguments are derived from the placeholders**, reproducing what
  `commands.yml`'s `$$1` / `$$2-` tokens enforced: a template using `{player}`
  demands at least one argument, one using `{args}` demands two. Too few and the
  whole entry is refused with a usage line and **nothing is dispatched** — so a
  follow-up line cannot announce a ban that never happened.
- **Tab completion** offers online player names for the first argument (filtered
  by `canSee`). `FormattedCommandAlias` inherited that from `Command` for free;
  `BasicCommand` suggests nothing unless told to.

## Configuration

```yaml
aliases:
  ban:
    permission: minecraft.command.ban
    aliases: [gban]
    commands:
      - "minecraft:ban {player} {args}"
      - "@console discordsrv:discord broadcast :hammer: **Banned {player} for {args}**"
  extinguish:
    permission: minecraft.command.fill
    commands:
      # runs as the sender: ~ is relative to the staff member's position
      - "fill ~-10 ~-10 ~-10 ~10 ~10 ~10 minecraft:air replace minecraft:fire"
```

| Key | Meaning |
|---|---|
| `permission` | Gates both visibility and use. Required. |
| `aliases` | Optional extra names for the command. |
| `commands` | Lines to run, in order. `{player}` is the first argument, `{args}` the rest. A line prefixed with `@console ` runs as the console. |

A macro that has no single underlying command to gate on needs a node of its
own, declared in `plugin.yml`. Note that steps running as `@console` execute
with the console's authority, so granting such a node hands out those effects
without the underlying permissions — intended, but worth knowing before granting
it.

## Building

```sh
./gradlew build
```

The jar lands in `build/libs/`. Requires JDK 25 (the Java version Paper 26.2's
API is built for).

## Limitations

- Registering commands through `LifecycleEvents.COMMANDS` **disables `/reload`**
  server-wide (a Paper restriction). Edit `config.yml` and restart the server.
- The lines of an entry run unconditionally in order; a later line does not check
  whether an earlier one succeeded. `Bukkit.dispatchCommand` only reports whether
  the command exists, not what it did — so a broadcast still fires when the ban
  before it failed.
- A plugin command replaces the vanilla root literal of the same name (`ban`,
  `kick`). The namespaced node (`minecraft:ban`) is a separate child of the
  dispatcher root and is left alone, so templates calling `minecraft:ban` reach
  vanilla rather than recursing into the alias.
- When invoked through a secondary name (`/gban`), the generated usage string
  names the primary command (`/ban`).
- Only the first argument is completed. Completing a reason, or completing
  `unban` from the ban list, would need a full Brigadier tree; the `config.yml`
  format would not have to change.

## License

MIT — see [LICENSE](LICENSE).
