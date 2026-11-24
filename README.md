# RTPQueue

A Paper / Folia plugin: type `/rtpqueue` to wait in a queue. As soon as a second player joins, both of you are teleported to the same random spot in the world, so you can fight without having to trust each other with coordinates.

This is a plugin rewrite of the original Skript. It adds a proper `leave` command, leaves the queue when you log out, times people out individually instead of wiping everyone every minute, and finds safe ground (no lava, water, cacti, and so on) before it teleports anyone.

## Commands

| Command | Who | What |
|---|---|---|
| `/rtpqueue` (alias `/rtpq`) | everyone | Join the queue |
| `/rtpqueue leave` | everyone | Leave the queue |
| `/rtpqueue-clear` | `rtpqueue.clear` (ops) | Empty the queue if it ever gets stuck |

## Permissions

- `rtpqueue.use` (default: everyone)
- `rtpqueue.clear` (default: op)

## Config

`plugins/RTPQueue/config.yml`: world, distance from 0,0, how far apart the two players land, queue timeout, and every message (with `&` color codes).

## Building

Needs a JDK (21+) and the Paper API jar:

```powershell
.\build.ps1 -Api path\to\paper-api.jar
```

The jar is written to `RTPQueue.jar`. Folia is supported (`folia-supported: true`); all world reads run on the owning region thread.