# past.dev for JetBrains IDEs

Your conversations with Junie become memory, and Junie can recall it.

[past.dev](https://past.dev) is a memory platform. This plugin connects Junie, JetBrains' coding agent,
to a past.dev project: every Junie session, in the IDE's AI chat or in Junie's terminal, is read into
past.dev when it is over, and past.dev's `past_recall` tool gives Junie what was decided before, why, and
what was already tried.

## Install

Requires a JetBrains IDE 2026.2 or later and a project API key: it starts with `past_sk_` and comes
from **Build › API keys** in the [past.dev console](https://past.dev).

1. Build the plugin (`./gradlew buildPlugin`) and install `build/distributions/past-jetbrains-*.zip`
   with **Settings › Plugins › ⚙ › Install Plugin from Disk…**.
2. Open **Settings › Tools › past.dev** and enter the key and your identity: the email or id every
   memory is attributed to. A machine already connected by another past.dev plugin needs nothing more;
   every past.dev plugin reads `~/.past/config.json`.
3. Allow the plugin to send your Junie sessions: tick **Send Junie's sessions** there, or answer
   **Send them** when the IDE asks. Nothing is sent before you do.
4. Ask Junie which past.dev tools it has: `past_recall` is listed beside its own tools, with nothing to
   turn on. Other agents in the AI chat reach it once the IDE's MCP server
   (**Settings › Tools › MCP Server**) is on and passed to them.

The **past.dev** tool window shows what has been sent from this machine, sends what waits, and searches
the project's memory.

## When a session is sent

Junie keeps every session in `~/.junie/sessions/`. A session goes to past.dev as **sittings**, one entry
each, cut where the session went quiet for half an hour (`sittingMinutes` in `~/.past/config.json`),
so each memory is dated at the time it was said. The plugin sends a session once it has been quiet
that long, and when the IDE quits, once you have allowed it. Whatever is said in it afterwards starts a new sitting, so what
was sent is never sent again.

Sessions from before the plugin was connected are your history. They are sent only when you ask
(**Send history…** in the tool window), after the plugin says what that costs.

## What leaves this machine

Only the prose of a conversation: what you typed and what Junie answered, with secrets that look
like keys or tokens redacted. Tool runs, their output, file contents, diffs and the environment
Junie records stay here. An unchanged session is never sent twice. Each request and each session
sent also carries the IDE's name and version (for example "DataGrip 2026.2.3"), so past.dev can tell
which IDE a call came from. A project whose folder is listed
in `deny` in `~/.past/config.json` is never read.

## What this repository is

```
src/main/kotlin/dev/past/jetbrains/
  PastConfig.kt, PastConnect.kt   the connection, in ~/.past/config.json
  PastApi.kt, Redaction.kt        the Memory API, and what is removed before anything leaves
  junie/                          Junie's sessions: reading, sittings, what was sent, the send loop
  mcp/                            the past_recall tool on the IDE's MCP server, listed to Junie
  settings/, toolwindow/          Settings › Tools › past.dev, and the past.dev tool window
```

The plugin talks only to past.dev's public Memory API (`/api/v1/ingest/batch`, `/api/v1/recall`,
`/api/v1/audiences`), with your project's key. Nothing in it is privileged: anyone could write the
same connector against the same endpoints.

[AGENTS.md](AGENTS.md) holds the rules the code keeps, for anyone (person or agent) changing it.

## License

MIT, see [LICENSE](LICENSE).
