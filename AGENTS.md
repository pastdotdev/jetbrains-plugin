# past.dev for JetBrains IDEs: working on the plugin

The past.dev plugin for JetBrains IDEs, for Junie. It runs inside the IDE on the developer's machine and
talks to past.dev's public Memory API. What a user sees is in `README.md`; this file is the rules the
code keeps, for whoever changes it.

## How it fits together

```
IDE starts, first project opens        PastStartup → JunieCapture.start(), JunieConsentNotice.askIfNeeded()
every minute, once the person allowed  JunieCapture.sendWaiting(now = false)
  under JunieState.locked              one IDE at a time, across processes
    JunieStore.list()                  ~/.junie/sessions/<id>/events.jsonl + index.jsonl
    a session changed since its send, quiet for its sitting length (or left by an earlier IDE run)
      JunieTranscript.read             events → turns: typed prompts, Junie's answer blocks
      JunieTranscript.plan             sittings, rendered and hashed; the ones past.dev lacks
      PastApi.ingestBatch              POST /api/v1/ingest/batch, one call per session
      JunieState.save                  ~/.past/junie/state.json
IDE quits                              JunieShutdown → JunieCapture.sendOnQuit(), five seconds at most
Settings › Tools › past.dev                PastConnect.connect → ~/.past/config.json
past.dev tool window                       status · Send now · Send history… · search (PastApi.recall)
Junie's session of the IDE's tools     PastToolFilter (last) → past_recall direct, as mcp_<ide>_past_recall
an agent calls past_recall             PastToolset → PastApi.recall → POST /api/v1/recall
```

## Rules

1. **Nothing breaks the IDE.** Every API call is time-boxed and answers with a `Reply`; a failure is
   kept as `lastError` in the state and shown in the tool window. After a failure the loop leaves
   the API alone for fifteen minutes, unless the person presses **Send now**.
2. **Only prose travels.** `JunieTranscript.read` keeps `UserPromptEvent` (its `presentablePrompt`,
   what the person typed), the person's answers to Junie's questions, and Junie's markdown and
   result blocks. Tool runs, file changes, model usage and `EnvironmentVariablesUpdatedEvent` are
   never rendered. Of Junie's folder, only `events.jsonl` and `index.jsonl` are opened:
   `state.json` there and `secure_credentials.json` hold the environment and credentials.
3. **A block's last word stands, at its first time.** Junie rewrites an answer under the same
   `stepId` as it streams, and broadcasts every block once more when a task completes. The turn
   keeps the time the block started, so the rendering, and its hash, settle once the answer does.
4. **A content hash gates every send.** `plan` hashes the whole rendering and each sitting; an
   unchanged session sends nothing, and a grown one sends the sittings that changed.
5. **What is sent never changes by a byte.** The heading, `Developer`, `Junie`, the `## … · time`
   lines, the time format and the redaction patterns are all in the hash: change one, and every
   session already sent goes again as a paid revision. `JunieTranscriptTest` pins the format.
6. **Sittings, and when a session is sent.** A new sitting starts at the first turn after
   `sittingMinutes` of silence, measured over every event, tool runs included, so an agent at work
   is never quiet. A session keeps the length it was first sent with. The first sitting's id is
   `junie:<session id>`, the next ones `junie:<session id>:<n>`. A session is sent once it has been
   quiet for that same length: anything said later starts a new sitting, so a sent sitting never
   changes. Sending earlier would pay for revisions; `JunieTranscriptTest` pins the boundary.
7. **History needs a yes.** `sinceMs` is when capture first ran. A session last active before it is
   sent only from **Send history…**, which shows the size and the credits first.
8. **One writer at a time.** Two IDEs can carry the plugin. Every read-change-write of the state
   goes through `JunieState.locked`, a file lock across processes.
9. **`~/.past` is the person's alone.** Folders are created `700` and files `600`, written beside
   their target and moved over it. The connection is shared with any other past.dev plugin on the
   machine; this plugin's own state lives in `~/.past/junie/`.
10. **Public APIs only.** The IntelliJ Platform and the IDE's MCP server. No internal extension point.
    On the IDE's MCP server every tool starts router-only, reachable through `execute_tool` by a
    model that already knows its name; nothing gives Junie that name. `PastToolFilter`, registered
    last, makes `past_recall` direct and changes nothing else, so a tool the person switched off
    stays off.
11. **Nothing blocks the UI thread.** File reads and API calls run on `Dispatchers.IO`.

12. **The product is written past.dev.** Every sentence a person reads says past.dev: the plugin's
    name, the tool window, the settings page, notifications, every message. Identifiers keep `past`:
    the plugin id `dev.past.jetbrains`, the tool `past_recall`, `~/.past`, the key prefix `past_sk_`
    and the data point ids. So does the recall block's header, which the reader drops by its exact
    text.

13. **Every call names its IDE.** `Ide` reads the platform's own product name, version and build.
    Every request carries them in its User-Agent (`past-junie/<version> (<IDE> <version>; <build>)`),
    and every session sent carries `sentFrom` in its metadata. `sentFrom` is the IDE that sent the
    session, which is not always where it took place: every IDE with the plugin watches the same
    sessions, the Junie CLI's included.

14. **Nothing is sent before the person allows it.** `JunieCapture.consent` starts `NotAsked`, and only
    `Allowed` sends on its own; an answer the IDE cannot read counts as not asked. A machine another
    past.dev plugin connected needs no visit to Settings, so `JunieConsentNotice` asks once per IDE
    run, while the machine is connected and nobody has answered. The Settings checkbox gives the same
    answer. `JunieConsentTest` pins the default. **Send history…** asks for itself, with its cost.

## Building

```
./gradlew test             # the Junie reader, sittings and the send plan
./gradlew buildPlugin      # build/distributions/past-jetbrains-<version>.zip
./gradlew verifyPlugin     # the Plugin Verifier: IDEA 2026.2 and 2026.2.3, PyCharm, WebStorm, Rider 2026.2.3
./gradlew signPlugin       # build/distributions/past-jetbrains-<version>-signed.zip, skipped without a certificate
./gradlew runIde           # a sandbox IDE with the plugin installed
```

The build compiles against IntelliJ IDEA 2026.2, the oldest platform the plugin supports: the IDE's
MCP server took its router-only tool states there, and Junie in the AI chat came in the same cycle.

A release is signed with the author's certificate, which the IDE checks at install. `signPlugin`
reads it from the environment, never from this repository: `CERTIFICATE_CHAIN` (the certificate),
`PRIVATE_KEY` (its encrypted key) and `PRIVATE_KEY_PASSWORD`. JetBrains' plugin signing guide gives
the `openssl` commands that make them. Run `./gradlew verifyPluginSignature` as its own command after
`signPlugin` to check the signed zip.

The Marketplace takes the 40×40 logo in `META-INF/pluginIcon.svg`, with `pluginIcon_dark.svg` for
dark themes, and the release notes in `<change-notes>` in `plugin.xml`.

## Testing by hand

Point the connection at a stand-in API (`apiUrl` on `localhost`) so nothing reaches a real project,
and set `sittingMinutes` to 5 in `~/.past/config.json`. Then, in an IDE with the plugin installed:

1. Settings › Tools › past.dev: save a key and an identity. `~/.past/config.json` holds them, `600`.
   Leave **Send Junie's sessions** unticked and restart the IDE: a notice asks whether to send them,
   and nothing reaches the stand-in until **Send them**.
2. Ask Junie something in the AI chat. Five minutes after its last event, the stand-in receives one
   `/api/v1/ingest/batch` whose content is the prompt and the answer, and nothing else.
3. Ask again in the same session: the next send carries only the new sitting, `junie:<id>:2`.
4. Ask once more and quit the IDE at once: the stand-in receives that sitting before the IDE closes.
5. In the past.dev tool window, **Status** counts the session as sent; **Send history…** lists what was
   there before and costs it before sending.
6. Ask Junie which past.dev tools it has: `past_recall` is listed, with the IDE's MCP server switch off.
   Ask it to use it: the stand-in receives `/api/v1/recall`.
